package app.notmumla.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import app.notmumla.audio.codec.Denoiser
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * Lightweight mic capture that only measures the input level (post noise-filter + gain) — no network
 * or playback. Powers the Settings input-level meter when NOT in a call, so gain/sensitivity can be
 * calibrated before connecting. Mirrors the engine's capture processing so the previewed level
 * matches what a call would actually send.
 */
class MicLevelMonitor {

    @Volatile var micGain: Float = 1.0f
    @Volatile var noiseSuppression: Boolean = true
    @Volatile var aiNoiseSuppression: Boolean = false
    @Volatile var noiseReductionMix: Float = 1.0f
    @Volatile var autoGain: Boolean = true

    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level

    private var captureThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        captureThread = thread(name = "mic-monitor", priority = Thread.NORM_PRIORITY) { loop() }
    }

    fun stop() {
        running = false
        captureThread?.join(300)
        captureThread = null
        _level.value = 0f
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO; capture no-ops if missing
    private fun loop() {
        val frame = AudioConstants.FRAME_SAMPLES
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frame * 2 * 4)

        val record = openRecord(minBuf)
        if (record == null) { running = false; return }
        val sessionId = record.audioSessionId
        val ns = if (noiseSuppression && NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        val agc = if (autoGain && AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        val denoiser = if (aiNoiseSuppression)
            runCatching { Denoiser().takeIf { it.frameSize == frame } }.getOrNull() else null
        val pcm = ShortArray(frame)

        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < frame && running) {
                    val n = record.read(pcm, read, frame - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < frame) continue

                denoiser?.let { it.mix = noiseReductionMix; it.process(pcm) }
                val gain = micGain
                if (gain != 1.0f) {
                    for (i in 0 until read) {
                        pcm[i] = (pcm[i] * gain).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                _level.value = rms(pcm)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { ns?.release() }
            runCatching { agc?.release() }
            runCatching { denoiser?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
            _level.value = 0f
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(minBuf: Int): AudioRecord? {
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
        )
        for (src in sources) {
            val r = runCatching {
                AudioRecord(
                    src, AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf,
                )
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) return r
            runCatching { r?.release() }
        }
        return null
    }

    private fun rms(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) { val v = s / 32768.0; sum += v * v }
        return sqrt(sum / pcm.size).toFloat()
    }
}
