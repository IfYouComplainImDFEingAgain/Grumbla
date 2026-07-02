package app.notmumla.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

enum class MicTestState { IDLE, RECORDING, PLAYING }

/**
 * Records a few seconds of mic audio, applies the *same* VAD gating a call would (envelope +
 * threshold + pre-roll), then plays back only the gated result through the loudspeaker. Lets the
 * user hear whether their VAD settings clip the start/end of sentences. Not used while connected
 * (the engine holds the mic); call from Settings when disconnected.
 */
class MicTester {

    @Volatile var micGain: Float = 1.0f
    @Volatile var vadThreshold: Float = 0.008f

    /** Run record → gate → play, reporting state via [onState]. Suspends until playback finishes. */
    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO
    suspend fun run(onState: (MicTestState) -> Unit) = withContext(Dispatchers.IO) {
        val frame = AudioConstants.FRAME_SAMPLES
        val recorded = try {
            onState(MicTestState.RECORDING)
            record(frame)
        } catch (_: Throwable) {
            null
        }
        if (recorded.isNullOrEmpty()) { onState(MicTestState.IDLE); return@withContext }

        val gated = gate(recorded, frame)
        try {
            onState(MicTestState.PLAYING)
            play(gated, frame)
        } catch (_: Throwable) {
        } finally {
            onState(MicTestState.IDLE)
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(frame: Int): List<ShortArray> {
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frame * 2 * 4)
        val rec = openRecord(minBuf) ?: return emptyList()
        val frames = ArrayList<ShortArray>(TEST_FRAMES)
        try {
            rec.startRecording()
            repeat(TEST_FRAMES) {
                val pcm = ShortArray(frame)
                var read = 0
                while (read < frame) {
                    val n = rec.read(pcm, read, frame - read)
                    if (n <= 0) break
                    read += n
                }
                if (micGain != 1.0f) {
                    for (i in pcm.indices) {
                        pcm[i] = (pcm[i] * micGain).toInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                    }
                }
                frames.add(pcm)
            }
        } finally {
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        return frames
    }

    /** Mark which frames a VAD would transmit (envelope + threshold + pre-roll), silencing the rest. */
    private fun gate(frames: List<ShortArray>, frame: Int): List<ShortArray> {
        val tx = BooleanArray(frames.size)
        var env = 0f
        for (i in frames.indices) {
            env = maxOf(rms(frames[i]), env * VAD_ENV_DECAY)
            tx[i] = env >= vadThreshold
        }
        // Pre-roll: at each rising edge, also send the preceding frames (the onset below threshold).
        for (i in frames.indices) {
            if (tx[i] && (i == 0 || !tx[i - 1])) {
                for (j in (i - VAD_PREROLL_FRAMES).coerceAtLeast(0) until i) tx[j] = true
            }
        }
        val silence = ShortArray(frame)
        return frames.indices.map { if (tx[it]) frames[it] else silence }
    }

    private fun play(frames: List<ShortArray>, frame: Int) {
        val minBuf = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frame * 2 * 8)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(AudioConstants.SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        try {
            track.play()
            for (f in frames) track.write(f, 0, frame)
            // Let the buffered tail drain before tearing down.
            Thread.sleep(120)
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openRecord(minBuf: Int): AudioRecord? {
        for (src in intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )) {
            val r = runCatching {
                AudioRecord(src, AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf)
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

    private companion object {
        const val TEST_FRAMES = 400   // ~4 s at 10 ms/frame
        const val VAD_ENV_DECAY = 0.96f
        const val VAD_PREROLL_FRAMES = 6
    }
}
