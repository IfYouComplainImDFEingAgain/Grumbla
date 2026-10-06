package app.notmumla.nudge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The receiving end of a nudge: a short buzz and a cartoon "boing". The sound is synthesized, so
 * there is no audio asset to ship or license. Both follow the ringer switch: silent = neither,
 * vibrate = buzz only.
 */
class NudgeEffects(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private var track: AudioTrack? = null

    /** [deafened]: the user chose not to hear the call, so don't make noise either. */
    @Synchronized
    fun play(deafened: Boolean) {
        val ringer = audioManager.ringerMode
        if (ringer == AudioManager.RINGER_MODE_SILENT) return
        // Notification usage, not the default (touch): with touch haptics turned off, a touch
        // vibration is dropped, and a nudge isn't a touch.
        val buzz = VibrationEffect.createOneShot(VIBRATE_MS, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(buzz, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_NOTIFICATION))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(buzz, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build())
        }
        if (ringer != AudioManager.RINGER_MODE_NORMAL || deafened) return
        val t = track ?: buildTrack().also { track = it }
        runCatching {
            // A static track replays from the start after stop + reload.
            t.stop()
            t.reloadStaticData()
            t.play()
        }
    }

    private fun buildTrack(): AudioTrack {
        val pcm = boing()
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
        private const val VIBRATE_MS = 40L

        /**
         * A spring "boing": a tone whose pitch wobbles fast and settles, under a decaying envelope.
         * The phase is integrated so the wobbling frequency stays click-free.
         */
        internal fun boing(seconds: Double = 0.45): ShortArray {
            val n = (RATE * seconds).toInt()
            val out = ShortArray(n)
            var phase = 0.0
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                val freq = 330.0 * (1 + 0.35 * exp(-5 * t) * sin(2 * PI * 14 * t))
                phase += 2 * PI * freq / RATE
                val attack = min(1.0, t / 0.005)
                val env = attack * exp(-5 * t)
                // A touch of the second harmonic makes it read as a spring rather than a test tone.
                val s = 0.8 * sin(phase) + 0.2 * sin(2 * phase)
                out[i] = (s * env * 0.6 * Short.MAX_VALUE).toInt().toShort()
            }
            return out
        }
    }
}
