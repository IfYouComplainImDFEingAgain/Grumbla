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
 * End-to-end validation of the direct UDP voice path against a real Mumble server.
 *
 * The UDP path becoming active is itself proof of byte-exact OCB2 interop **in both directions**:
 * our encrypted ping must decrypt on the server, and the server's echo must decrypt for us. On top
 * of that we round-trip an audio frame through server loopback (target 31) over UDP.
 *
 * Requires a Mumble server on 127.0.0.1:64738 with UDP reachable; skips otherwise.
 */
class UdpVoiceIntegrationTest {

    private val host = "127.0.0.1"
    private val port = 64738

    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess

    private suspend fun awaitConnected(client: MumbleClient): Boolean =
        withTimeoutOrNull(15_000) {
            while (client.state.value.connection != ConnectionState.CONNECTED) {
                if (client.state.value.connection == ConnectionState.FAILED) return@withTimeoutOrNull false
                delay(100)
            }
            true
        } ?: false

    @Test
    fun udpPathConfirmsAndLoopsBackAudio() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MumbleClient(IdentityCertificate.generate("udptester"), scope, osVersion = "test")

        val received = CopyOnWriteArrayList<Triple<Int, Long, ByteArray>>()
        client.voiceSink = object : VoiceSink {
            override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
                received += Triple(session, sequence, opus)
            }
        }

        client.connect(ConnectConfig(host = host, port = port, username = "udptester"))
        assertTrue("connected", awaitConnected(client))

        // Wait for the encrypted UDP ping to be echoed and decrypted — proves OCB2 interop both ways.
        val udpUp = withTimeoutOrNull(12_000) {
            while (!client.stats.value.udp) delay(100)
            true
        } ?: false
        assertTrue("UDP path confirmed (OCB2 ping echo round-tripped)", udpUp)

        val session = client.state.value.sessionId!!
        // Server loopback: audio sent to target 31 is echoed back to us — now over the UDP path.
        val payload = byteArrayOf(0xFC.toByte(), 0xFF.toByte(), 0xEE.toByte(), 0x11)
        repeat(5) { client.sendAudio(payload, terminator = it == 4, target = 31) }

        val got = withTimeoutOrNull(5_000) {
            while (received.none { it.first == session && it.third.contentEquals(payload) }) delay(50)
            true
        } ?: false
        assertTrue("received our audio back via UDP server loopback", got)

        client.disconnect()
    }
}
