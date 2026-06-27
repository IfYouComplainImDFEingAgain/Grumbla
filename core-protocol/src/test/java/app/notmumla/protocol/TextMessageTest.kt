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

/** A channel text message from one client reaches another in the same channel via the server. */
class TextMessageTest {
    private val host = "127.0.0.1"
    private val port = 64738
    private fun reachable() = runCatching { Socket(host, port).close() }.isSuccess

    private suspend fun awaitConnected(c: MumbleClient) = withTimeoutOrNull(15_000) {
        while (c.state.value.connection != ConnectionState.CONNECTED) {
            if (c.state.value.connection == ConnectionState.FAILED) return@withTimeoutOrNull false
            delay(100)
        }
        true
    } ?: false

    @Test
    fun textMessageRoutesBetweenClients() = runBlocking {
        assumeTrue("no server", reachable())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = MumbleClient(IdentityCertificate.generate("alice"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("bob"), scope, osVersion = "test")

        val received = CopyOnWriteArrayList<String>()
        scope.launch {
            bob.events.collect { if (it is MumbleClient.Event.Text) received += it.text.message }
        }

        alice.connect(ConnectConfig(host = host, port = port, username = "alice"))
        bob.connect(ConnectConfig(host = host, port = port, username = "bob"))
        assertTrue(awaitConnected(alice)); assertTrue(awaitConnected(bob))
        delay(400)

        val channel = bob.state.value.self!!.channelId
        alice.sendText(channel, "hello from alice")

        val got = withTimeoutOrNull(5_000) {
            while (received.none { it.contains("hello from alice") }) delay(50); true
        } ?: false
        assertTrue("bob received alice's text; got=$received", got)

        alice.disconnect(); bob.disconnect()
    }
}
