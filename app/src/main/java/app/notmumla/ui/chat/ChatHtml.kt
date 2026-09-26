package app.notmumla.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import app.notmumla.data.HtmlText.decodeEntities

/**
 * Renders the HTML subset Mumble clients send (Qt rich text: b/i/u/s, links, font colors, Qt's
 * `<span style>` formatting, headings, code, lists, quotes) into an [AnnotatedString].
 *
 * Deliberately lenient and deliberately limited: unknown tags keep their text, font sizes and
 * backgrounds are ignored (a sender shouldn't control our layout), colors too close to the bubble
 * background are dropped so text never goes invisible, and links go through [ChatLinks.safeUrl].
 */
object ChatHtml {

    private val TOKEN = Regex(
        """<!--[\s\S]*?(?:-->|$)|<![^>]*>|<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:[^>"']|"[^"]*"|'[^']*')*)>""",
    )
    private val ATTR = Regex("""([a-zA-Z-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val WS = Regex("[ \\t\\r\\n\\f]+")

    private val SKIP = setOf("head", "style", "script", "title")
    private val VOID = setOf("br", "img", "hr", "meta", "link", "input", "col", "wbr")
    private val BLOCK = setOf("p", "div", "li", "ul", "ol", "tr", "table", "blockquote", "pre",
        "h1", "h2", "h3", "h4", "h5", "h6", "body", "html", "center")
    private val HEADING_SCALE = mapOf("h1" to 1.4f, "h2" to 1.25f, "h3" to 1.1f)

    /**
     * @param background the bubble color, for the contrast check on sender colors.
     * @param onLink called with the (already vetted) URL and the text the link was shown as.
     */
    fun render(
        html: String,
        linkColor: Color,
        background: Color,
        onLink: (url: String, shown: String) -> Unit,
    ): AnnotatedString = Renderer(linkColor, background, onLink).run(html)

    private class Entry(val tag: String, val pushes: Int, val block: Boolean, val link: LinkText?)

    /** A link's visible text, captured when its tag closes (the tap handler needs it later). */
    private class LinkText(val start: Int) { var text = "" }

    private class Renderer(
        linkColor: Color,
        private val background: Color,
        private val onLink: (String, String) -> Unit,
    ) {
        private val out = AnnotatedString.Builder()
        private val plain = StringBuilder() // mirror of out's text, for whitespace decisions
        private val stack = ArrayList<Entry>()
        private val lists = ArrayList<Int>() // -1 = bullet list, else the next ordered number
        private val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
        private val codeBg = Color.Gray.copy(alpha = 0.2f)
        private var skip = 0
        private var pre = 0
        private var inLink = 0

        fun run(html: String): AnnotatedString {
            var pos = 0
            for (m in TOKEN.findAll(html)) {
                if (m.range.first > pos) text(html.substring(pos, m.range.first))
                pos = m.range.last + 1
                val name = m.groupValues[2].lowercase()
                if (name.isEmpty()) continue // comment / doctype
                if (m.groupValues[1] == "/") close(name) else open(name, m.groupValues[3])
            }
            if (pos < html.length) text(html.substring(pos))
            while (stack.isNotEmpty()) popTop()

            val result = out.toAnnotatedString()
            val start = plain.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return AnnotatedString("")
            val end = plain.indexOfLast { !it.isWhitespace() } + 1
            return result.subSequence(start, end)
        }

        private fun append(s: String) {
            out.append(s)
            plain.append(s)
        }

        private fun newline() {
            if (plain.isNotEmpty() && plain.last() != '\n') append("\n")
        }

        private fun text(raw: String) {
            if (skip > 0) return
            var t = decodeEntities(if (pre > 0) raw else raw.replace(WS, " "))
            if (pre == 0 && t.startsWith(' ') && (plain.isEmpty() || plain.last() == ' ' || plain.last() == '\n')) {
                t = t.substring(1)
            }
            if (t.isEmpty()) return
            if (inLink > 0) { append(t); return }
            // Bare URLs in plain text become links too (senders that don't autolink, e.g. us pre-markdown).
            var p = 0
            for (m in ChatLinks.URL.findAll(t)) {
                val shown = ChatLinks.trimTrailing(m.value)
                val url = ChatLinks.safeUrl(shown) ?: continue
                append(t.substring(p, m.range.first))
                out.pushLink(LinkAnnotation.Clickable(url, linkStyles) { onLink(url, shown) })
                append(shown)
                out.pop()
                p = m.range.first + shown.length
            }
            append(t.substring(p))
        }

        private fun open(name: String, attrText: String) {
            if (name in SKIP) { skip++; return }
            if (skip > 0) return
            when (name) {
                "br" -> { append("\n"); return }
                "hr" -> { newline(); return }
                "img", "meta", "link", "input", "col", "wbr" -> return
            }
            // HTML lets <p>/<li> go unclosed; a new one ends the previous sibling.
            if ((name == "p" || name == "li") && stack.lastOrNull()?.tag == name) popTop()
            val attrs = parseAttrs(attrText)
            val block = name in BLOCK
            if (block) newline()
            var pushes = 0
            var link: LinkText? = null
            fun style(s: SpanStyle) { out.pushStyle(s); pushes++ }

            when (name) {
                "b", "strong" -> style(SpanStyle(fontWeight = FontWeight.Bold))
                "i", "em", "cite", "blockquote" -> style(SpanStyle(fontStyle = FontStyle.Italic))
                "u", "ins" -> style(SpanStyle(textDecoration = TextDecoration.Underline))
                "s", "strike", "del" -> style(SpanStyle(textDecoration = TextDecoration.LineThrough))
                "code", "tt", "kbd", "samp" -> style(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg))
                "pre" -> { pre++; style(SpanStyle(fontFamily = FontFamily.Monospace)) }
                "sub" -> style(SpanStyle(baselineShift = BaselineShift.Subscript, fontSize = 0.8.em))
                "sup" -> style(SpanStyle(baselineShift = BaselineShift.Superscript, fontSize = 0.8.em))
                "h1", "h2", "h3", "h4", "h5", "h6" ->
                    style(SpanStyle(fontWeight = FontWeight.Bold, fontSize = (HEADING_SCALE[name] ?: 1f).em))
                "font" -> readableColor(attrs["color"])?.let { style(SpanStyle(color = it)) }
                "ul" -> lists.add(-1)
                "ol" -> lists.add(attrs["start"]?.toIntOrNull() ?: 1)
                "li" -> {
                    val i = lists.lastIndex
                    val n = lists.getOrNull(i) ?: -1
                    append("  ".repeat(maxOf(i, 0)) + if (n < 0) "• " else "$n. ")
                    if (n >= 0) lists[i] = n + 1
                }
                "a" -> {
                    val url = attrs["href"]?.let(ChatLinks::safeUrl)
                    if (url != null && inLink == 0) {
                        val lt = LinkText(plain.length).also { link = it }
                        out.pushLink(LinkAnnotation.Clickable(url, linkStyles) { onLink(url, lt.text) })
                        pushes++
                        inLink++
                    }
                }
            }
            attrs["style"]?.let { css -> cssStyle(css)?.let(::style) }
            stack.add(Entry(name, pushes, block, link))
        }

        private fun close(name: String) {
            if (name in SKIP) { if (skip > 0) skip--; return }
            if (skip > 0 || name in VOID) return
            val idx = stack.indexOfLast { it.tag == name }
            if (idx < 0) return // stray close tag
            while (stack.size > idx) popTop()
        }

        private fun popTop() {
            val e = stack.removeAt(stack.lastIndex)
            repeat(e.pushes) { out.pop() }
            when (e.tag) {
                "pre" -> pre--
                "ul", "ol" -> lists.removeLastOrNull()
                "a" -> e.link?.let { inLink--; it.text = plain.substring(it.start).trim() }
            }
            if (e.block) newline()
        }

        private fun cssStyle(css: String): SpanStyle? {
            var s = SpanStyle()
            var any = false
            for (decl in css.split(';')) {
                val k = decl.substringBefore(':').trim().lowercase()
                val v = decl.substringAfter(':', "").trim().lowercase()
                when (k) {
                    "font-weight" -> if (v == "bold" || (v.toIntOrNull() ?: 0) >= 600) {
                        s = s.merge(SpanStyle(fontWeight = FontWeight.Bold)); any = true
                    }
                    "font-style" -> if (v == "italic" || v == "oblique") {
                        s = s.merge(SpanStyle(fontStyle = FontStyle.Italic)); any = true
                    }
                    "text-decoration", "text-decoration-line" -> {
                        val d = listOfNotNull(
                            TextDecoration.Underline.takeIf { "underline" in v },
                            TextDecoration.LineThrough.takeIf { "line-through" in v },
                        )
                        if (d.isNotEmpty()) { s = s.merge(SpanStyle(textDecoration = TextDecoration.combine(d))); any = true }
                    }
                    "color" -> readableColor(v)?.let { s = s.merge(SpanStyle(color = it)); any = true }
                }
            }
            return s.takeIf { any }
        }

        /** Parse a CSS/HTML color, or null if unparseable or too low-contrast on our bubble. */
        private fun readableColor(value: String?): Color? {
            val c = parseColor(value?.trim() ?: return null) ?: return null
            val l1 = c.luminance()
            val l2 = background.luminance()
            val ratio = (maxOf(l1, l2) + 0.05f) / (minOf(l1, l2) + 0.05f)
            return c.takeIf { ratio >= 3f }
        }
    }

    private fun parseAttrs(s: String): Map<String, String> =
        ATTR.findAll(s).associate { m ->
            m.groupValues[1].lowercase() to
                decodeEntities(m.groupValues[2].ifEmpty { m.groupValues[3].ifEmpty { m.groupValues[4] } })
        }

    // Qt's rich-text editor writes hex; these cover hand-written HTML.
    private val NAMED_COLORS = mapOf(
        "black" to "000000", "white" to "ffffff", "red" to "ff0000", "green" to "008000", "lime" to "00ff00",
        "blue" to "0000ff", "yellow" to "ffff00", "orange" to "ffa500", "purple" to "800080", "gray" to "808080",
        "grey" to "808080", "silver" to "c0c0c0", "maroon" to "800000", "navy" to "000080", "teal" to "008080",
        "olive" to "808000", "aqua" to "00ffff", "cyan" to "00ffff", "fuchsia" to "ff00ff", "magenta" to "ff00ff",
        "pink" to "ffc0cb", "brown" to "a52a2a", "gold" to "ffd700",
    )

    private fun parseColor(v: String): Color? {
        val rgb = Regex("""rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)""").find(v)
        if (rgb != null) {
            val (r, g, b) = rgb.destructured
            return Color(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
        }
        val hex = when {
            Regex("#[0-9a-fA-F]{6}").matches(v) -> v.drop(1)
            Regex("#[0-9a-fA-F]{3}").matches(v) -> v.drop(1).map { "$it$it" }.joinToString("")
            else -> NAMED_COLORS[v.lowercase()] ?: return null
        }
        return Color(0xFF000000 or hex.toLong(16))
    }
}
