package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Isolates the send/receive + packet-codec path from server routing: a single client transmits to
 * the server-loopback target (31), which the server echoes straight back to the sender.
 */
class VoiceLoopbackTest {
    private val host = "127.0.0.1"
    private val port = 64738
    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess

    @Test
    fun serverLoopbackEchoesAudio() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MumbleClient(IdentityCertificate.generate("echo"), scope, osVersion = "test")
        val got = CopyOnWriteArrayList<ByteArray>()
        client.voiceSink = object : VoiceSink {
            override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
                got += opus
            }
        }
        client.connect(ConnectConfig(host = host, port = port, username = "echo"))
        val connected = withTimeoutOrNull(15_000) {
            while (client.state.value.connection != ConnectionState.CONNECTED) {
                if (client.state.value.connection == ConnectionState.FAILED) return@withTimeoutOrNull false
                delay(100)
            }
            true
        } ?: false
        assertTrue("connected", connected)
        delay(300)

        val payload = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55)
        repeat(3) { client.sendAudio(payload, terminator = it == 2, target = 31) }

        val echoed = withTimeoutOrNull(5_000) {
            while (got.none { it.contentEquals(payload) }) delay(50)
            true
        } ?: false
        assertTrue("server loopback echoed our audio", echoed)
        client.disconnect()
    }
}
