package app.notmumla.protocol.udp

import MumbleUDP.Ping as UdpPing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * The direct Mumble UDP voice path. Sends OCB2-encrypted audio (and periodic pings) to the same
 * host:port as the TCP control channel, and decrypts inbound datagrams back into raw audio packets
 * (identical bytes to a UDPTunnel body, so the caller reuses the same parser).
 *
 * Following the reference connectivity model (docs/dev/network-protocol/voice_data.md): the client
 * pings over UDP; any successfully decrypted reply proves the path works, at which point audio is
 * sent over UDP. If replies dry up (NAT/firewall/roaming), [active] drops and the caller falls back
 * to the TCP tunnel — but pinging continues so the path can recover.
 */
class UdpTransport(
    host: String,
    port: Int,
    private val crypt: CryptStateOCB2,
    private val scope: CoroutineScope,
    /** Pre-1.5 servers use the legacy ping/audio framing; ≥1.5 use the protobuf framing. */
    private val legacy: Boolean,
    /** Called with a decrypted raw audio packet (same format as a UDPTunnel body). */
    private val onAudio: (ByteArray) -> Unit,
    /** Called when the UDP path becomes usable (true) or stops responding (false). */
    private val onActiveChanged: (Boolean) -> Unit,
) {
    private val remote = InetSocketAddress(InetAddress.getByName(host), port)
    private var socket: DatagramSocket? = null
    private var recvJob: Job? = null
    private var pingJob: Job? = null

    @Volatile private var lastGoodMs = 0L
    @Volatile var active = false
        private set

    fun start() {
        val s = DatagramSocket()
        s.connect(remote)          // filter to the server and let us send/recv without re-addressing
        s.soTimeout = RECV_TIMEOUT_MS
        socket = s
        recvJob = scope.launch(Dispatchers.IO) { receiveLoop(s) }
        pingJob = scope.launch(Dispatchers.IO) { pingLoop() }
    }

    /** Encrypt and send one raw audio packet over UDP. No-op if the socket is gone. */
    fun sendAudio(rawPacket: ByteArray) {
        val s = socket ?: return
        val enc = runCatching { crypt.encrypt(rawPacket) }.getOrNull() ?: return
        runCatching { s.send(DatagramPacket(enc, enc.size, remote)) }
    }

    fun stop() {
        recvJob?.cancel()
        pingJob?.cancel()
        runCatching { socket?.close() }
        socket = null
        setActive(false)
    }

    private fun receiveLoop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        while (scope.isActive && !s.isClosed) {
            val dp = DatagramPacket(buf, buf.size)
            try {
                s.receive(dp)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: Exception) {
                break
            }
            val plain = crypt.decrypt(dp.data.copyOfRange(dp.offset, dp.offset + dp.length)) ?: continue
            // Any packet that decrypts proves the crypto is in sync and the path is alive.
            lastGoodMs = System.currentTimeMillis()
            if (!active) setActive(true)
            if (plain.isNotEmpty() && !isPing(plain)) onAudio(plain)
        }
    }

    private suspend fun pingLoop() {
        while (scope.isActive) {
            runCatching { socket?.send(DatagramPacket(pingPacket(), pingPacket().size, remote)) }
            // Drop back to TCP if we haven't decrypted anything from the server in a while.
            if (active && System.currentTimeMillis() - lastGoodMs > CONFIRM_WINDOW_MS) setActive(false)
            delay(PING_INTERVAL_MS)
        }
    }

    private fun setActive(value: Boolean) {
        if (active == value) return
        active = value
        onActiveChanged(value)
    }

    /** Build an encrypted UDP ping. The timestamp is opaque (we don't decode echoes for RTT yet). */
    private fun pingPacket(): ByteArray {
        val ts = System.nanoTime()
        val body = if (legacy) {
            ByteArrayOutputStream().apply {
                write(LEGACY_PING_HEADER)
                MumbleVarint.encode(this, ts)
            }.toByteArray()
        } else {
            val proto = UdpPing(timestamp = ts).encode()
            ByteArray(proto.size + 1).also {
                it[0] = PROTOBUF_TYPE_PING.toByte()
                proto.copyInto(it, 1)
            }
        }
        return crypt.encrypt(body)
    }

    /** True if a decrypted packet is a ping (echo), not audio — by the same header rule as sending. */
    private fun isPing(plain: ByteArray): Boolean =
        if (legacy) ((plain[0].toInt() and 0xFF) ushr 5) == LEGACY_PING_TYPE
        else plain[0].toInt() == PROTOBUF_TYPE_PING

    companion object {
        private const val PING_INTERVAL_MS = 2_000L
        private const val RECV_TIMEOUT_MS = 1_000
        /** Fall back to TCP if no UDP packet decrypts within this window. */
        private const val CONFIRM_WINDOW_MS = 8_000L

        private const val PROTOBUF_TYPE_PING = 1     // MumbleUDP type byte for Ping
        private const val LEGACY_PING_HEADER = 0x20  // legacy ping header byte (type 1 << 5)
        private const val LEGACY_PING_TYPE = 1       // legacy audio header top-3-bits value for ping
    }
}
