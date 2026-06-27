package app.notmumla.ui.channels

import androidx.compose.ui.graphics.Color
import app.notmumla.protocol.model.ServerState
import app.notmumla.protocol.model.User
import app.notmumla.ui.UiChannel
import app.notmumla.ui.UiUser
import app.notmumla.ui.UserStatus
import kotlin.math.abs

/** Stable avatar palette; colour chosen by name hash so a user keeps the same colour. */
private val AVATAR_COLORS = listOf(
    Color(0xFFE0613A), Color(0xFF1F8A5B), Color(0xFF7C5CFF), Color(0xFFA98600),
    Color(0xFF2A6FDB), Color(0xFFB0457B), Color(0xFF2A8C8C), Color(0xFFC0562C),
)

fun avatarColorFor(name: String): Color =
    AVATAR_COLORS[abs(name.hashCode()) % AVATAR_COLORS.size]

fun initialsFor(name: String): String {
    val parts = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return when {
        parts.isEmpty() -> "?"
        parts.size == 1 -> parts[0].take(2).uppercase()
        else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
    }
}

private fun User.toUiUser(isYou: Boolean, speaking: Boolean): UiUser = UiUser(
    id = session,
    name = name,
    initials = initialsFor(name),
    avatar = avatarColorFor(name),
    status = when {
        speaking -> UserStatus.SPEAKING
        effectivelyMuted -> UserStatus.MUTED
        else -> UserStatus.ACTIVE
    },
    isYou = isYou,
    isPrioritySpeaker = prioritySpeaker,
)

/**
 * Flatten the server's channel tree into a depth-ordered list of [UiChannel] with their users,
 * which the Tree/Speakers/Compact layouts already know how to render.
 * [speakingSessions] marks users currently transmitting (populated once audio lands in M3).
 */
fun ServerState.toUiChannels(speakingSessions: Set<Int> = emptySet()): List<UiChannel> {
    val selfSession = sessionId
    val currentChannel = self?.channelId
    val out = mutableListOf<UiChannel>()

    fun walk(parentId: Int?, depth: Int) {
        for (channel in childChannels(parentId)) {
            val users = usersInChannel(channel.id).map {
                it.toUiUser(isYou = it.session == selfSession, speaking = it.session in speakingSessions)
            }
            out += UiChannel(
                id = channel.id,
                name = channel.name,
                depth = depth,
                locked = false,
                users = users,
                isCurrent = channel.id == currentChannel,
            )
            walk(channel.id, depth + 1)
        }
    }
    walk(null, 0)
    return out
}
