package app.notmumla.audio.routing

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder

/** User-selectable audio output route. The two Bluetooth entries are the headline feature. */
enum class OutputRoute {
    /** Built-in earpiece/loudspeaker. */
    PHONE_SPEAKER,

    /** Wired headset/headphones. */
    WIRED,

    /**
     * High-quality Bluetooth: output over A2DP (full bandwidth) while the **phone's built-in mic**
     * captures input. Runs in MODE_NORMAL so the system does not force the headset's HFP profile.
     */
    BT_A2DP_HQ,

    /** Standard Bluetooth headset: two-way over HFP/SCO (or LE Audio), mic + speaker on the headset. */
    BT_HEADSET_SCO,
}

/**
 * Concrete capture/playback parameters the [app.notmumla.audio.AudioEngine] applies for a route.
 * Device ids reference [android.media.AudioDeviceInfo.getId]; null means "let the system choose".
 */
data class RouteConfig(
    val route: OutputRoute,
    val audioMode: Int,
    val recordSource: Int,
    val recordDeviceId: Int?,
    val trackUsage: Int,
    val trackDeviceId: Int?,
    /** Device to hand to AudioManager.setCommunicationDevice, or null to clear it. */
    val communicationDeviceId: Int?,
) {
    companion object {
        /** Default phone speaker/mic route used before any Bluetooth selection. */
        val PHONE = RouteConfig(
            route = OutputRoute.PHONE_SPEAKER,
            audioMode = AudioManager.MODE_IN_COMMUNICATION,
            recordSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            recordDeviceId = null,
            trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
            trackDeviceId = null,
            communicationDeviceId = null,
        )
    }
}
