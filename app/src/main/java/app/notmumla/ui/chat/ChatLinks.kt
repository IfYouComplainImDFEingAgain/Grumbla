package app.notmumla.ui.chat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/** Finds web links in chat text and makes them tappable. */
object ChatLinks {

    // Only explicit http(s):// or www. links. Bare domains ("config.json", "v1.2") misfire too often.
    private val URL = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"]+""")
    private const val TRAILING = ".,;:!?'\"]}>"

    /** [text] with each URL as a link that opens in the browser; plain [AnnotatedString] if none. */
    fun linkify(context: Context, text: String, linkColor: Color): AnnotatedString {
        val matches = URL.findAll(text).toList()
        if (matches.isEmpty()) return AnnotatedString(text)
        val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        return buildAnnotatedString {
            var pos = 0
            for (m in matches) {
                val url = trimTrailing(m.value)
                append(text, pos, m.range.first)
                withLink(LinkAnnotation.Clickable(url, style) { open(context, url) }) { append(url) }
                pos = m.range.first + url.length
            }
            append(text, pos, text.length)
        }
    }

    /** Drop sentence punctuation after a URL; keep ')' only when it closes a '(' inside the URL. */
    private fun trimTrailing(raw: String): String {
        var url = raw
        while (url.isNotEmpty()) {
            val last = url.last()
            val unbalancedParen = last == ')' && url.count { it == '(' } < url.count { it == ')' }
            if (last in TRAILING || unbalancedParen) url = url.dropLast(1) else break
        }
        return url
    }

    private fun open(context: Context, url: String) {
        val target = if (url.startsWith("www.", ignoreCase = true)) "https://$url" else url
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // No browser installed shouldn't crash the call.
        try { context.startActivity(intent) } catch (_: ActivityNotFoundException) {}
    }
}
