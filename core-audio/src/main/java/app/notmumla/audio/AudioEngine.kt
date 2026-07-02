package app.notmumla.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import app.notmumla.audio.codec.Denoiser
import app.notmumla.audio.codec.OpusEncoder
import app.notmumla.audio.playback.SpeakerMixer
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.audio.routing.RouteConfig
import app.notmumla.protocol.VoiceSink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.thread
import kotlin.math.sqrt

/** How the mic decides when to transmit. */
enum class TransmissionMode { PTT, VAD }

/** Frames of audio kept before VAD opens, flushed on open so speech onsets aren't clipped (~60 ms). */
private const val VAD_PREROLL_FRAMES = 6

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
    /** When true, capture continues (for the meter) but nothing is sent — used while Settings is open. */
    @Volatile var suppressTransmit: Boolean = false
    @Volatile var micGain: Float = 1.0f
    /** When true, the platform AutomaticGainControl effect is attached to the mic (auto-leveling). */
    @Volatile var autoGain: Boolean = true
    /** Normalized VAD threshold (RMS over full-scale). */
    @Volatile var vadThreshold: Float = 0.008f
    /** Raw mic: capture from an unprocessed source (no native NS/AGC/echo-cancel) on phone/wired. */
    @Volatile var rawMic: Boolean = false
    @Volatile var noiseSuppression: Boolean = true
    /** RNNoise (ML) suppression — when on, hardware [noiseSuppression] should be off (no double-NS). */
    @Volatile var aiNoiseSuppression: Boolean = false
    /** RNNoise strength: 1.0 = full, lower blends in the original to soften over-suppression. */
    @Volatile var noiseReductionMix: Float = 1.0f
    @Volatile var echoCancellation: Boolean = true
    @Volatile var bitrate: Int = 128_000
    /** When true (default), VAD sensitivity adapts continuously; [vadThreshold] is ignored. */
    @Volatile var autoSensitivity: Boolean = true
    @Volatile private var pttHeld: Boolean = false
    private val vad = AdaptiveVad()
    @Volatile private var encoderRef: OpusEncoder? = null

    /**
     * Apply audio-processing settings. Gain/VAD/bitrate take effect live; toggling the hardware
     * effects (NS/AEC) requires recreating the capture, so we restart if those changed while running.
     */
    fun applyAudioSettings(
        micGain: Float, vadThreshold: Float, bitrate: Int,
        noiseSuppression: Boolean, aiNoiseSuppression: Boolean, noiseReductionMix: Float,
        echoCancellation: Boolean, autoGain: Boolean, autoSensitivity: Boolean, rawMic: Boolean,
        audioLeveling: Boolean,
    ) {
        this.micGain = micGain
        this.vadThreshold = vadThreshold
        this.autoSensitivity = autoSensitivity
        this.noiseReductionMix = noiseReductionMix
        mixer.leveling = audioLeveling
        // NS/AEC/AGC/RNNoise and the capture source are bound when the AudioRecord/denoiser is
        // created — toggling any requires recreating the capture.
        val effectsChanged = this.noiseSuppression != noiseSuppression ||
            this.aiNoiseSuppression != aiNoiseSuppression ||
            this.echoCancellation != echoCancellation || this.autoGain != autoGain ||
            this.rawMic != rawMic
        this.noiseSuppression = noiseSuppression
        this.aiNoiseSuppression = aiNoiseSuppression
        this.echoCancellation = echoCancellation
        this.autoGain = autoGain
        this.rawMic = rawMic
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

    /** Local playback gain (multiplier) for a remote speaker session. */
    fun setUserVolume(session: Int, gain: Float) = mixer.setUserGain(session, gain)

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

        // Raw mic: use the unprocessed VOICE_RECOGNITION source on phone/wired (SCO must keep the
        // comms source to capture the headset mic; A2DP-HQ already uses raw MIC).
        val source = if (rawMic && config.route != OutputRoute.BT_HEADSET_SCO &&
            config.recordSource == android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        ) android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION else config.recordSource
        val record = openRecord(source, minBuf)
        if (record == null) {
            android.util.Log.e("notmumla-audio", "AudioRecord failed to initialize for any source")
            _transmitting.value = false
            return
        }
        // Pin the input to the built-in mic for the A2DP-HQ route (A2DP carries no microphone).
        deviceById(config.recordDeviceId)?.let { record.setPreferredDevice(it) }

        // VOICE_COMMUNICATION already runs native NS/AEC/AGC; stacking our own effects on top just
        // double-processes and worsens the close-mic over-suppression. Only attach effects for raw
        // sources (e.g. the A2DP-HQ MIC route) that have no native processing.
        val sessionId = record.audioSessionId
        val rawSource = source != android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION
        val nsEffect = if (rawSource && noiseSuppression && NoiseSuppressor.isAvailable())
            runCatching { NoiseSuppressor.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        val aecEffect = if (rawSource && echoCancellation && AcousticEchoCanceler.isAvailable())
            runCatching { AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } }.getOrNull() else null
        val agcEffect = if (rawSource && autoGain && AutomaticGainControl.isAvailable())
            runCatching { AutomaticGainControl.create(sessionId)?.apply { enabled = true } }.getOrNull() else null

        var encoder = OpusEncoder(bitrate = bitrate)
        encoderRef = encoder
        // RNNoise needs its native 480-sample frame; our frame matches, so enable when requested.
        val denoiser = if (aiNoiseSuppression)
            runCatching { Denoiser().takeIf { it.frameSize == frame } }.getOrNull() else null
        val pcm = ShortArray(frame)
        var wasTransmitting = false
        vad.reset()
        // Rolling buffer of recent frames captured while not transmitting; flushed when VAD opens so
        // the speech onset (which is below threshold) isn't clipped.
        val preroll = ArrayDeque<ShortArray>()

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
                applyGain(pcm, read, micGain)
                val level = rms(pcm)
                _inputLevel.value = level
                val active = !muted && !suppressTransmit && shouldTransmit(level)
                _transmitting.value = active

                if (active) {
                    if (!wasTransmitting) {
                        // Start each talk-spurt with a fresh encoder so its first frame is
                        // self-contained and decodes cleanly regardless of the receiver's state —
                        // gating a single shared encoder distorts the onset. (Create new, then
                        // release old, so encoderRef always points at a live encoder.)
                        val stale = encoder
                        encoder = OpusEncoder(bitrate = bitrate)
                        encoderRef = encoder
                        runCatching { stale.release() }
                        // Flush the pre-roll (the onset captured just before VAD opened).
                        if (mode == TransmissionMode.VAD) {
                            while (preroll.isNotEmpty()) {
                                val o = encoder.encode(preroll.removeFirst(), frame)
                                if (o != null) sendFrame(o, false)
                            }
                        }
                    }
                    val opus = encoder.encode(pcm, frame)
                    if (opus != null) sendFrame(opus, false)
                    wasTransmitting = true
                } else {
                    if (wasTransmitting) {
                        // Empty terminator: just the flag, no decoded silence frame to click on.
                        sendFrame(ByteArray(0), true)
                        wasTransmitting = false
                    }
                    preroll.addLast(pcm.copyOf(read))
                    while (preroll.size > VAD_PREROLL_FRAMES) preroll.removeFirst()
                }
            }
        } catch (_: Throwable) {
            // surfaced via transmitting=false; engine stop() cleans up
        } finally {
            runCatching { nsEffect?.release() }
            runCatching { aecEffect?.release() }
            runCatching { agcEffect?.release() }
            runCatching { denoiser?.release() }
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
        TransmissionMode.VAD -> vad.shouldTransmit(level, if (autoSensitivity) null else vadThreshold)
    }

    /** Create an initialized AudioRecord, falling back through alternate sources if one won't init. */
    @SuppressLint("MissingPermission") // caller ensures RECORD_AUDIO before start()
    private fun openRecord(preferredSource: Int, minBuf: Int): AudioRecord? {
        val sources = buildList {
            add(preferredSource)
            add(android.media.MediaRecorder.AudioSource.MIC)
            add(android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION)
        }.distinct()
        for (src in sources) {
            val r = runCatching {
                AudioRecord(
                    src, AudioConstants.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuf,
                )
            }.getOrNull()
            if (r != null && r.state == AudioRecord.STATE_INITIALIZED) {
                if (src != preferredSource) {
                    android.util.Log.w("notmumla-audio", "record source $preferredSource unavailable; using $src")
                } else {
                    android.util.Log.i("notmumla-audio", "record source $src initialized")
                }
                return r
            }
            runCatching { r?.release() }
        }
        return null
    }

    private fun applyGain(pcm: ShortArray, len: Int, gain: Float) {
        if (gain == 1.0f) return
        for (i in 0 until len) {
            pcm[i] = (pcm[i] * gain).toInt()
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    private fun rms(pcm: ShortArray): Float {
        var sum = 0.0
        for (s in pcm) { val v = s / 32768.0; sum += v * v }
        return sqrt(sum / pcm.size).toFloat()
    }
}
