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
 * Verifies the **legacy** voice path used for pre-1.5 servers. Both clients advertise version
 * 1.4.0, so even the modern test server treats them as legacy peers and routes the legacy Opus
 * packet format between them — exercising encode + decode end to end.
 */
class LegacyVoiceTest {
    private val host = "127.0.0.1"
    private val port = 64738
    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess

    private fun legacyClient(name: String, scope: CoroutineScope) = MumbleClient(
        identity = IdentityCertificate.generate(name),
        scope = scope,
        clientVersion = MumbleClient.encodeVersion(1, 4, 0), // pre-protobuf → legacy audio
        osVersion = "test",
    )

    private suspend fun awaitConnected(c: MumbleClient) = withTimeoutOrNull(15_000) {
        while (c.state.value.connection != ConnectionState.CONNECTED) {
            if (c.state.value.connection == ConnectionState.FAILED) return@withTimeoutOrNull false
            delay(100)
        }
        true
    } ?: false

    @Test
    fun legacyAudioRoutesBetweenClients() = runBlocking {
        assumeTrue("no server", reachable())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = legacyClient("alice-legacy", scope)
        val bob = legacyClient("bob-legacy", scope)

        val received = CopyOnWriteArrayList<Triple<Int, Long, ByteArray>>()
        bob.voiceSink = object : VoiceSink {
            override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
                received += Triple(session, sequence, opus)
            }
        }

        alice.connect(ConnectConfig(host = host, port = port, username = "alice-legacy"))
        bob.connect(ConnectConfig(host = host, port = port, username = "bob-legacy"))
        assertTrue(awaitConnected(alice)); assertTrue(awaitConnected(bob))
        val aliceSession = alice.state.value.sessionId!!
        delay(500)

        val payload = byteArrayOf(0xFC.toByte(), 0x01, 0x02, 0x03, 0x04)
        repeat(5) { alice.sendAudio(payload, terminator = it == 4) }

        val got = withTimeoutOrNull(5_000) {
            while (received.none { it.first == aliceSession }) delay(50); true
        } ?: false
        assertTrue("bob (legacy) received alice's legacy audio; got=${received.map { it.first }}", got)
        assertTrue("payload preserved", received.first { it.first == aliceSession }.third.contentEquals(payload))

        alice.disconnect(); bob.disconnect()
    }
}
