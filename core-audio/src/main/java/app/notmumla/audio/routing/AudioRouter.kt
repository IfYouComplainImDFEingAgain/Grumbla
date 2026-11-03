package app.notmumla.audio.routing

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns Android-level audio routing for the call. Discovers available output destinations, builds
 * the concrete [RouteConfig] for the engine, and applies the audio mode + communication device.
 *
 * The two Bluetooth routes are the headline capability:
 *  - [OutputRoute.BT_A2DP_HQ]: full-bandwidth A2DP output + built-in phone mic (MODE_NORMAL, no SCO).
 *  - [OutputRoute.BT_HEADSET_SCO]: two-way HFP/SCO or LE Audio (MODE_IN_COMMUNICATION).
 */
class AudioRouter(context: Context) {

    private val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val handler = Handler(Looper.getMainLooper())

    private val _available = MutableStateFlow(computeAvailable())
    /** Routes currently selectable given connected hardware. */
    val available: StateFlow<List<OutputRoute>> = _available

    private val _current = MutableStateFlow(OutputRoute.PHONE_SPEAKER)
    val current: StateFlow<OutputRoute> = _current

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = refresh()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = refresh()
    }

    fun startObserving() = am.registerAudioDeviceCallback(deviceCallback, handler)
    fun stopObserving() = am.unregisterAudioDeviceCallback(deviceCallback)

    // Fallback / auto-switch policy lives with the owner (it depends on user settings and has to
    // re-apply the engine too), so this only publishes what's selectable.
    private fun refresh() {
        _available.value = computeAvailable()
    }

    private fun outputs() = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    private fun inputs() = am.getDevices(AudioManager.GET_DEVICES_INPUTS)

    private fun hasOutput(vararg types: Int) = outputs().any { it.type in types }

    private fun computeAvailable(): List<OutputRoute> = buildList {
        add(OutputRoute.PHONE_SPEAKER)
        if (hasOutput(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)) {
            add(OutputRoute.WIRED)
        }
        if (hasOutput(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)) add(OutputRoute.BT_A2DP_HQ)
        if (commDevice(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET) != null) {
            add(OutputRoute.BT_HEADSET_SCO)
        }
    }

    private fun outputDevice(vararg types: Int): AudioDeviceInfo? =
        outputs().firstOrNull { it.type in types }

    private fun inputDevice(vararg types: Int): AudioDeviceInfo? =
        inputs().firstOrNull { it.type in types }

    private fun commDevice(vararg types: Int): AudioDeviceInfo? =
        am.availableCommunicationDevices.firstOrNull { it.type in types }

    /** Call vs. media choice for the loudspeaker; see [resolveRoute]. */
    @Volatile var callMode: CallModeOverride = CallModeOverride.AUTO

    /** Measured echo cancelling on the loudspeaker outside call mode; used by [CallModeOverride.AUTO]. */
    @Volatile var speakerEcho: SpeakerEcho = SpeakerEcho.UNKNOWN

    /**
     * Phone route plays through the ear speaker instead of the loudspeaker. Android only reaches
     * the earpiece as a call's communication device, so this is always call mode.
     */
    @Volatile var earpiece: Boolean = false

    /** Whether the phone has an ear speaker at all (tablets don't). */
    val hasEarpiece: Boolean
        get() = commDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) != null

    private fun devices() = RouteDevices(
        speakerOut = outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.id,
        speakerComm = commDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.id,
        earpieceOut = outputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)?.id,
        earpieceComm = commDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)?.id,
        wiredOut = outputDevice(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES)?.id,
        a2dpOut = outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)?.id,
        btHeadsetComm = commDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)?.id,
        builtinMic = inputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)?.id,
    )

    /**
     * Build the engine config for [route] using currently-connected devices. [micContended] gives
     * the playback-only variant used while another app has the mic (no call mode).
     */
    fun configFor(route: OutputRoute, micContended: Boolean = false): RouteConfig =
        resolveRoute(route, earpiece, callMode, speakerEcho, micContended, devices())

    /**
     * Whether [config] is the one case Auto still has to learn: the loudspeaker in media mode, where
     * echo cancelling is up to the device. Earpiece/call/contended configs never qualify.
     */
    fun needsEchoCheck(config: RouteConfig): Boolean =
        callMode == CallModeOverride.AUTO && speakerEcho == SpeakerEcho.UNKNOWN &&
            config.route == OutputRoute.PHONE_SPEAKER && config.audioMode == AudioManager.MODE_NORMAL &&
            config.trackUsage == android.media.AudioAttributes.USAGE_MEDIA

    /**
     * Apply [route] at the AudioManager level (audio mode + communication device) and return the
     * [RouteConfig] the engine should adopt. Caller passes the config to AudioEngine.applyRoute.
     * [shareMic] selects the playback-only variant used while another app records.
     */
    fun select(route: OutputRoute, shareMic: Boolean = false): RouteConfig {
        val config = configFor(route, micContended = shareMic)
        val stream = streamFor(config.trackUsage)
        // Same output, other mode (share mic, detected echo, setting change): media and call volume
        // are separate levels, so carry the one the user was hearing over. Not across outputs: a
        // headset's volume has nothing to do with the loudspeaker's.
        val carry = selected?.takeIf { it.route == route && it.stream != stream }?.let { prev ->
            Triple(am.getStreamVolume(prev.stream), am.getStreamMinVolume(prev.stream), am.getStreamMaxVolume(prev.stream))
        }
        val commId = config.communicationDeviceId
        if (commId != null) {
            am.availableCommunicationDevices.firstOrNull { it.id == commId }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            am.clearCommunicationDevice()
        }
        am.mode = config.audioMode
        handler.removeCallbacksAndMessages(CARRY_TOKEN)
        if (carry != null) {
            val (index0, min0, max0) = carry
            val index = carryVolume(index0, min0, max0, am.getStreamMinVolume(stream), am.getStreamMaxVolume(stream))
            val target = outputs().firstOrNull { it.id == (config.communicationDeviceId ?: config.trackDeviceId) }
            if (target != null) setVolumeOn(stream, config.trackUsage, index, target, attempts = 10)
        }
        selected = Selected(route, stream)
        _current.value = route
        return config
    }

    /**
     * Stream volume is stored per output device and applies to wherever the stream routes *now*.
     * The mode change lands asynchronously (right after leaving call mode, music still routes to the
     * earpiece), so wait until the stream reaches [target]; give up rather than set another device.
     */
    private fun setVolumeOn(stream: Int, usage: Int, index: Int, target: AudioDeviceInfo, attempts: Int) {
        val routed = if (android.os.Build.VERSION.SDK_INT >= 33) {
            val attrs = android.media.AudioAttributes.Builder().setUsage(usage).build()
            am.getAudioDevicesForAttributes(attrs).any { it.id == target.id }
        } else {
            attempts <= 7 // no way to ask before API 33: just give the mode change ~300 ms
        }
        if (routed) {
            runCatching { am.setStreamVolume(stream, index, 0) } // refused under Do Not Disturb
        } else if (attempts > 1) {
            handler.postDelayed({ setVolumeOn(stream, usage, index, target, attempts - 1) }, CARRY_TOKEN, 100)
        }
    }

    private data class Selected(val route: OutputRoute, val stream: Int)
    /** What the live call last selected; null when no call is active. */
    private var selected: Selected? = null

    private companion object { val CARRY_TOKEN = Any() }

    private fun streamFor(usage: Int) =
        if (usage == android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION) AudioManager.STREAM_VOICE_CALL
        else AudioManager.STREAM_MUSIC

    /**
     * Record [route] as the one to use without touching AudioManager — for when no call is active,
     * so choosing a route doesn't leave the system stuck in MODE_IN_COMMUNICATION.
     */
    fun markCurrent(route: OutputRoute) {
        _current.value = route
    }

    /** Restore the system audio mode/routing after a call ends. */
    fun reset() {
        selected = null
        handler.removeCallbacksAndMessages(CARRY_TOKEN)
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }
}

/**
 * [index] on a [min]..[max] stream mapped to the same position on [toMin]..[toMax]. Audible stays
 * audible: the lowest call level (quiet, not muted) mustn't become media level 0.
 */
internal fun carryVolume(index: Int, min: Int, max: Int, toMin: Int, toMax: Int): Int {
    val fraction = if (max <= min) 1f else ((index - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    val mapped = toMin + Math.round(fraction * (toMax - toMin))
    return if (index > 0 && mapped == 0 && toMax > 0) 1 else mapped
}
