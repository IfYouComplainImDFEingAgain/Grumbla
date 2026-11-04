package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import app.notmumla.protocol.model.User
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket

/**
 * Our own profile through a real server: comment (short = text, 128+ chars = hash only), avatar
 * texture (hash), and self-registration surfacing a user_id.
 */
class ProfileIntegrationTest {

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

    private suspend fun MumbleClient.awaitUser(session: Int, cond: (User) -> Boolean): User? =
        withTimeoutOrNull(5_000) {
            while (true) {
                state.value.users[session]?.takeIf(cond)?.let { return@withTimeoutOrNull it }
                delay(50)
            }
            @Suppress("UNREACHABLE_CODE") null
        }

    @Test
    fun commentTextureAndRegistration() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // Unique names: a registration persists in the server's database across runs.
        val tag = System.currentTimeMillis().toString(36)
        val alice = MumbleClient(IdentityCertificate.generate("pr-a-$tag"), scope, osVersion = "test")
        val bob = MumbleClient(IdentityCertificate.generate("pr-b-$tag"), scope, osVersion = "test")
        alice.connect(ConnectConfig(host = host, port = port, trustUnpinned = true, username = "pr-a-$tag"))
        bob.connect(ConnectConfig(host = host, port = port, trustUnpinned = true, username = "pr-b-$tag"))
        assertTrue(awaitConnected(alice))
        assertTrue(awaitConnected(bob))
        val a = alice.state.value.sessionId!!
        assertNull("a fresh user is a guest", alice.state.value.self!!.userId)

        alice.setSelfComment("hi there")
        assertNotNull(bob.awaitUser(a) { it.comment == "hi there" && it.commentHash == null })

        val long = "x".repeat(200)
        alice.setSelfComment(long)
        val seen = bob.awaitUser(a) { it.commentHash != null }
        assertNotNull("a 128+ char comment arrives as a hash", seen)
        assertNull(seen!!.comment)
        assertEquals(long.encodeUtf8().sha1().hex(), seen.commentHash)

        val png = ByteArray(500) { it.toByte() }
        alice.setSelfTexture(png)
        val tex = bob.awaitUser(a) { it.textureHash != null }
        assertEquals(png.toByteString().sha1().hex(), tex?.textureHash)

        alice.setSelfTexture(ByteArray(0))
        assertNotNull("an empty texture clears it", bob.awaitUser(a) { it.textureHash == null })

        // Murmur rate-limits each user's messages (burst 5, then ~1/s) and silently drops the excess.
        delay(2_500)
        alice.registerSelf()
        val reg = alice.awaitUser(a) { it.userId != null }
        assertNotNull("self-registration assigns a user id", reg)
        assertTrue(reg!!.registered)

        listOf(alice, bob).forEach { it.disconnect() }
    }
}
