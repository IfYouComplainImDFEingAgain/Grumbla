package app.notmumla.ui.chat

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichDraftTest {

    /** Simulate typing [s] at the cursor, one character at a time. */
    private fun RichDraft.type(s: String): RichDraft = s.fold(this) { d, ch ->
        val at = d.value.selection.min
        val t = d.text.substring(0, at) + ch + d.text.substring(d.value.selection.max)
        d.edit(TextFieldValue(t, TextRange(at + 1)))
    }
    private fun RichDraft.select(a: Int, b: Int) = edit(value.copy(selection = TextRange(a, b)))
    private fun RichDraft.cursor(at: Int) = select(at, at)

    @Test fun plainTextIsEscaped() =
        assertEquals("a &lt;b&gt; &amp; c", RichDraft().type("a <b> & c").toHtml())

    @Test fun toggleBeforeTypingThenOff() {
        val d = RichDraft().type("x ").toggle(Format.BOLD).type("bold").toggle(Format.BOLD).type(" y")
        assertEquals("x <b>bold</b> y", d.toHtml())
    }

    @Test fun formatSelection() {
        val d = RichDraft().type("hello world").select(6, 11).toggle(Format.ITALIC)
        assertTrue(d.isOn(Format.ITALIC))
        assertEquals("hello <i>world</i>", d.toHtml())
        // Toggling again with the whole selection on turns it off.
        assertEquals("hello world", d.toggle(Format.ITALIC).toHtml())
    }

    @Test fun typingContinuesStyleOfPreviousChar() {
        val d = RichDraft().toggle(Format.BOLD).type("ab").cursor(1).type("X")
        assertEquals("<b>aXb</b>", d.toHtml())
    }

    @Test fun repeatedLetterInsertsAtCursor() {
        // "aa" with the first 'a' bold; a struck 'a' typed between them lands there (and inherits bold).
        var d = RichDraft().toggle(Format.BOLD).type("a").toggle(Format.BOLD).type("a")
        d = d.cursor(1).toggle(Format.STRIKE).type("a")
        assertEquals("<b>a</b><b><s>a</s></b>a", d.toHtml())
    }

    @Test fun deleteCarriesStyles() {
        var d = RichDraft().type("ab").select(1, 2).toggle(Format.CODE).cursor(2).type("c")
        d = d.edit(TextFieldValue("bc", TextRange(0))) // delete leading 'a'
        assertEquals("<code>bc</code>", d.toHtml())
    }

    @Test fun cursorMoveDropsPending() {
        val d = RichDraft().type("ab").toggle(Format.BOLD).cursor(0)
        assertFalse(d.isOn(Format.BOLD))
    }

    @Test fun colorAndLinks() {
        val d = RichDraft().type("see www.example.com").select(0, 3).color(0x1E88E5)
        assertEquals("<span style=\"color:#1e88e5\">see</span> <a href=\"https://www.example.com\">www.example.com</a>",
            d.toHtml())
    }

    @Test fun trimmed() {
        val d = RichDraft().type("  ").toggle(Format.BOLD).type("hi").toggle(Format.BOLD).type("  ").trimmed()
        assertEquals("hi", d.text)
        assertEquals("<b>hi</b>", d.toHtml())
    }
}
