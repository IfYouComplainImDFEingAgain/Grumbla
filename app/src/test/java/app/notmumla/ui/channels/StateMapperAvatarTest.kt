package app.notmumla.ui.channels

import app.notmumla.protocol.model.Channel
import app.notmumla.protocol.model.ServerState
import app.notmumla.protocol.model.User
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Channel-row avatars (BACKLOG 3.4a): what each row shows and whether it asks for a fetch. */
class StateMapperAvatarTest {
    private val jpeg = byteArrayOf(1, 2, 3)
    private val state = ServerState(
        channels = mapOf(0 to Channel(id = 0, name = "Root")),
        users = mapOf(
            1 to User(session = 1, name = "fetched", textureHash = "aa", texture = jpeg),
            2 to User(session = 2, name = "hashonly", textureHash = "bb"),
            3 to User(session = 3, name = "none"),
        ),
    )

    private fun rows(show: Boolean) = state.toUiChannels(showAvatars = show).single().users.associateBy { it.id }

    @Test fun shownWhenOn() {
        val r = rows(true)
        assertSame(jpeg, r[1]!!.avatarImage)
        assertFalse(r[1]!!.avatarPending)
        assertNull(r[2]!!.avatarImage)
        assertTrue(r[2]!!.avatarPending)
        assertNull(r[3]!!.avatarImage)
        assertFalse(r[3]!!.avatarPending)
    }

    @Test fun nothingShownOrFetchedWhenOff() {
        rows(false).values.forEach {
            assertNull(it.avatarImage)
            assertFalse(it.avatarPending)
        }
    }
}
