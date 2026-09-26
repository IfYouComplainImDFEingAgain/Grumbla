package app.notmumla.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/** URL detection, vetting and opening for chat links. */
object ChatLinks {

    // Only explicit http(s):// or www. links. Bare domains ("config.json", "v1.2") misfire too often.
    internal val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
    private const val TRAILING = ".,;:!?'\"]}>"
    private val SAFE_SCHEMES = setOf("http", "https", "mailto")
    private val SCHEME = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /** Drop sentence punctuation after a URL; keep ')' only when it closes a '(' inside the URL. */
    internal fun trimTrailing(raw: String): String {
        var url = raw
        while (url.isNotEmpty()) {
            val last = url.last()
            val unbalancedParen = last == ')' && url.count { it == '(' } < url.count { it == ')' }
            if (last in TRAILING || unbalancedParen) url = url.dropLast(1) else break
        }
        return url
    }

    /**
     * The URL to open for [href], or null if it isn't one we'll follow. Sender-supplied hrefs can
     * name any scheme (intent:, file:, content:, app deep links); only web and mail links are opened.
     */
    fun safeUrl(href: String): String? {
        val h = href.trim()
        val url = if (h.startsWith("www.", ignoreCase = true)) "https://$h" else h
        val scheme = SCHEME.find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        return url.takeIf { scheme in SAFE_SCHEMES }
    }

    /**
     * Whether a link shown as [shown] may open [url] without asking. Rich text lets the visible
     * text differ from the target ("click here", or a fake URL); then the user should see the real one.
     */
    fun opensWhatItShows(url: String, shown: String): Boolean {
        val text = safeUrl(shown) ?: return false
        fun host(u: String) = runCatching { java.net.URI(u).host }.getOrNull()?.lowercase()?.removePrefix("www.")
        return host(text) != null && host(text) == host(url)
    }

    fun open(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // No browser installed shouldn't crash the call.
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
    }
}
