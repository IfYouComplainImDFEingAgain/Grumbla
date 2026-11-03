package app.notmumla.audio.routing

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder

/**
 * User override for whether the phone loudspeaker plays as a call or as media. Only the
 * loudspeaker needs a choice: every other output has one right answer (see [resolveRoute]).
 */
enum class CallModeOverride { AUTO, CALL, MEDIA }

/**
 * What we know about this phone's echo cancelling on the loudspeaker outside call mode.
 * `AcousticEchoCanceler.isAvailable()` doesn't answer this; only measuring it does.
 */
enum class SpeakerEcho {
    /** Not measured yet. Treated like [CANCELLED]: media mode keeps the media volume keys. */
    UNKNOWN,

    /** Our playback didn't show up in the mic capture in media mode. */
    CANCELLED,

    /** Our playback leaked into the mic in media mode, so others hear themselves. Needs call mode. */
    LEAKS,
}

/** Device ids [resolveRoute] can point a config at; null where the device isn't present. */
data class RouteDevices(
    val speakerOut: Int? = null,
    val speakerComm: Int? = null,
    val earpieceOut: Int? = null,
    val earpieceComm: Int? = null,
    val wiredOut: Int? = null,
    val a2dpOut: Int? = null,
    /** LE Audio headset, else SCO, from the communication devices. */
    val btHeadsetComm: Int? = null,
    val builtinMic: Int? = null,
)

/**
 * The one place that decides call vs. media mode for an output, so the rules can't drift apart:
 *
 * | Output            | Normally                       | While another app records            |
 * |-------------------|--------------------------------|--------------------------------------|
 * | Earpiece          | call (media can't reach it)    | voice usage pinned to it, no call    |
 * | Loudspeaker       | [override], else [speakerEcho] | media                                |
 * | Wired             | media (no acoustic echo)       | media                                |
 * | BT A2DP-HQ        | media + built-in mic           | media                                |
 * | BT headset (SCO)  | call                           | media over the headset's A2DP        |
 *
 * While another app records ([micContended]), call mode has to go, because Android silences every
 * other app's capture while it's on. Our own capture is stopped then, so only playback matters.
 *
 * [earpiece] asks for the ear speaker on [OutputRoute.PHONE_SPEAKER]; it's ignored without one.
 */
fun resolveRoute(
    route: OutputRoute,
    earpiece: Boolean,
    override: CallModeOverride,
    speakerEcho: SpeakerEcho,
    micContended: Boolean,
    devices: RouteDevices,
): RouteConfig = when (route) {
    OutputRoute.PHONE_SPEAKER -> when {
        earpiece && devices.earpieceComm != null ->
            if (micContended) {
                // Media usage can't reach the earpiece; voice usage outside call mode is the one way
                // there, pinned to it so the HAL doesn't pick the loudspeaker instead.
                voice(route, communicationDeviceId = null).copy(
                    audioMode = AudioManager.MODE_NORMAL,
                    trackDeviceId = devices.earpieceOut,
                )
            } else {
                voice(route, communicationDeviceId = devices.earpieceComm)
            }

        micContended || speakerWantsMedia(override, speakerEcho) -> media(route, devices.speakerOut)

        // MODE_IN_COMMUNICATION alone plays on the earpiece; the loudspeaker as communication
        // device is what makes call mode audible.
        else -> voice(route, communicationDeviceId = devices.speakerComm)
    }

    OutputRoute.WIRED -> media(route, devices.wiredOut)

    OutputRoute.BT_A2DP_HQ -> RouteConfig(
        route = route,
        // MODE_NORMAL so the system keeps the high-quality A2DP profile instead of HFP/SCO.
        audioMode = AudioManager.MODE_NORMAL,
        // MIC (not VOICE_COMMUNICATION) avoids the framework steering capture toward SCO.
        recordSource = MediaRecorder.AudioSource.MIC,
        recordDeviceId = devices.builtinMic,
        trackUsage = AudioAttributes.USAGE_MEDIA,
        trackDeviceId = devices.a2dpOut,
        communicationDeviceId = null, // no communication device → no SCO
    )

    // Without SCO, the headset's A2DP profile (if it has one) is the way to reach it.
    OutputRoute.BT_HEADSET_SCO ->
        if (micContended) media(route, devices.a2dpOut)
        else voice(route, communicationDeviceId = devices.btHeadsetComm)
}

private fun speakerWantsMedia(override: CallModeOverride, echo: SpeakerEcho) = when (override) {
    CallModeOverride.CALL -> false
    CallModeOverride.MEDIA -> true
    CallModeOverride.AUTO -> echo != SpeakerEcho.LEAKS
}

/** Call mode: the clean, hardware-leveled capture path Mumble/Mumla use. */
private fun voice(route: OutputRoute, communicationDeviceId: Int?) = RouteConfig(
    route = route,
    audioMode = AudioManager.MODE_IN_COMMUNICATION,
    recordSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    recordDeviceId = null,
    trackUsage = AudioAttributes.USAGE_VOICE_COMMUNICATION,
    trackDeviceId = null,
    communicationDeviceId = communicationDeviceId,
)

/**
 * Media mode: the volume keys set media volume. Capture keeps VOICE_COMMUNICATION; whether that
 * still gets the platform's echo canceller outside call mode is up to the device's audio HAL.
 * The track is pinned to [output], since voice usage outside call mode can land on the earpiece.
 */
private fun media(route: OutputRoute, output: Int?) = RouteConfig(
    route = route,
    audioMode = AudioManager.MODE_NORMAL,
    recordSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION,
    recordDeviceId = null,
    trackUsage = AudioAttributes.USAGE_MEDIA,
    trackDeviceId = output,
    communicationDeviceId = null,
)
