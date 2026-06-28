package app.notmumla.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import app.notmumla.audio.codec.OpusEncoder
import app.notmumla.audio.playback.SpeakerMixer
import app.notmumla.audio.routing.RouteConfig
import app.notmumla.protocol.VoiceSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** How the mic decides when to transmit. */
enum class TransmissionMode { PTT, VAD }

/**
 * Real-time voice engine: captures mic audio, Opus-encodes and ships it via [sendFrame], and mixes
 * inbound speakers to the speaker/headset. Implements [VoiceSink] for inbound frames.
 *
 * Routing (phone vs Bluetooth) is layered on top in M4 via the AudioRouter; this engine just owns
 * the AudioRecord/AudioTrack and the codec loops.
 */
class AudioEngine(
    private val audioManager: AudioManager,
    private val sendFrame: (opus: ByteArray, terminator: Boolean) -> Unit,
) : VoiceSink {

    @Volatile var mode: TransmissionMode = TransmissionMode.PTT
    @Volatile var muted: Boolean = false
    @Volatile var micGain: Float = 1.0f
    /** Normalized VAD threshold (RMS over full-scale). */
    @Volatile var vadThreshold: Float = 0.008f
    @Volatile var noiseSuppression: Boolean = true
    @Volatile var echoCancellation: Boolean = true
    @Volatile var bitrate: Int = 72_000
    @Volatile private var pttHeld: Boolean = false
    @Volatile private var encoderRef: OpusEncoder? = null

    /**
     * Apply audio-processing settings. Gain/VAD/bitrate take effect live; toggling the hardware
     * effects (NS/AEC) requires recreating the capture, so we restart if those changed while running.
     */
    fun applyAudioSettings(
        micGain: Float, vadThreshold: Float, bitrate: Int,
        noiseSuppression: Boolean, echoCancellation: Boolean,
    ) {
        this.micGain = micGain
        this.vadThreshold = vadThreshold
        val effectsChanged = this.noiseSuppression != noiseSuppression ||
            this.echoCancellation != echoCancellation
        this.noiseSuppression = noiseSuppression
        this.echoCancellation = echoCancellation
        if (this.bitrate != bitrate) {
            this.bitrate = bitrate
            encoderRef?.setBitrate(bitrate)
        }
        if (effectsChanged && running) { stop(); start() }
    }

    /** Current capture/playback route. Changing it restarts the audio threads. */
    @Volatile var routeConfig: RouteConfig = RouteConfig.PHONE
        private set

    private fun deviceById(id: Int?): AudioDeviceInfo? {
        if (id == null) return null
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.id == id }
    }

    private val _transmitting = MutableStateFlow(false)
    val transmitting: StateFlow<Boolean> = _transmitting

    private val _speaking = MutableStateFlow<Set<Int>>(emptySet())
    /** Remote sessions currently producing audio (drives speaking indicators). */
    val speakingSessions: StateFlow<Set<Int>> = _speaking

    private val _inputLevel = MutableStateFlow(0f)
    /** Live mic input level (normalized RMS, 0..1) — drives the VAD calibration meter. */
    val inputLevel: StateFlow<Float> = _inputLevel

    private val mixer = SpeakerMixer()
    private var captureThread: Thread? = null
    private var playbackThread: Thread? = null
    @Volatile private var running = false

    fun start() {
        if (running) return
        running = true
        captureThread = thread(name = "mumble-capture", priority = Thread.MAX_PRIORITY) { captureLoop() }
        playbackThread = thread(name = "mumble-playback", priority = Thread.MAX_PRIORITY) { playbackLoop() }
    }

    fun stop() {
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        captureThread = null
        playbackThread = null
        mixer.release()
        _speaking.value = emptySet()
        _transmitting.value = false
        _inputLevel.value = 0f
    }

    /** PTT button down/up. */
    fun setPttHeld(held: Boolean) { pttHeld = held }

    /** Apply a new capture/playback route, restarting the audio threads if running. */
    fun applyRoute(config: RouteConfig) {
        routeConfig = config
        if (running) {
            stop()
            start()
        }
    }

    override fun onIncomingAudio(session: Int, sequence: Long, opus: ByteArray, terminator: Boolean) {
        mixer.enqueue(session, sequence, opus, terminator)
    }

    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO before start()
    private fun captureLoop() {
        val config = routeConfig
        val frame = AudioConstants.FRAME_SAMPLES
        val minBuf = AudioRecord.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frame * 2 * 4)

        val record = AudioRecord(
            config.recordSource,
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf,
        )
        // Pin the input to the built-in mic for the A2DP-HQ route (A2DP carries no microphone).
        deviceById(config.recordDeviceId)?.let { record.setPreferredDevice(it) }

        // Attach hardware noise suppression / echo cancellation when enabled and available.
        val sessionId = record.audioSessionId
        val nsEffect = if (noiseSuppression && NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        val aecEffect = if (echoCancellation && AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull() else null

        val encoder = OpusEncoder(bitrate = bitrate)
        encoderRef = encoder
        val pcm = ShortArray(frame)
        var wasTransmitting = false

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

                applyGain(pcm, read)
                val level = rms(pcm)
                _inputLevel.value = level
                val active = !muted && shouldTransmit(level)
                _transmitting.value = active

                if (active) {
                    val opus = encoder.encode(pcm, frame)
                    if (opus != null) sendFrame(opus, false)
                    wasTransmitting = true
                } else if (wasTransmitting) {
                    // Send a terminator frame so listeners stop concealing.
                    val opus = encoder.encode(ShortArray(frame), frame)
                    if (opus != null) sendFrame(opus, true)
                    wasTransmitting = false
                }
            }
        } catch (_: Throwable) {
            // surfaced via transmitting=false; engine stop() cleans up
        } finally {
            runCatching { nsEffect?.release() }
            runCatching { aecEffect?.release() }
            runCatching { record.stop() }
            runCatching { record.release() }
            encoderRef = null
            encoder.release()
            _transmitting.value = false
        }
    }

    private fun playbackLoop() {
        val config = routeConfig
        val frame = AudioConstants.FRAME_SAMPLES
        val minBuf = AudioTrack.getMinBufferSize(
            AudioConstants.SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(frame * 2 * 4)

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(config.trackUsage)
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
        // Route playback to the A2DP sink for the HQ route.
        deviceById(config.trackDeviceId)?.let { track.setPreferredDevice(it) }

        val out = ShortArray(frame)
        try {
            track.play()
            var idleSpins = 0
            while (running) {
                val speaking = mixer.mixNextFrame(out)
                if (_speaking.value != speaking) _speaking.value = speaking
                track.write(out, 0, frame)
                // mixNextFrame consumes one 20ms frame; AudioTrack.write blocks to pace us.
                if (speaking.isEmpty()) {
                    idleSpins++
                    if (idleSpins > 200) Thread.sleep(5)
                } else idleSpins = 0
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { track.stop() }
            runCatching { track.release() }
        }
    }

    private fun shouldTransmit(level: Float): Boolean = when (mode) {
        TransmissionMode.PTT -> pttHeld
        TransmissionMode.VAD -> level >= vadThreshold
    }

    private fun applyGain(pcm: ShortArray, len: Int) {
        if (micGain == 1.0f) return
        for (i in 0 until len) {
            pcm[i] = (pcm[i] * micGain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun rms(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) { val v = s / 32768.0; sum += v * v }
        return sqrt(sum / pcm.size).toFloat()
    }
}
