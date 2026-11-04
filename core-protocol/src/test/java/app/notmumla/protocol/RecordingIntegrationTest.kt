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
 * `UserState.recording` (NEXT 2.2) through a real server (default `allowRecording=true`): obs sees rec's
 * flag toggle on its [app.notmumla.protocol.model.User] and gets start/stop events, plus a "joined while
 * recording" event when rec moves into obs's channel with the flag already set.
 */
class RecordingIntegrationTest {

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
    fun recordingToggleIsMappedAndEmitted() = runBlocking {
        assumeTrue("No Mumble server on $host:$port — skipping", reachable())

        val tag = System.nanoTime().toString(36).takeLast(6)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val rec = MumbleClient(IdentityCertificate.generate("r-rec-$tag"), scope, osVersion = "test")
        val obs = MumbleClient(IdentityCertificate.generate("r-obs-$tag"), scope, osVersion = "test")
        val seen = CopyOnWriteArrayList<MumbleClient.Event.RecordingChanged>()
        obs.events.onEach { if (it is MumbleClient.Event.RecordingChanged) seen += it }.launchIn(scope)

        obs.connect(ConnectConfig(host = host, port = port, username = "r-obs-$tag"))
        assertTrue(awaitConnected(obs))
        rec.connect(ConnectConfig(host = host, port = port, username = "r-rec-$tag"))
        assertTrue(awaitConnected(rec))
        val r = rec.state.value.sessionId!!
        assertEquals(false, await { obs.state.value.users[r] }?.recording)

        rec.setSelfRecording(true)
        assertEquals(true, await { obs.state.value.users[r]?.recording?.takeIf { it } })
        assertEquals(
            MumbleClient.Event.RecordingChanged(r, "r-rec-$tag", recording = true),
            await { seen.firstOrNull() },
        )

        rec.setSelfRecording(false)
        assertEquals(false, await { obs.state.value.users[r]?.recording?.takeIf { !it } })
        assertEquals(
            MumbleClient.Event.RecordingChanged(r, "r-rec-$tag", recording = false),
            await { seen.getOrNull(1) },
        )

        // rec turns it on in a temp channel of its own (no event: not our channel), then moves into ours.
        // Temp channels need a registered user (default ACL: @auth MakeTempChannel). The creator moves in.
        rec.registerSelf()
        assertNotNull(await { rec.state.value.self?.userId })
        rec.createChannel(0, "r-room-$tag")
        val room = await { obs.state.value.channels.values.firstOrNull { it.name == "r-room-$tag" }?.id }
        await { obs.state.value.users[r]?.channelId?.takeIf { it == room } }
        // Five self-UserStates/ChannelStates in a row: Murmur's RATELIMIT (burst 5, then 1/s) silently drops more.
        delay(2_000)
        rec.setSelfRecording(true)
        assertEquals(true, await { obs.state.value.users[r]?.recording?.takeIf { it } })
        delay(300)
        assertEquals("nothing for a channel that isn't ours: $seen", 2, seen.size)
        rec.joinChannel(0)
        assertEquals(
            MumbleClient.Event.RecordingChanged(r, "r-rec-$tag", recording = true, joined = true),
            await { seen.getOrNull(2) },
        )

        listOf(rec, obs).forEach { it.disconnect() }
    }
}
