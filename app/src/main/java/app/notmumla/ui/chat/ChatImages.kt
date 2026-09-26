package app.notmumla.ui.chat

import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Image decoding for chat, kept off the UI thread and downsampled to the size actually shown.
 * A full-resolution decode of a camera photo on the main thread stalls the voice screen for
 * hundreds of ms (and costs tens of MB per image).
 */
internal object ChatImages {
    private const val CACHE_BYTES = 24 * 1024 * 1024

    /** Keyed by the ByteArray instance (identity equality) plus the requested size. */
    private val cache = object : LruCache<Pair<ByteArray, Int>, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: Pair<ByteArray, Int>, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Header-only parse: cheap enough for composition, and lets the layout reserve the right box. */
    fun bounds(bytes: ByteArray): Pair<Int, Int>? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        // Other clients may send photos with an EXIF rotation; decode() honors it, so match here.
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val swapped = orientation in setOf(
            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_ROTATE_270,
            ExifInterface.ORIENTATION_TRANSPOSE, ExifInterface.ORIENTATION_TRANSVERSE,
        )
        return if (swapped) o.outHeight to o.outWidth else o.outWidth to o.outHeight
    }

    fun cached(bytes: ByteArray, maxPx: Int): ImageBitmap? = cache.get(bytes to maxPx)

    /** Decode so the longer side is at least [maxPx] (power-of-two sampling, so up to ~2x that). */
    suspend fun decode(bytes: ByteArray, maxPx: Int): ImageBitmap? = withContext(Dispatchers.Default) {
        cache.get(bytes to maxPx)?.let { return@withContext it }
        val (w, h) = bounds(bytes) ?: return@withContext null
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= maxPx) sample *= 2
        // ImageDecoder applies EXIF orientation; BitmapFactory would show rotated photos sideways.
        runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { d, _, _ -> d.setTargetSampleSize(sample) }
                .asImageBitmap()
        }.getOrNull()
            ?.also { cache.put(bytes to maxPx, it) }
    }
}

/** Async, cached, downsampled bitmap; null until decoded (or if the bytes aren't an image). */
@Composable
internal fun rememberChatImage(bytes: ByteArray, maxPx: Int): State<ImageBitmap?> {
    val initial = remember(bytes, maxPx) { ChatImages.cached(bytes, maxPx) }
    return produceState(initial, bytes, maxPx) {
        if (value == null) value = ChatImages.decode(bytes, maxPx)
    }
}
