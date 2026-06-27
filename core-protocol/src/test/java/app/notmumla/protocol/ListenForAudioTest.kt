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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manual harness: connects as "listener" and waits up to 30s for inbound audio from any other
 * user, so the device app can be made to transmit (PTT) while this runs. Enabled only when the
 * env var NOTMUMLA_LISTEN=1 is set, so it never runs as part of the normal suite.
 */
class ListenForAudioTest {
    private val host = "127.0.0.1"
    private val port = 64738
    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess

    @Test
    fun listenForDeviceAudio() = runBlocking {
        assumeTrue("listener harness disabled", System.getenv("NOTMUMLA_LISTEN") == "1")
        assumeTrue("no server", reachable())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MumbleClient(IdentityCertificate.generate("listener"), scope, osVersion = "test")
        val frames = AtomicInteger()
        val senders = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        client.voiceSink = object : VoiceSink {
            override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
                frames.incrementAndGet(); senders += session
            }
        }
        client.connect(ConnectConfig(host = host, port = port, username = "listener"))
        withTimeoutOrNull(15_000) {
            while (client.state.value.connection != ConnectionState.CONNECTED) delay(100); true
        }
        println("LISTENER connected, waiting for device audio…")
        val got = withTimeoutOrNull(30_000) {
            while (frames.get() < 5) delay(100); true
        } ?: false
        println("LISTENER frames=${frames.get()} senders=$senders")
        assertTrue("received audio frames from the device (got ${frames.get()})", got)
        client.disconnect()
    }
}
