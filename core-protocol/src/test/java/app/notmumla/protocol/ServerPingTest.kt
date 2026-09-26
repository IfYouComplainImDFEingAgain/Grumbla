package app.notmumla.protocol

import app.notmumla.protocol.udp.ServerPing
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.nio.ByteBuffer

class ServerPingTest {

    @Test
    fun requestIsZeroHeaderPlusIdent() {
        val req = ServerPing.request(0x0102030405060708L)
        assertEquals(12, req.size)
        assertEquals(0, ByteBuffer.wrap(req).int)
        assertEquals(0x0102030405060708L, ByteBuffer.wrap(req, 4, 8).long)
    }

    @Test
    fun parsesReply() {
        val reply = ByteBuffer.allocate(24)
            .putInt((1 shl 16) or (5 shl 8) or 255).putLong(42L)
            .putInt(7).putInt(100).putInt(558_000).array()
        val info = ServerPing.parse(reply, 24, 42L, 12)!!
        assertEquals("1.5.255", info.version)
        assertEquals(7, info.users)
        assertEquals(100, info.maxUsers)
        assertEquals(558_000, info.maxBandwidth)
        assertEquals(12, info.latencyMs)
    }

    @Test
    fun rejectsWrongIdentOrLength() {
        val reply = ByteBuffer.allocate(24).putInt(0).putLong(42L).array()
        assertNull(ServerPing.parse(reply, 24, 43L, 0))
        assertNull(ServerPing.parse(reply, 12, 42L, 0))
    }

    @Test
    fun pingsLocalServer() = runBlocking {
        val up = runCatching { Socket("127.0.0.1", Mumble.DEFAULT_PORT).close() }.isSuccess
        assumeTrue("no local Mumble server", up)
        val info = ServerPing.ping("127.0.0.1", Mumble.DEFAULT_PORT)
        assertNotNull(info)
        assertTrue(info!!.maxUsers > 0)
        assertTrue(info.version.startsWith("1."))
    }
}
