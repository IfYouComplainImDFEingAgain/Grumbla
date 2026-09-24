package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whisper routing through a real server: alice registers a voice target for bob only and sends with
 * it; bob must hear her, carol (same channel) must not. Normal talk afterwards reaches carol again.
 */
class WhisperIntegrationTest {

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

    private fun sink(into: MutableList<Int>) = object : VoiceSink {
        override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
            into += session
        }
    }

    @Test
    fun whisperReachesOnlyTarget() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = MumbleClient(IdentityCertificate.generate("w-alice"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("w-bob"), scope, osVersion = "test")
        val carol = MumbleClient(IdentityCertificate.generate("w-carol"), scope, osVersion = "test")
        val bobGot = CopyOnWriteArrayList<Int>()
        val carolGot = CopyOnWriteArrayList<Int>()
        bob.voiceSink = sink(bobGot)
        carol.voiceSink = sink(carolGot)

        for ((c, n) in listOf(alice to "w-alice", bob to "w-bob", carol to "w-carol")) {
            c.connect(ConnectConfig(host = host, port = port, username = n))
        }
        for (c in listOf(alice, bob, carol)) assertTrue(awaitConnected(c))
        delay(500)
        val aliceSession = alice.state.value.sessionId!!

        alice.setWhisperTarget(1, listOf(bob.state.value.sessionId!!))
        delay(300) // VoiceTarget is sent async; let it land before the first frame uses it
        val payload = byteArrayOf(0xFC.toByte(), 0xFF.toByte(), 0xFE.toByte(), 0x01)
        repeat(5) { alice.sendAudio(payload, terminator = it == 4, target = 1) }

        val bobHeard = withTimeoutOrNull(5_000) {
            while (aliceSession !in bobGot) delay(50)
            true
        } ?: false
        delay(500) // give any misrouted frames time to reach carol
        assertTrue("bob heard the whisper", bobHeard)
        assertFalse("carol must not hear the whisper", aliceSession in carolGot)

        repeat(5) { alice.sendAudio(payload, terminator = it == 4) }
        val carolHeard = withTimeoutOrNull(5_000) {
            while (aliceSession !in carolGot) delay(50)
            true
        } ?: false
        assertTrue("normal talk reaches carol again", carolHeard)

        listOf(alice, bob, carol).forEach { it.disconnect() }
    }
}
