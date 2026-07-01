package app.notmumla.protocol.model

enum class ConnectionState { DISCONNECTED, CONNECTING, HANDSHAKING, CONNECTED, FAILED }

/** A connected user as reflected by the server's UserState messages. */
data class User(
    val session: Int,
    val name: String,
    val channelId: Int = 0,
    val mute: Boolean = false,
    val deaf: Boolean = false,
    val suppress: Boolean = false,
    val selfMute: Boolean = false,
    val selfDeaf: Boolean = false,
    val prioritySpeaker: Boolean = false,
    val comment: String? = null,
    val certHash: String? = null,
) {
    val effectivelyMuted: Boolean get() = mute || selfMute || suppress
    val effectivelyDeaf: Boolean get() = deaf || selfDeaf
}

/** A channel in the server's tree. */
data class Channel(
    val id: Int,
    val parent: Int? = null,
    val name: String,
    val position: Int = 0,
    val temporary: Boolean = false,
    val description: String? = null,
)

/** Immutable snapshot of the whole server session, surfaced as a StateFlow by MumbleClient. */
/** Live audio packet stats for the debug overlay. Rates are per-second (updated ~2×/s). */
data class AudioDebugStats(
    val sent: Long = 0,
    val received: Long = 0,
    val lost: Long = 0,
    val sentPerSec: Int = 0,
    val recvPerSec: Int = 0,
)

data class ServerState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val sessionId: Int? = null,
    val channels: Map<Int, Channel> = emptyMap(),
    val users: Map<Int, User> = emptyMap(),
    val welcomeText: String? = null,
    val serverFingerprintSha256: String? = null,
    /** Set when the connection failed because the server's cert no longer matches the pinned one. */
    val certMismatchFingerprint: String? = null,
    /** Max length of an image (HTML) message the server accepts; 0 = unknown (use a safe default). */
    val imageMessageLength: Int = 0,
    val allowHtml: Boolean = true,
    val error: String? = null,
    /** Set when the failure is fatal (kick, ban, reject) and auto-reconnect should NOT retry. */
    val fatal: Boolean = false,
) {
    val self: User? get() = sessionId?.let { users[it] }

    /** Users grouped by channel id, for rendering the tree. */
    fun usersInChannel(channelId: Int): List<User> =
        users.values.filter { it.channelId == channelId }.sortedBy { it.name.lowercase() }

    /**
     * Direct children of [parentId], ordered by position then name. The Mumble root channel
     * (id 0) has no parent, so `childChannels(null)` yields the root.
     */
    fun childChannels(parentId: Int?): List<Channel> =
        channels.values.filter { it.parent == parentId }
            .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
}
