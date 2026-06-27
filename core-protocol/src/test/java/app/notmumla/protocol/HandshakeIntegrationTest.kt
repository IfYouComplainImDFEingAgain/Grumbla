package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket

/**
 * End-to-end handshake test against a real Mumble server.
 *
 * Requires a server reachable at 127.0.0.1:64738 (the project's docker test server). The test is
 * skipped (assumed away) when nothing is listening, so it never fails CI environments without one.
 */
class HandshakeIntegrationTest {

    private val host = "127.0.0.1"
    private val port = 64738

    private fun serverReachable(): Boolean =
        runCatching { Socket(host, port).close() }.isSuccess

    @Test
    fun connectsAndReachesServerSync() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", serverReachable())

        val identity = IdentityCertificate.generate(commonName = "user")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MumbleClient(identity = identity, scope = scope, osVersion = "test")

        client.connect(ConnectConfig(host = host, port = port, username = "user"))

        val reached = withTimeoutOrNull(15_000) {
            while (true) {
                val s = client.state.value
                if (s.connection == ConnectionState.CONNECTED) return@withTimeoutOrNull true
                if (s.connection == ConnectionState.FAILED) {
                    throw AssertionError("Connection failed: ${s.error}")
                }
                delay(100)
            }
            @Suppress("UNREACHABLE_CODE") false
        }

        val state = client.state.value
        assertTrue("Did not reach ServerSync (state=${state.connection}, err=${state.error})",
            reached == true)
        assertEquals(ConnectionState.CONNECTED, state.connection)
        assertNotNull("Server assigned a session id", state.sessionId)
        assertTrue("Server tree has at least the root channel", state.channels.isNotEmpty())
        assertTrue("Our own user is present", state.users.containsKey(state.sessionId))
        assertNotNull("Server fingerprint captured (TOFU)", state.serverFingerprintSha256)

        val self = state.self
        assertNotNull(self)
        assertEquals("user", self!!.name)

        client.disconnect()
    }
}
