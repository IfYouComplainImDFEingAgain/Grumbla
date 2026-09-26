package app.notmumla.data

/** One rendered chat entry. Server/system lines have [isSystem]; our own outgoing lines have [isMe]. */
data class ChatLine(
    val id: Long,
    val senderName: String,
    /** Plain text, for copying, notifications and read-aloud. */
    val text: String,
    val timeMillis: Long,
    /** The message as sent on the wire (Mumble chat is HTML), minus any inline images; rendered for display. */
    val html: String? = null,
    val isMe: Boolean = false,
    val isSystem: Boolean = false,
    /** Decoded image bytes when the message carried an inline `<img>` data URI. */
    val imageBytes: ByteArray? = null,
    /**
     * Set for private messages: the other party (the sender for inbound, the recipient for our own).
     * Null for channel/server messages.
     */
    val privatePeer: UserRef? = null,
)
