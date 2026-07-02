package app.notmumla.ui

import androidx.compose.ui.graphics.Color

/**
 * Lightweight UI models used to render the screens with mock data in Milestone 1.
 * These are replaced/fed by real protocol state ([app.notmumla.protocol] models) in M2+.
 */

enum class UserStatus { ACTIVE, SPEAKING, MUTED, AFK }

data class UiUser(
    val id: Int,
    val name: String,
    val initials: String,
    val avatar: Color,
    val status: UserStatus = UserStatus.ACTIVE,
    val isYou: Boolean = false,
    val isPrioritySpeaker: Boolean = false,
    /** Local per-user volume adjustment in dB (0 = default); shown inline next to the name. */
    val gainDb: Int = 0,
)

data class UiChannel(
    val id: Int,
    val name: String,
    val depth: Int = 0,
    val locked: Boolean = false,
    val users: List<UiUser> = emptyList(),
    val isCurrent: Boolean = false,
    val expanded: Boolean = true,
)

data class UiServer(
    val id: Long,
    val host: String,
    val port: Int = 64738,
    val label: String,
    val initial: String,
    val accent: Color,
    val onlineCount: Int,
    val pingMs: Int,
    val online: Boolean,
)

enum class ChatKind { SYSTEM, OTHER, ME, FILE }

data class UiMessage(
    val id: Int,
    val kind: ChatKind,
    val name: String = "",
    val initials: String = "",
    val avatar: Color = Color.Gray,
    val time: String = "",
    val text: String = "",
    val fileName: String = "",
    val fileSize: String = "",
    /** Inline image bytes (JPEG/PNG) when this message carries an image. */
    val imageBytes: ByteArray? = null,
)

enum class ChannelLayout { TREE, SPEAKERS, COMPACT }

enum class TransmissionMode { PTT, VAD }
