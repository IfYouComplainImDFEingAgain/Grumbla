package app.notmumla.audio.routing

import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Detects another app trying to record, so we can hand it the mic.
 *
 * While we hold MODE_IN_COMMUNICATION (or a privacy-sensitive capture), Android silences every other
 * app's capture instead of failing it — the silenced client still shows up as an active recording
 * (anonymized), which is what we watch for. [isOwnSession] identifies our own AudioRecord sessions.
 *
 * Changes are debounced: a start must persist [YIELD_DELAY_MS] and an end [RESUME_DELAY_MS] before
 * [otherAppRecording] flips. That absorbs our own records lingering in the callback's snapshot across
 * a capture restart (route change, mic preview handing over) and apps that record in short bursts.
 */
class MicContentionMonitor(
    private val am: AudioManager,
    private val isOwnSession: (Int) -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val _otherAppRecording = MutableStateFlow(false)
    val otherAppRecording: StateFlow<Boolean> = _otherAppRecording

    private val callback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) = schedule()
    }
    private val evaluate = Runnable {
        // Re-read the live list rather than trusting the (possibly stale) callback snapshot.
        _otherAppRecording.value = am.activeRecordingConfigurations.any(::isForeignMicCapture)
    }
    private var observing = false

    fun start() {
        if (observing) return
        observing = true
        am.registerAudioRecordingCallback(callback, handler)
        schedule()
    }

    fun stop() {
        if (!observing) return
        observing = false
        am.unregisterAudioRecordingCallback(callback)
        handler.removeCallbacks(evaluate)
        _otherAppRecording.value = false
    }

    private fun schedule() {
        handler.removeCallbacks(evaluate)
        val delay = if (_otherAppRecording.value) RESUME_DELAY_MS else YIELD_DELAY_MS
        handler.postDelayed(evaluate, delay)
    }

    private fun isForeignMicCapture(c: AudioRecordingConfiguration): Boolean =
        !isOwnSession(c.clientAudioSessionId) && c.clientAudioSource !in NON_MIC_SOURCES

    private companion object {
        const val YIELD_DELAY_MS = 300L
        const val RESUME_DELAY_MS = 1000L
        /** Captures that don't compete for the microphone (playback capture, hotword DSP, echo ref). */
        val NON_MIC_SOURCES = setOf(
            MediaRecorder.AudioSource.REMOTE_SUBMIX,
            1997, // AUDIO_SOURCE_ECHO_REFERENCE (@SystemApi)
            1998, // AUDIO_SOURCE_FM_TUNER (@SystemApi)
            1999, // AUDIO_SOURCE_HOTWORD (@SystemApi)
        )
    }
}
