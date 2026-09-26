package app.notmumla.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import app.notmumla.data.ChatMarkdown

/** Formatting of one character in the WYSIWYG composer. [color] is 0xRRGGBB or null for default. */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val color: Int? = null,
) {
    companion object { val PLAIN = CharStyle() }
}

enum class Format { BOLD, ITALIC, UNDERLINE, STRIKE, CODE }

/**
 * Immutable state of the WYSIWYG composer: the text field value plus one [CharStyle] per character.
 * A per-character model (rather than span ranges) keeps edits trivially correct — an edit only
 * splices the style list the same way it splices the text — and chat messages are short.
 */
data class RichDraft(
    val value: TextFieldValue = TextFieldValue(""),
    val styles: List<CharStyle> = emptyList(),
    /** Style chosen via the toolbar with no selection; applies to the next typed text. */
    val pending: CharStyle? = null,
) {
    val text: String get() = value.text

    /** Style new text gets: the toolbar's pending choice, else the character before the cursor. */
    val typingStyle: CharStyle
        get() = pending ?: styles.getOrNull(value.selection.min - 1)
            ?: styles.firstOrNull()?.takeIf { value.selection.min == 0 } ?: CharStyle.PLAIN

    /** Apply a text-field edit, carrying styles along with the characters. */
    fun edit(new: TextFieldValue): RichDraft {
        val old = value.text
        val next = new.text
        if (old == next) {
            // Cursor/selection move only; a pending style belongs to the old cursor spot.
            return copy(value = new, pending = if (new.selection == value.selection) pending else null)
        }
        val (p, removedEnd, insertedEnd) = diff(old, next, new.selection)
        val inserted = List(insertedEnd - p) { typingStyle }
        val newStyles = styles.subList(0, p) + inserted + styles.subList(removedEnd, styles.size)
        return RichDraft(new, newStyles, pending = if (inserted.isEmpty()) pending else null)
    }

    /** Toggle [f] on the selection, or on what's typed next if nothing is selected. */
    fun toggle(f: Format): RichDraft {
        val sel = value.selection
        if (sel.collapsed) return copy(pending = typingStyle.with(f, !typingStyle.has(f)))
        val range = sel.min until sel.max
        val on = !range.all { styles[it].has(f) }
        return copy(styles = styles.mapIndexed { i, s -> if (i in range) s.with(f, on) else s })
    }

    /** Set text color on the selection (or for what's typed next); null = default color. */
    fun color(rgb: Int?): RichDraft {
        val sel = value.selection
        if (sel.collapsed) return copy(pending = typingStyle.copy(color = rgb))
        val range = sel.min until sel.max
        return copy(styles = styles.mapIndexed { i, s -> if (i in range) s.copy(color = rgb) else s })
    }

    /** Whether [f] is on for the selection (all of it) or for the next typed text. */
    fun isOn(f: Format): Boolean {
        val sel = value.selection
        return if (sel.collapsed) typingStyle.has(f) else (sel.min until sel.max).all { styles[it].has(f) }
    }

    /** The draft without leading/trailing whitespace (styles trimmed to match). */
    fun trimmed(): RichDraft {
        val start = text.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return RichDraft()
        val end = text.indexOfLast { !it.isWhitespace() } + 1
        return RichDraft(TextFieldValue(text.substring(start, end)), styles.subList(start, end))
    }

    /** Mumble chat HTML for the draft. Bare URLs become links, as the desktop client does. */
    fun toHtml(): String {
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val s = styles[i]
            var j = i
            while (j < text.length && styles[j] == s) j++
            out.append(wrap(s, linkify(text.substring(i, j), s.code)))
            i = j
        }
        return out.toString()
    }

    companion object {
        /**
         * Where [old] became [new]: returns (start, end of removed text in old, end of inserted in new).
         * Anchored on the new cursor so typing a repeated letter ("aa" → "aaa") lands where the
         * cursor is, not at the first position a plain prefix diff would guess.
         */
        internal fun diff(old: String, new: String, sel: TextRange): Triple<Int, Int, Int> {
            val c = sel.end
            val suffix = new.length - c
            if (sel.collapsed && suffix in 0..old.length && old.endsWith(new.substring(c))) {
                val limit = minOf(c, old.length - suffix)
                var p = 0
                while (p < limit && old[p] == new[p]) p++
                return Triple(p, old.length - suffix, c)
            }
            var p = 0
            val max = minOf(old.length, new.length)
            while (p < max && old[p] == new[p]) p++
            var s = 0
            while (s < max - p && old[old.length - 1 - s] == new[new.length - 1 - s]) s++
            return Triple(p, old.length - s, new.length - s)
        }

        private fun wrap(s: CharStyle, inner: String): String {
            var h = inner
            if (s.code) h = "<code>$h</code>"
            if (s.strike) h = "<s>$h</s>"
            if (s.underline) h = "<u>$h</u>"
            if (s.italic) h = "<i>$h</i>"
            if (s.bold) h = "<b>$h</b>"
            s.color?.let { h = "<span style=\"color:#%06x\">$h</span>".format(it and 0xFFFFFF) }
            return h
        }

        private fun linkify(t: String, code: Boolean): String {
            if (code) return ChatMarkdown.escape(t)
            val out = StringBuilder()
            var p = 0
            for (m in ChatLinks.URL.findAll(t)) {
                val shown = ChatLinks.trimTrailing(m.value)
                val url = ChatLinks.safeUrl(shown) ?: continue
                out.append(ChatMarkdown.escape(t.substring(p, m.range.first)))
                out.append("<a href=\"${ChatMarkdown.escape(url)}\">${ChatMarkdown.escape(shown)}</a>")
                p = m.range.first + shown.length
            }
            return out.append(ChatMarkdown.escape(t.substring(p))).toString()
        }
    }
}

