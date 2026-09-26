package app.notmumla.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHtmlTest {

    private val dark = Color(0xFF1D2024)
    private fun render(html: String) = ChatHtml.render(html, Color.Blue, dark) { _, _ -> }
    private fun AnnotatedString.links() =
        getLinkAnnotations(0, length).map { text.substring(it.start, it.end) to (it.item as LinkAnnotation.Clickable).tag }

    @Test fun qtDocument() {
        val qt = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0//EN\"><html><head><meta name=\"qrichtext\" content=\"1\" />" +
            "<style type=\"text/css\">p, li { white-space: pre-wrap; }</style></head><body style=\" font-size:9pt;\">" +
            "<p style=\" margin-top:0px;\">Hello <span style=\" font-weight:600;\">world</span></p><p>line 2</p></body></html>"
        val r = render(qt)
        assertEquals("Hello world\nline 2", r.text)
        val bold = r.spanStyles.single { it.item.fontWeight == FontWeight.Bold }
        assertEquals("world", r.text.substring(bold.start, bold.end))
    }

    @Test fun whitespaceAndEntities() =
        assertEquals("a < b & c", render("  a   &lt;\n b &amp;   c ").text)

    @Test fun anchorsAndBareUrls() {
        val r = render("<a href=\"https://example.com/x\">click</a> and https://example.org/y.")
        assertEquals("click and https://example.org/y.", r.text)
        assertEquals(listOf("click" to "https://example.com/x", "https://example.org/y" to "https://example.org/y"), r.links())
    }

    @Test fun unsafeSchemesAreNotLinked() {
        val r = render("<a href=\"intent://evil#Intent;end\">x</a> <a href=\"javascript:alert(1)\">y</a>")
        assertEquals("x y", r.text)
        assertTrue(r.links().isEmpty())
    }

    @Test fun lowContrastColorDropped() {
        val r = render("<font color=\"#000000\">black</font> <span style=\"color:#ff8800\">orange</span>")
        assertNull(r.spanStyles.firstOrNull { r.text.substring(it.start, it.end) == "black" })
        assertEquals(Color(0xFFFF8800), r.spanStyles.single { r.text.substring(it.start, it.end) == "orange" }.item.color)
    }

    @Test fun listsAndBreaks() =
        assertEquals("• one\n• two\nend\nx", render("<ul><li>one<li>two</ul>end<br>x").text)

    @Test fun unclosedAndStrayTags() =
        assertEquals("bold still", render("<b>bold</i> still").text)

    @Test fun linkConfirmation() {
        assertTrue(ChatLinks.opensWhatItShows("https://example.com/a", "www.example.com/a"))
        assertTrue(!ChatLinks.opensWhatItShows("https://evil.example", "https://bank.example"))
        assertTrue(!ChatLinks.opensWhatItShows("https://example.com", "click here"))
    }
}
