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
 * Round-trips a voice frame through a real Mumble server over the TCP tunnel: client A transmits,
 * client B (in the same channel) must receive it with A's session id. Validates the legacy Opus
 * packet codec, varint encoding, UDPTunnel wrapping and server-side routing end to end.
 */
class VoiceTunnelIntegrationTest {

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
    fun audioFrameRoutesBetweenClients() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = MumbleClient(IdentityCertificate.generate("alice"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("bob"), scope, osVersion = "test")

        val received = CopyOnWriteArrayList<Triple<Int, Long, ByteArray>>()
        bob.voiceSink = object : VoiceSink {
            override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
                received += Triple(session, sequence, opus)
            }
        }

        alice.connect(ConnectConfig(host = host, port = port, username = "alice"))
        bob.connect(ConnectConfig(host = host, port = port, username = "bob"))

        assertTrue("alice connected", awaitConnected(alice))
        assertTrue("bob connected", awaitConnected(bob))

        val aliceSession = alice.state.value.sessionId!!
        // Give the user lists time to fully sync so both are seen in Root.
        delay(500)

        // A small but valid-looking payload; the server forwards Opus without decoding it.
        val payload = byteArrayOf(0xFC.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x01)
        repeat(5) { alice.sendAudio(payload, terminator = it == 4) }

        val got = withTimeoutOrNull(5_000) {
            while (received.none { it.first == aliceSession }) delay(50)
            true
        } ?: false

        assertTrue("bob received audio from alice (session $aliceSession); got=${received.map { it.first }}", got)
        val frame = received.first { it.first == aliceSession }
        assertTrue("payload preserved", frame.third.contentEquals(payload))

        alice.disconnect()
        bob.disconnect()
    }
}
