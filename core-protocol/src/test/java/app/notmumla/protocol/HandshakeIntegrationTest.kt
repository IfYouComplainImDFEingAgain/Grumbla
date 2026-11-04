package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.protocol.model.ServerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

        // Unique: a phone logged in to the same server as "user" would get this rejected as a duplicate.
        val name = "hs-" + System.nanoTime().toString(36).takeLast(6)
        val identity = IdentityCertificate.generate(commonName = name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val client = MumbleClient(identity = identity, scope = scope, osVersion = "test")

        client.connect(ConnectConfig(host = host, port = port, trustUnpinned = true, username = name))

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
        assertEquals(name, self!!.name)

        client.disconnect()
    }

    /** Waits for CONNECTED or FAILED and returns that state (null on timeout). */
    private suspend fun awaitSettled(client: MumbleClient): ServerState? = withTimeoutOrNull(15_000) {
        while (true) {
            val s = client.state.value
            if (s.connection == ConnectionState.CONNECTED || s.connection == ConnectionState.FAILED) {
                return@withTimeoutOrNull s
            }
            delay(100)
        }
        @Suppress("UNREACHABLE_CODE") null
    }

    /** NEXT 2.3: no pin means no connection until the fingerprint is accepted (then pinned). */
    @Test
    fun unpinnedServerFailsUntilFingerprintAccepted() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", serverReachable())
        val name = "tofu-" + System.nanoTime().toString(36).takeLast(6)
        val identity = IdentityCertificate.generate(commonName = name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        val first = MumbleClient(identity = identity, scope = scope, osVersion = "test")
        first.connect(ConnectConfig(host = host, port = port, username = name))
        val failed = awaitSettled(first)!!
        assertEquals(ConnectionState.FAILED, failed.connection)
        assertTrue("first-use flag", failed.untrustedCertFirstUse)
        val fp = failed.untrustedCertFingerprint
        assertNotNull("fingerprint offered for the prompt", fp)
        assertNull("never got past TLS", failed.sessionId)
        first.disconnect()

        val wrong = MumbleClient(identity = identity, scope = scope, osVersion = "test")
        wrong.connect(ConnectConfig(host = host, port = port, username = name, pinnedServerSha256 = "00:11"))
        val mismatch = awaitSettled(wrong)!!
        assertEquals(ConnectionState.FAILED, mismatch.connection)
        assertFalse("a changed cert is not first-use", mismatch.untrustedCertFirstUse)
        assertEquals(fp, mismatch.untrustedCertFingerprint)
        wrong.disconnect()

        val accepted = MumbleClient(identity = identity, scope = scope, osVersion = "test")
        accepted.connect(ConnectConfig(host = host, port = port, username = name, pinnedServerSha256 = fp))
        val ok = awaitSettled(accepted)!!
        assertEquals("connects once pinned (err=${ok.error})", ConnectionState.CONNECTED, ok.connection)
        assertNull(ok.untrustedCertFingerprint)
        accepted.disconnect()
    }
}
