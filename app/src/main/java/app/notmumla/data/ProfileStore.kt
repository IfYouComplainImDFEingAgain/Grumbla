package app.notmumla.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's Mumble profile: the comment and avatar ("texture") sent in our own UserState.
 * Murmur only keeps them for registered users, so they live here and are re-sent on each connect.
 */
@Singleton
class ProfileStore @Inject constructor(@ApplicationContext context: Context) {
    private val dir = File(context.filesDir, "profile").apply { mkdirs() }
    private val commentFile = File(dir, "comment.txt")
    private val avatarFile = File(dir, "avatar.jpg")

    private val _comment = MutableStateFlow(runCatching { commentFile.readText() }.getOrDefault(""))
    /** Plain text as typed; [commentHtml] is what goes on the wire. */
    val comment: StateFlow<String> = _comment

    private val _avatar = MutableStateFlow(runCatching { avatarFile.readBytes() }.getOrNull()?.takeIf { it.isNotEmpty() })
    /** JPEG bytes, or null for no avatar. */
    val avatar: StateFlow<ByteArray?> = _avatar

    suspend fun setComment(text: String) = withContext(Dispatchers.IO) {
        val t = text.trim()
        if (t.isEmpty()) commentFile.delete() else commentFile.writeText(t)
        _comment.value = t
    }

    suspend fun setAvatar(jpeg: ByteArray?) = withContext(Dispatchers.IO) {
        if (jpeg == null || jpeg.isEmpty()) avatarFile.delete() else avatarFile.writeBytes(jpeg)
        _avatar.value = jpeg?.takeIf { it.isNotEmpty() }
    }

    companion object {
        /** The comment as Mumble HTML: escaped, line breaks kept. */
        fun commentHtml(text: String): String =
            text.trim().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\r\n", "\n").replace("\n", "<br/>")
    }
}
