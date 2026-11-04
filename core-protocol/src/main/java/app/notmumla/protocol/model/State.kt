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
    /** The user's client is recording (`UserState.recording`); Murmur only lets a user set it on itself. */
    val recording: Boolean = false,
    val comment: String? = null,
    /** SHA-1 (hex) of the comment, sent instead of [comment] when it is 128+ chars. */
    val commentHash: String? = null,
    /** SHA-1 (hex) of the avatar ("texture"); null when the user has none. */
    val textureHash: String? = null,
    /**
     * The avatar image bytes, once we have them. Murmur sends small ones inline and only the hash for the
     * rest, which then need a `RequestBlob`. Kept across updates while [textureHash] doesn't change, so
     * the array instance (and any image cache keyed on it) stays the same.
     */
    val texture: ByteArray? = null,
    /** Registered user id on this server; null for a guest (unregistered) session. */
    val userId: Int? = null,
    val certHash: String? = null,
    /** Channels this user is listening to (channel listeners) without being in them. */
    val listeningChannels: Set<Int> = emptySet(),
) {
    val effectivelyMuted: Boolean get() = mute || selfMute || suppress
    val effectivelyDeaf: Boolean get() = deaf || selfDeaf
    val registered: Boolean get() = userId != null
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
    /** True while voice is flowing over the direct UDP path; false when tunneled over TCP. */
    val udp: Boolean = false,
)

data class ServerState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val sessionId: Int? = null,
    val channels: Map<Int, Channel> = emptyMap(),
    val users: Map<Int, User> = emptyMap(),
    val welcomeText: String? = null,
    val serverFingerprintSha256: String? = null,
    /**
     * Set when the connection failed because the server's cert isn't trusted: nothing pinned yet
     * ([untrustedCertFirstUse]) or it no longer matches the pin. The UI asks the user to accept it.
     */
    val untrustedCertFingerprint: String? = null,
    val untrustedCertFirstUse: Boolean = false,
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

    /** Users listening to [channelId] from elsewhere (Mumble "channel listeners"). */
    fun listenersOf(channelId: Int): List<User> =
        users.values.filter { channelId in it.listeningChannels }.sortedBy { it.name.lowercase() }

    /**
     * Direct children of [parentId], ordered by position then name. The Mumble root channel
     * (id 0) has no parent, so `childChannels(null)` yields the root.
     */
    fun childChannels(parentId: Int?): List<Channel> =
        channels.values.filter { it.parent == parentId }
            .sortedWith(compareBy({ it.position }, { it.name.lowercase() }))
}
