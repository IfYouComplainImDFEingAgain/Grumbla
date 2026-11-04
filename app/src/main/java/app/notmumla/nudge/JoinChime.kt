package app.notmumla.nudge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The join alert (NEXT 2.1): a soft two-note rising chime, synthesized like the nudge "boing" so there is
 * no asset to ship. Follows the ringer switch (sound only in normal mode) and stays quiet when deafened.
 */
class JoinChime(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var track: AudioTrack? = null

    @Synchronized
    fun play(deafened: Boolean) {
        if (deafened || audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val t = track ?: buildTrack().also { track = it }
        runCatching {
            // A static track replays from the start after stop + reload.
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun buildTrack(): AudioTrack {
        val pcm = chime()
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }

    companion object {
        private const val RATE = 44_100

        /** Two bell-ish notes a fifth apart (E5 then B5), each with a fast attack and an exponential tail. */
        internal fun chime(): ShortArray {
            val notes = listOf(0.0 to 659.25, 0.11 to 987.77)
            val n = (RATE * 0.5).toInt()
            val out = ShortArray(n)
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                var s = 0.0
                for ((start, freq) in notes) {
                    val u = t - start
                    if (u < 0) continue
                    val env = min(1.0, u / 0.004) * exp(-9 * u)
                    s += env * (0.85 * sin(2 * PI * freq * u) + 0.15 * sin(4 * PI * freq * u))
                }
                out[i] = (s * 0.35 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
            return out
        }
    }
}
