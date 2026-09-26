package app.notmumla.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMarkdownTest {

    private fun md(s: String) = ChatMarkdown.toHtml(s)

    @Test fun plainTextIsEscaped() =
        assertEquals("a &lt; b &amp;&amp; c &gt; d &quot;q&quot;", md("a < b && c > d \"q\""))

    @Test fun inlineFormatting() {
        assertEquals("<b>bold</b> <i>it</i> <s>gone</s> <code>x &lt; y</code>",
            md("**bold** *it* ~~gone~~ `x < y`"))
    }

    @Test fun links() {
        assertEquals("<a href=\"https://www.example.com\">www.example.com</a>", md("www.example.com"))
        assertEquals("see <a href=\"https://example.com/a?b=1&amp;c=2\">https://example.com/a?b=1&amp;c=2</a>",
            md("see https://example.com/a?b=1&c=2"))
        assertEquals("<a href=\"http://example.com\">site</a>", md("[site](example.com)"))
    }

    @Test fun linkHrefCannotBreakOutOfAttribute() =
        assertEquals("<a href=\"http://x&quot;onclick=&quot;y\">t</a>", md("[t](x\"onclick=\"y)"))

    @Test fun headingAndQuote() {
        assertEquals("<h2>Title</h2>", md("## Title"))
        assertEquals("<div><i>quoted</i></div>", md("> quoted"))
    }

    @Test fun backslashEscapesMarkdown() = assertEquals("*not italic*", md("\\*not italic\\*"))

    @Test fun lineBreaks() {
        assertEquals("a<br/>b", md("a\n\nb"))
        assertEquals("ab", md("a\nb")) // matches desktop: single newlines are dropped
    }

    @Test fun codeBlockKeepsLines() = assertEquals("<pre>x\ny</pre>", md("```\nx\ny\n```"))
}

class HtmlTextTest {
    @Test fun stripsQtDocument() {
        val qt = "<!DOCTYPE HTML PUBLIC \"-//W3C//DTD HTML 4.0//EN\"><html><head><meta name=\"qrichtext\" content=\"1\" />" +
            "<style type=\"text/css\">p, li { white-space: pre-wrap; }</style></head><body style=\" font-size:9pt;\">" +
            "<p style=\" margin-top:0px;\">Hello <span style=\" font-weight:600;\">world</span></p><p>line 2</p></body></html>"
        assertEquals("Hello world\nline 2", HtmlText.strip(qt))
    }

    @Test fun entities() = assertEquals("a < b & c ' é", HtmlText.strip("a &lt; b &amp; c &#39; &#xe9;"))
}