fun CharStyle.has(f: Format) = when (f) {
    Format.BOLD -> bold
    Format.ITALIC -> italic
    Format.UNDERLINE -> underline
    Format.STRIKE -> strike
    Format.CODE -> code
}

fun CharStyle.with(f: Format, on: Boolean) = when (f) {
    Format.BOLD -> copy(bold = on)
    Format.ITALIC -> copy(italic = on)
    Format.UNDERLINE -> copy(underline = on)
    Format.STRIKE -> copy(strike = on)
    Format.CODE -> copy(code = on)
}

/** Draws the draft's per-character styles in the text field (the text itself is unchanged). */
class RichDraftTransformation(private val styles: List<CharStyle>) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val b = AnnotatedString.Builder(text.text)
        var i = 0
        // styles can briefly lag the text by a frame; never style past either end.
        val n = minOf(text.length, styles.size)
        while (i < n) {
            val s = styles[i]
            var j = i
            while (j < n && styles[j] == s) j++
            if (s != CharStyle.PLAIN) b.addStyle(s.toSpan(), i, j)
            i = j
        }
        return TransformedText(b.toAnnotatedString(), OffsetMapping.Identity)
    }

    override fun equals(other: Any?) = other is RichDraftTransformation && other.styles == styles
    override fun hashCode() = styles.hashCode()
}

private fun CharStyle.toSpan() = SpanStyle(
    fontWeight = if (bold) FontWeight.Bold else null,
    fontStyle = if (italic) FontStyle.Italic else null,
    textDecoration = TextDecoration.combine(listOfNotNull(
        TextDecoration.Underline.takeIf { underline }, TextDecoration.LineThrough.takeIf { strike },
    )).takeIf { underline || strike },
    fontFamily = if (code) FontFamily.Monospace else null,
    background = if (code) Color.Gray.copy(alpha = 0.2f) else Color.Unspecified,
    color = color?.let { Color(0xFF000000 or it.toLong()) } ?: Color.Unspecified,
)
