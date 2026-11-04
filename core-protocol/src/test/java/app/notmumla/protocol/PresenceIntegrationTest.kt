package app.notmumla.protocol

import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.model.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Join/leave events (NEXT 2.1) through a real server, seen by obs in Root: none for the user list before
 * ServerSync, then connect, move out, move in, disconnect, and nothing when obs itself changes channel.
 */
class PresenceIntegrationTest {

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

    private suspend fun <T> await(block: () -> T?): T? =
        withTimeoutOrNull(5_000) {
            var v = block()
            while (v == null) { delay(50); v = block() }
            v
        }

    @Test
    fun joinLeaveEventsForOurChannel() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val tag = System.nanoTime().toString(36).takeLast(6)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val mover = MumbleClient(IdentityCertificate.generate("p-mover-$tag"), scope, osVersion = "test")
        val obs = MumbleClient(IdentityCertificate.generate("p-obs-$tag"), scope, osVersion = "test")
        val late = MumbleClient(IdentityCertificate.generate("p-late-$tag"), scope, osVersion = "test")
        val seen = CopyOnWriteArrayList<MumbleClient.Event>()
        obs.events.onEach { if (it is MumbleClient.Event.UserJoined || it is MumbleClient.Event.UserLeft) seen += it }
            .launchIn(scope)

        // mover is already in Root when obs logs in: its UserState is part of the pre-sync flood.
        mover.connect(ConnectConfig(host = host, port = port, trustUnpinned = true, username = "p-mover-$tag"))
        assertTrue(awaitConnected(mover))
        val m = mover.state.value.sessionId!!
        obs.connect(ConnectConfig(host = host, port = port, trustUnpinned = true, username = "p-obs-$tag"))
        assertTrue(awaitConnected(obs))
        delay(500)
        assertTrue("no events for users present before ServerSync: $seen", seen.isEmpty())

        late.connect(ConnectConfig(host = host, port = port, trustUnpinned = true, username = "p-late-$tag"))
        assertTrue(awaitConnected(late))
        val l = late.state.value.sessionId!!
        assertEquals(
            MumbleClient.Event.UserJoined(l, "p-late-$tag", fromChannel = null),
            await { seen.firstOrNull { it is MumbleClient.Event.UserJoined && it.session == l } },
        )

        // Temp channels need a registered user (default ACL: @auth MakeTempChannel). The creator moves in.
        mover.registerSelf()
        assertNotNull(await { mover.state.value.self?.userId })
        mover.createChannel(0, "p-room-$tag")
        val room = await { obs.state.value.channels.values.firstOrNull { it.name == "p-room-$tag" }?.id }
        assertNotNull("temp channel created", room)
        assertEquals(
            MumbleClient.Event.UserLeft(m, "p-mover-$tag", toChannel = room, channelName = "p-room-$tag"),
            await { seen.firstOrNull { it is MumbleClient.Event.UserLeft && it.session == m } },
        )

        // obs follows into the room: mover was already there and late stays behind; neither is an event.
        seen.clear()
        obs.joinChannel(room!!)
        await { obs.state.value.self?.channelId?.takeIf { it == room } }
        obs.joinChannel(0)
        await { obs.state.value.self?.channelId?.takeIf { it == 0 } }
        delay(500)
        assertTrue("our own moves emit nothing: $seen", seen.isEmpty())

        // Murmur deletes the emptied temp room right after the move; the event still carries its name.
        mover.joinChannel(0)
        assertEquals(
            MumbleClient.Event.UserJoined(m, "p-mover-$tag", fromChannel = room, channelName = "p-room-$tag"),
            await { seen.firstOrNull { it is MumbleClient.Event.UserJoined && it.session == m } },
        )

        late.disconnect()
        assertEquals(
            MumbleClient.Event.UserLeft(l, "p-late-$tag", toChannel = null),
            await { seen.firstOrNull { it is MumbleClient.Event.UserLeft && it.session == l } },
        )

        listOf(mover, obs).forEach { it.disconnect() }
    }
}
