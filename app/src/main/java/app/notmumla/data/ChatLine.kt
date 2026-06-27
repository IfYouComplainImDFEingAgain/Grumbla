package app.notmumla.data

/** One rendered chat entry. Server/system lines have [isSystem]; our own outgoing lines have [isMe]. */
data class ChatLine(
    val id: Long,
    val senderName: String,
    val text: String,
    val timeMillis: Long,
    val isMe: Boolean = false,
    val isSystem: Boolean = false,
)
