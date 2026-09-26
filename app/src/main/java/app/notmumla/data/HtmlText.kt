package app.notmumla.data

/** Plain-text helpers for the HTML that Mumble chat messages carry. */
object HtmlText {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to "\u00A0",
        "copy" to "©", "reg" to "®", "trade" to "™", "hellip" to "…", "mdash" to "—", "ndash" to "–",
        "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "bull" to "•", "middot" to "·", "deg" to "°", "euro" to "€", "pound" to "£", "times" to "×",
    )
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]+);")

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        return ENTITY.replace(s) { m ->
            val e = m.groupValues[1]
            val code = when {
                e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)
                e.startsWith("#") -> e.drop(1).toIntOrNull()
                else -> return@replace NAMED[e] ?: m.value
            }
            code?.takeIf { Character.isValidCodePoint(it) && it != 0 }
                ?.let { String(Character.toChars(it)) } ?: m.value
        }
    }

    /** Flatten message HTML to plain text (notifications, read-aloud, copy, mention matching). */
    fun strip(html: String): String =
        html.replace(Regex("<(head|style|script|title)\\b[\\s\\S]*?</\\1\\s*>|<!--[\\s\\S]*?-->", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ") // HTML whitespace is insignificant; line breaks come from tags
            .replace(Regex("<br\\s*/?>|</(p|div|li|h[1-6]|tr|pre)\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]*>"), "")
            .let(::decodeEntities)
            .replace('\u00A0', ' ')
            .lines().joinToString("\n") { it.trim() }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
