package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket

/**
 * Plugin data through a real server: alice sends to bob only; bob receives it with alice's session
 * stamped as the sender, and carol (not a receiver) gets nothing.
 */
class PluginDataIntegrationTest {

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
    fun pluginDataReachesOnlyReceivers() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val alice = MumbleClient(IdentityCertificate.generate("p-alice"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("p-bob"), scope, osVersion = "test")
        val carol = MumbleClient(IdentityCertificate.generate("p-carol"), scope, osVersion = "test")
        for ((c, n) in listOf(alice to "p-alice", bob to "p-bob", carol to "p-carol")) {
            c.connect(ConnectConfig(host = host, port = port, username = n))
        }
        for (c in listOf(alice, bob, carol)) assertTrue(awaitConnected(c))
        delay(500)

        var carolGot: MumbleClient.Event.PluginData? = null
        val carolJob = scope.launch {
            carolGot = carol.events.filterIsInstance<MumbleClient.Event.PluginData>().first()
        }
        var bobGot: MumbleClient.Event.PluginData? = null
        val bobJob = scope.launch {
            bobGot = bob.events.filterIsInstance<MumbleClient.Event.PluginData>().first()
        }
        delay(200) // events is a SharedFlow without replay: collectors must be running before the send

        val payload = "hello".toByteArray()
        alice.sendPluginData(listOf(bob.state.value.sessionId!!), "notmumla/test", payload)

        withTimeoutOrNull(5_000) { while (bobGot == null) delay(50) }
        delay(500) // give a misrouted copy time to reach carol
        val got = bobGot
        assertNotNull("bob received the plugin data", got)
        assertEquals(alice.state.value.sessionId, got!!.sender)
        assertEquals("notmumla/test", got.dataId)
        assertArrayEquals(payload, got.data)
        assertNull("carol is not a receiver", carolGot)

        carolJob.cancel(); bobJob.cancel()
        listOf(alice, bob, carol).forEach { it.disconnect() }
    }
}
