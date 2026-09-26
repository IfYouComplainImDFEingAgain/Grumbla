package app.notmumla.data

import java.util.regex.Pattern

/**
 * Markdown → HTML for outgoing chat, ported from the desktop client's `Markdown.cpp` so what we
 * send renders the same as a desktop user's message. Mumble chat is HTML on the wire: plain text
 * must be escaped or `<` and `&` get eaten by the receiver's renderer.
 *
 * Supports `# heading`, `[text](url)`, `**bold**`, `*italic*`, `~~strike~~`, `> quote`,
 * ```` ```code block``` ````, `` `code` ``, bare http(s)/www links and `\` escapes.
 */
object ChatMarkdown {

    private const val PRE_BREAK = "%<\\!!linebreak!!//>@"

    private val HEADER = Pattern.compile("^(#+) (.*)", Pattern.MULTILINE)
    private val LINK = Pattern.compile("""\[([^\]\[]+)\]\(([^)]+)\)""")
    private val BOLD = Pattern.compile("""\*\*([^*]+)\*\*""")
    private val ITALIC = Pattern.compile("""\*([^*]+)\*""")
    private val STRIKE = Pattern.compile("~~([^~]+)~~")
    private val QUOTE = Pattern.compile("^(>|&gt;) (.|\\n(>|&gt;) )+", Pattern.MULTILINE)
    private val CODE_BLOCK = Pattern.compile("```.*\\n((?:[^`]|``?[^`]?)*)```(\\r\\n|\\n|\\r)?")
    private val INLINE_CODE = Pattern.compile("`([^`\\n]+)`")
    private val PLAIN_LINK = Pattern.compile(
        "([a-zA-Z]+://|[wW][wW][wW]\\.)([A-Za-z0-9\\-._~:/?#\\[\\]@!$&'()*+,;=]|%[a-fA-F0-9]{2})+",
    )
    private val ESCAPED = Pattern.compile("""\\(.)""")

    fun toHtml(markdown: String): String {
        val s = StringBuilder(markdown)
        var offset = 0
        while (offset < s.length) {
            // Each rule only matches exactly at [offset]; the first that matches replaces its text
            // and jumps past the replacement, so generated HTML is never re-processed.
            offset = header(s, offset) ?: link(s, offset) ?: wrap(s, offset, BOLD, "b")
                ?: wrap(s, offset, ITALIC, "i") ?: wrap(s, offset, STRIKE, "s") ?: quote(s, offset)
                ?: codeBlock(s, offset) ?: wrap(s, offset, INLINE_CODE, "code") ?: plainLink(s, offset)
                ?: escaped(s, offset) ?: escapeChar(s, offset)
        }
        return s.toString()
            .replace(Regex("(\r\n|\n|\r)(\r\n|\n|\r)"), "<br/>")
            .replace(Regex("\r\n|\n|\r"), "")
            .replace(PRE_BREAK, "\n")
    }

    /** Qt's `QString::toHtmlEscaped`. */
    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun matchAt(p: Pattern, s: CharSequence, offset: Int) =
        p.matcher(s).apply {
            region(offset, s.length)
            useAnchoringBounds(false) // '^' must mean a real line start, not the region start
            useTransparentBounds(true)
        }.takeIf { it.lookingAt() }

    /** Replace the match with [replacement]; returns the offset just past it. */
    private fun replace(s: StringBuilder, m: java.util.regex.Matcher, replacement: String): Int {
        s.replace(m.start(), m.end(), replacement)
        return m.start() + replacement.length
    }

    private fun header(s: StringBuilder, offset: Int): Int? {
        val m = matchAt(HEADER, s, offset) ?: return null
        val level = m.group(1)!!.length
        return replace(s, m, "<h$level>${escape(m.group(2)!!.trim())}</h$level>")
    }

    private fun link(s: StringBuilder, offset: Int): Int? {
        val m = matchAt(LINK, s, offset) ?: return null
        var url = m.group(2)!!
        if (!url.startsWith("http", ignoreCase = true)) url = "http://$url"
        // Unlike the desktop client, escape the href: a '"' in the URL would otherwise end the
        // attribute and let the rest of it inject markup.
        return replace(s, m, "<a href=\"${escape(url)}\">${escape(m.group(1)!!)}</a>")
    }

    private fun wrap(s: StringBuilder, offset: Int, p: Pattern, tag: String): Int? {
        val m = matchAt(p, s, offset) ?: return null
        return replace(s, m, "<$tag>${escape(m.group(1)!!)}</$tag>")
    }

    private fun quote(s: StringBuilder, offset: Int): Int? {
        val m = matchAt(QUOTE, s, offset) ?: return null
        val body = m.group(0)!!.replace("&gt;", ">").split('\n').joinToString("\n") { it.drop(2) }
        return replace(s, m, "<div><i>${escape(body).replace("\n", "<br/>")}</i></div>")
    }

    private fun codeBlock(s: StringBuilder, offset: Int): Int? {
        val m = matchAt(CODE_BLOCK, s, offset) ?: return null
        val code = escape(m.group(1)!!).trimStart('\n', '\r').trimEnd()
        if (code.isEmpty()) return null
        // <pre> keeps its line breaks; hide them from the <br/> pass below.
        return replace(s, m, "<pre>${code.replace("\n", PRE_BREAK)}</pre>")
    }

    private fun plainLink(s: StringBuilder, offset: Int): Int? {
        val m = matchAt(PLAIN_LINK, s, offset) ?: return null
        val shown = m.group(0)!!
        val url = if (shown.startsWith("www", ignoreCase = true)) "https://$shown" else shown
        return replace(s, m, "<a href=\"${escape(url)}\">${escape(shown)}</a>")
    }

    private fun escaped(s: StringBuilder, offset: Int): Int? {
        val m = matchAt(ESCAPED, s, offset) ?: return null
        return replace(s, m, escape(m.group(1)!!))
    }

    private fun escapeChar(s: StringBuilder, offset: Int): Int {
        val e = escape(s[offset].toString())
        s.replace(offset, offset + 1, e)
        return offset + e.length
    }
}
