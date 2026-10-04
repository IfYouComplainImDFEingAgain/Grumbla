package app.notmumla.audio.routing

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
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

    /**
     * Play phone-speaker and wired audio as media (MODE_NORMAL) instead of as a call. In call mode
     * Android points the volume keys at call volume no matter what the app asks, so this is the only
     * way to get media volume. Capture keeps the VOICE_COMMUNICATION source; whether that still gets
     * the platform's echo canceller outside call mode is up to the device's audio HAL.
     */
    @Volatile var mediaVolume: Boolean = true

    /**
     * Phone route plays through the ear speaker instead of the loudspeaker. Android only reaches
     * the earpiece as a call's communication device, so this overrides [mediaVolume] on that route.
     */
    @Volatile var earpiece: Boolean = false

    /** Whether the phone has an ear speaker at all (tablets don't). */
    val hasEarpiece: Boolean
        get() = commDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) != null

    private fun usesEarpiece(route: OutputRoute) =
        route == OutputRoute.PHONE_SPEAKER && earpiece && hasEarpiece

    private val builtinMicId: Int?
        get() = inputDevice(AudioDeviceInfo.TYPE_BUILTIN_MIC)?.id

    /** Build the engine config for [route] using currently-connected devices. */
    fun configFor(route: OutputRoute): RouteConfig {
        val call = callConfigFor(route)
        if (!mediaVolume || usesEarpiece(route) ||
            (route != OutputRoute.PHONE_SPEAKER && route != OutputRoute.WIRED)) return call
        // SCO stays a call (HFP audio has its own headset volume); A2DP-HQ is already media.
        return asMedia(call)
    }

    /** [config] without call mode: media usage pinned to the route's output, no communication device. */
    private fun asMedia(config: RouteConfig): RouteConfig = config.copy(
        audioMode = AudioManager.MODE_NORMAL,
        communicationDeviceId = null,
        trackUsage = AudioAttributes.USAGE_MEDIA,
        trackDeviceId = when (config.route) {
            OutputRoute.PHONE_SPEAKER -> outputDevice(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)?.id
            // Without SCO, the headset's A2DP profile (if it has one) is the way to reach it.
            OutputRoute.BT_HEADSET_SCO -> outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)?.id
            else -> config.trackDeviceId
        },
    )

    private fun callConfigFor(route: OutputRoute): RouteConfig = when (route) {
        // MODE_IN_COMMUNICATION + VOICE_COMMUNICATION is the clean, hardware-leveled capture path that
        // Mumble/Mumla use. We explicitly route output to the built-in loudspeaker so voice doesn't
        // land on the earpiece (the reason we'd previously fallen back to the distortion-prone
        // MODE_NORMAL + VOICE_RECOGNITION combo).
        OutputRoute.PHONE_SPEAKER -> RouteConfig(
            route = route,
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            recordSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            recordDeviceId = null,
            trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
            trackDeviceId = null,
            communicationDeviceId = commDevice(
                if (usesEarpiece(route)) AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
                else AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
            )?.id,
        )

        OutputRoute.WIRED -> RouteConfig(
            route = route,
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            recordSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            recordDeviceId = null,
            trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
            trackDeviceId = outputDevice(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            )?.id,
            communicationDeviceId = outputDevice(
                AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            )?.id,
        )

        OutputRoute.BT_A2DP_HQ -> RouteConfig(
            route = route,
            // MODE_NORMAL so the system keeps the high-quality A2DP profile instead of HFP/SCO.
            audioMode = AudioManager.MODE_NORMAL,
            // MIC (not VOICE_COMMUNICATION) avoids the framework steering capture toward SCO.
            recordSource = MediaRecorder.AudioSource.MIC,
            recordDeviceId = builtinMicId,
            trackUsage = AudioAttributes.USAGE_MEDIA,
            trackDeviceId = outputDevice(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)?.id,
            communicationDeviceId = null, // do NOT set a comm device → no SCO
        )

        OutputRoute.BT_HEADSET_SCO -> {
            val bt = commDevice(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            RouteConfig(
                route = route,
                audioMode = AudioManager.MODE_IN_COMMUNICATION,
                recordSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                recordDeviceId = null,
                trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
                trackDeviceId = null,
                communicationDeviceId = bt?.id,
            )
        }
    }

    /**
     * Playback-only variant of [route] used while another app has the mic. Call mode makes Android
     * silence every other app's capture, so it's dropped: MODE_NORMAL, no communication device, and
     * media usage pinned to the route's output (voice usage outside call mode can land on the earpiece).
     */
    fun sharedConfigFor(route: OutputRoute): RouteConfig {
        val base = configFor(route)
        if (usesEarpiece(route)) {
            // Media usage can't reach the earpiece; voice usage outside call mode is the one way
            // there, pinned to it so the HAL doesn't pick the loudspeaker instead.
            return base.copy(
                audioMode = AudioManager.MODE_NORMAL,
                communicationDeviceId = null,
                trackDeviceId = outputDevice(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE)?.id,
            )
        }
        return if (base.audioMode == AudioManager.MODE_NORMAL) base else asMedia(base)
    }

    /**
     * Apply [route] at the AudioManager level (audio mode + communication device) and return the
     * [RouteConfig] the engine should adopt. Caller passes the config to AudioEngine.applyRoute.
     * [shareMic] selects the playback-only [sharedConfigFor] variant.
     */
    fun select(route: OutputRoute, shareMic: Boolean = false): RouteConfig {
        val config = if (shareMic) sharedConfigFor(route) else configFor(route)
        val commId = config.communicationDeviceId
        if (commId != null) {
            am.availableCommunicationDevices.firstOrNull { it.id == commId }
                ?.let { am.setCommunicationDevice(it) }
        } else {
            am.clearCommunicationDevice()
        }
        am.mode = config.audioMode
        _current.value = route
        return config
    }

    /**
     * Record [route] as the one to use without touching AudioManager — for when no call is active,
     * so choosing a route doesn't leave the system stuck in MODE_IN_COMMUNICATION.
     */
    fun markCurrent(route: OutputRoute) {
        _current.value = route
    }

    /** Restore the system audio mode/routing after a call ends. */
    fun reset() {
        runCatching { am.clearCommunicationDevice() }
        runCatching { am.mode = AudioManager.MODE_NORMAL }
    }
}
