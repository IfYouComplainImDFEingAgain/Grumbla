package app.notmumla.protocol.udp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import kotlin.random.Random

/** What an unauthenticated server ping reports; shown in the server list before connecting. */
data class ServerPingInfo(
    val version: String,
    val users: Int,
    val maxUsers: Int,
    val maxBandwidth: Int,
    val latencyMs: Long,
)

/**
 * The pre-connect "server list" ping: a plain (unencrypted) 12-byte UDP datagram of four zero bytes
 * + an 8-byte ident, answered with 24 bytes: legacy version, the echoed ident, user count, max
 * users and max bandwidth (all big-endian). Every server version answers this legacy form, unlike
 * the 1.5 protobuf ping, and servers with pings disabled (`allowping=false`) simply don't reply.
 */
object ServerPing {

    fun request(ident: Long): ByteArray =
        ByteBuffer.allocate(12).putInt(0).putLong(ident).array()

    /** Parses a reply, or null if it isn't a well-formed answer to [ident]. */
    fun parse(reply: ByteArray, length: Int, ident: Long, latencyMs: Long): ServerPingInfo? {
        if (length != 24) return null
        val buf = ByteBuffer.wrap(reply, 0, length)
        val v = buf.int
        if (buf.long != ident) return null
        return ServerPingInfo(
            version = "${(v ushr 16) and 0xFFFF}.${(v ushr 8) and 0xFF}.${v and 0xFF}",
            users = buf.int,
            maxUsers = buf.int,
            maxBandwidth = buf.int,
            latencyMs = latencyMs,
        )
    }

    /** Pings [host]:[port], retrying once; null if unresolvable, unreachable or pings are off. */
    suspend fun ping(host: String, port: Int, timeoutMs: Int = 1500, attempts: Int = 2): ServerPingInfo? =
        withContext(Dispatchers.IO) {
            val addr = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return@withContext null
            runCatching {
                DatagramSocket().use { socket ->
                    socket.soTimeout = timeoutMs
                    val target = InetSocketAddress(addr, port)
                    val reply = ByteArray(64)
                    repeat(attempts) {
                        val ident = Random.nextLong()
                        val out = request(ident)
                        val sentAt = System.nanoTime()
                        socket.send(DatagramPacket(out, out.size, target))
                        val deadline = sentAt + timeoutMs * 1_000_000L
                        while (true) {
                            val left = ((deadline - System.nanoTime()) / 1_000_000L).toInt()
                            if (left <= 0) break
                            socket.soTimeout = left
                            val packet = DatagramPacket(reply, reply.size)
                            try {
                                socket.receive(packet)
                            } catch (_: SocketTimeoutException) {
                                break
                            }
                            // A stale reply to the previous attempt fails the ident check; keep waiting.
                            val rtt = (System.nanoTime() - sentAt) / 1_000_000L
                            parse(reply, packet.length, ident, rtt)?.let { return@use it }
                        }
                    }
                    null
                }
            }.getOrNull()
        }
}
