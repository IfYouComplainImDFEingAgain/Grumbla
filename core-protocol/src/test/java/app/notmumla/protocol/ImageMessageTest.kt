package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** An inline-image (HTML) message routes between clients, and ServerConfig is captured. */
class ImageMessageTest {
    private val host = "127.0.0.1"
    private val port = 64738
    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess
    private suspend fun awaitConnected(c: MumbleClient) = withTimeoutOrNull(15_000) {
        while (c.state.value.connection != ConnectionState.CONNECTED) {
            if (c.state.value.connection == ConnectionState.FAILED) return@withTimeoutOrNull false
            delay(100)
        }; true
    } ?: false

    @Test
    fun imageMessageRoutesAndConfigCaptured() = runBlocking {
        assumeTrue("no server", reachable())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = MumbleClient(IdentityCertificate.generate("alice-img"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("bob-img"), scope, osVersion = "test")
        val received = CopyOnWriteArrayList<String>()
        scope.launch { bob.events.collect { if (it is MumbleClient.Event.Text) received += it.text.message } }

        alice.connect(ConnectConfig(host = host, port = port, username = "alice-img"))
        bob.connect(ConnectConfig(host = host, port = port, username = "bob-img"))
        assertTrue(awaitConnected(alice)); assertTrue(awaitConnected(bob))
        delay(400)

        // ServerConfig should have populated the image-message limit.
        assertTrue("ServerConfig image limit captured (${alice.state.value.imageMessageLength})",
            alice.state.value.imageMessageLength > 0)

        // A ~16 KB base64 "image" message (well under the default 128 KB limit).
        val html = "<img src=\"data:image/jpeg;base64,${"A".repeat(16_000)}\" />"
        alice.sendText(bob.state.value.self!!.channelId, html)

        val got = withTimeoutOrNull(5_000) {
            while (received.none { it.contains("data:image") }) delay(50); true
        } ?: false
        assertTrue("bob received the inline-image message", got)
        assertTrue("image payload preserved", received.first { it.contains("data:image") }.length > 15_000)

        alice.disconnect(); bob.disconnect()
    }
}
