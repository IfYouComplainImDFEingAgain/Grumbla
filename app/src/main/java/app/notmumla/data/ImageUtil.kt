package app.notmumla.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/** Image handling for chat: load+compress for sending, and parse inline `<img>` data URIs. */
object ImageUtil {

    private const val MAX_DIMENSION = 1280
    /** Default cap if the server didn't advertise an image-message limit (Mumble default 128 KB). */
    private const val DEFAULT_LIMIT = 131_072

    /**
     * Load [uri], downscale and JPEG-compress it so the resulting **base64** fits under
     * [serverLimit] (with headroom for the `<img>` tag). Returns the compressed JPEG bytes, or null.
     */
    fun loadCompressed(context: Context, uri: Uri, serverLimit: Int): ByteArray? {
        val limit = (if (serverLimit > 0) serverLimit else DEFAULT_LIMIT)
        // base64 inflates ~4/3 and the tag adds ~40 chars; leave margin.
        val maxRaw = ((limit - 64) * 3 / 4) - 64
        if (maxRaw <= 0) return null

        val source = runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull() ?: return null

        var bmp = scaleDown(source, MAX_DIMENSION)
        for (quality in intArrayOf(85, 70, 55, 40, 25, 15)) {
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
            if (out.size() <= maxRaw) return out.toByteArray()
        }
        // Still too big: shrink dimensions and try once more.
        bmp = scaleDown(bmp, 720)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 35, out)
        return if (out.size() <= maxRaw) out.toByteArray() else null
    }

    /** Wrap JPEG [bytes] in the Mumble inline-image HTML (`<img src="data:image/jpeg;base64,…">`). */
    fun toImageHtml(bytes: ByteArray): String {
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "<img src=\"data:image/jpeg;base64,$b64\" />"
    }

    // The data-URI value may be percent-encoded (%2F…) and/or wrapped across multiple lines, so the
    // capture must allow base64 chars, '%', and whitespace (stripped/decoded below).
    private val IMG_DATA = Regex(
        "data:image/[^;]+;base64,([A-Za-z0-9+/=%\\s]+)", RegexOption.IGNORE_CASE,
    )
    private val IMG_TAG = Regex("<img[^>]*>", RegexOption.IGNORE_CASE)

    /** Extract the first inline image's decoded bytes from [html], if any. */
    fun extractImage(html: String): ByteArray? {
        val raw = IMG_DATA.find(html)?.groupValues?.get(1) ?: return null
        // Some clients (e.g. the Mumble desktop client) percent-encode the base64 inside the data
        // URI (`/` -> %2F, `+` -> %2B, …) and wrap it across lines. Strip whitespace first (joining
        // wrapped lines), then decode %XX (leaving other chars, including '+', intact).
        val b64 = percentDecode(raw.replace(Regex("\\s"), ""))
        return runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
    }

    private fun percentDecode(s: String): String {
        if ('%' !in s) return s
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%' && i + 2 < s.length) {
                val code = s.substring(i + 1, i + 3).toIntOrNull(16)
                if (code != null) { out.append(code.toChar()); i += 3; continue }
            }
            out.append(ch); i++
        }
        return out.toString()
    }

    /** Remove `<img>` tags so the remaining text can be shown alongside the image. */
    fun stripImageTags(html: String): String = html.replace(IMG_TAG, "")

    private fun scaleDown(bmp: Bitmap, maxDim: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxDim && h <= maxDim) return bmp
        val scale = maxDim.toFloat() / maxOf(w, h)
        return Bitmap.createScaledBitmap(bmp, (w * scale).toInt().coerceAtLeast(1),
            (h * scale).toInt().coerceAtLeast(1), true)
    }
}
