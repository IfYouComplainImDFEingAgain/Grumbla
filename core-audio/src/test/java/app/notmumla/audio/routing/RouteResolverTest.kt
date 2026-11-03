package app.notmumla.audio.routing

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteResolverTest {

    private val devices = RouteDevices(
        speakerOut = 1, speakerComm = 11, earpieceOut = 2, earpieceComm = 12,
        wiredOut = 3, a2dpOut = 4, btHeadsetComm = 15, builtinMic = 20,
    )

    private fun resolve(
        route: OutputRoute,
        earpiece: Boolean = false,
        override: CallModeOverride = CallModeOverride.AUTO,
        echo: SpeakerEcho = SpeakerEcho.UNKNOWN,
        contended: Boolean = false,
        devices: RouteDevices = this.devices,
    ) = resolveRoute(route, earpiece, override, echo, contended, devices)

    private fun RouteConfig.assertCall(commId: Int?) {
        assertEquals(AudioManager.MODE_IN_COMMUNICATION, audioMode)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, trackUsage)
        assertEquals(MediaRecorder.AudioSource.VOICE_COMMUNICATION, recordSource)
        assertEquals(commId, communicationDeviceId)
    }

    private fun RouteConfig.assertMedia(trackId: Int?) {
        assertEquals(AudioManager.MODE_NORMAL, audioMode)
        assertEquals(AudioAttributes.USAGE_MEDIA, trackUsage)
        assertEquals(trackId, trackDeviceId)
        assertNull(communicationDeviceId)
    }

    @Test fun `loudspeaker auto is media while no echo has been seen`() {
        resolve(OutputRoute.PHONE_SPEAKER).assertMedia(1)
    }

    @Test fun `loudspeaker auto switches to call when playback leaks into the mic`() {
        resolve(OutputRoute.PHONE_SPEAKER, echo = SpeakerEcho.LEAKS).assertCall(11)
    }

    @Test fun `loudspeaker override beats the measurement`() {
        resolve(OutputRoute.PHONE_SPEAKER, override = CallModeOverride.CALL).assertCall(11)
        resolve(OutputRoute.PHONE_SPEAKER, override = CallModeOverride.MEDIA, echo = SpeakerEcho.LEAKS)
            .assertMedia(1)
    }

    @Test fun `loudspeaker drops call mode while another app records`() {
        resolve(OutputRoute.PHONE_SPEAKER, override = CallModeOverride.CALL, contended = true).assertMedia(1)
        resolve(OutputRoute.PHONE_SPEAKER, echo = SpeakerEcho.LEAKS, contended = true).assertMedia(1)
    }

    @Test fun `earpiece is always a call, whatever the override`() {
        for (o in CallModeOverride.entries) {
            resolve(OutputRoute.PHONE_SPEAKER, earpiece = true, override = o).assertCall(12)
        }
    }

    @Test fun `earpiece while another app records uses voice usage pinned to it, no call mode`() {
        val c = resolve(OutputRoute.PHONE_SPEAKER, earpiece = true, contended = true)
        assertEquals(AudioManager.MODE_NORMAL, c.audioMode)
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, c.trackUsage)
        assertEquals(2, c.trackDeviceId)
        assertNull(c.communicationDeviceId)
    }

    @Test fun `earpiece request without an earpiece falls back to the loudspeaker`() {
        val tablet = devices.copy(earpieceOut = null, earpieceComm = null)
        resolve(OutputRoute.PHONE_SPEAKER, earpiece = true, devices = tablet).assertMedia(1)
        resolve(OutputRoute.PHONE_SPEAKER, earpiece = true, override = CallModeOverride.CALL, devices = tablet)
            .assertCall(11)
    }

    @Test fun `wired is always media pinned to the headset`() {
        for (o in CallModeOverride.entries) for (e in SpeakerEcho.entries) for (m in listOf(false, true)) {
            resolve(OutputRoute.WIRED, override = o, echo = e, contended = m).assertMedia(3)
        }
    }

    @Test fun `a2dp hq is media with the built-in mic`() {
        for (o in CallModeOverride.entries) for (m in listOf(false, true)) {
            val c = resolve(OutputRoute.BT_A2DP_HQ, override = o, contended = m)
            c.assertMedia(4)
            assertEquals(MediaRecorder.AudioSource.MIC, c.recordSource)
            assertEquals(20, c.recordDeviceId)
        }
    }

    @Test fun `bt headset is a call, and media over a2dp while another app records`() {
        for (o in CallModeOverride.entries) {
            resolve(OutputRoute.BT_HEADSET_SCO, override = o).assertCall(15)
            resolve(OutputRoute.BT_HEADSET_SCO, override = o, contended = true).assertMedia(4)
        }
    }
}
