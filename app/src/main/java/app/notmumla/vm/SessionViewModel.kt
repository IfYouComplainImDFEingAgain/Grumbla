package app.notmumla.vm

import androidx.lifecycle.ViewModel
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.SessionManager
import app.notmumla.protocol.model.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    val state: StateFlow<ServerState> = sessionManager.state
    val serverLabel: StateFlow<String> = sessionManager.serverLabel
    val speakingSessions: StateFlow<Set<Int>> = sessionManager.speakingSessions
    val localTransmitting: StateFlow<Boolean> = sessionManager.localTransmitting
    val micYielded: StateFlow<Boolean> = sessionManager.micYielded
    val inputLevel: StateFlow<Float> = sessionManager.inputLevel
    val userVolumes: StateFlow<Map<String, Float>> = sessionManager.userVolumes
    fun setUserVolume(name: String, db: Float) = sessionManager.setUserVolume(name, db)
    val whisper: StateFlow<app.notmumla.data.UserRef?> = sessionManager.whisper
    fun startWhisper(session: Int, name: String) = sessionManager.startWhisper(session, name)
    fun stopWhisper() = sessionManager.stopWhisper()
    val debugStats: StateFlow<app.notmumla.protocol.model.AudioDebugStats> = sessionManager.debugStats
    val availableRoutes: StateFlow<List<OutputRoute>> = sessionManager.availableRoutes

    /** Live mic-level preview for the Settings meter while not connected. */
    fun startMicPreview() = sessionManager.startMicPreview()
    fun stopMicPreview() = sessionManager.stopMicPreview()

    /** Auto-calibrate the VAD threshold from a few seconds of the user talking. */
    val vadCalibrating: StateFlow<Boolean> = sessionManager.vadCalibrating
    fun calibrateVad() = sessionManager.calibrateVad()

    /** Mic self-test: record → VAD-gate → play back. Only when not in a call. */
    val micTestState: StateFlow<app.notmumla.audio.MicTestState> = sessionManager.micTestState
    val canTestMic: Boolean get() = sessionManager.canTestMic
    fun testMic() = sessionManager.testMic()
    val currentRoute: StateFlow<OutputRoute> = sessionManager.currentRoute
    val hasEarpiece: Boolean get() = sessionManager.hasEarpiece
    val chat: StateFlow<List<app.notmumla.data.ChatLine>> = sessionManager.chat
    val unreadChat: StateFlow<Int> = sessionManager.unreadChat

    fun selectRoute(route: OutputRoute) = sessionManager.selectRoute(route)
    fun markChatRead() = sessionManager.markChatRead()
    fun deleteChat(id: Long) = sessionManager.deleteChat(id)
    fun clearChat() = sessionManager.clearChat()

    fun joinChannel(channelId: Int) = sessionManager.joinChannel(channelId)
    fun setListening(channelId: Int, listen: Boolean) = sessionManager.setListening(channelId, listen)

    /** Server refusals (e.g. listener limit) to surface as a toast. */
    val denials: kotlinx.coroutines.flow.Flow<String> = sessionManager.events
        .filterIsInstance<app.notmumla.protocol.MumbleClient.Event.Denied>()
        .map { it.message }

    val privateChat: StateFlow<app.notmumla.data.UserRef?> = sessionManager.privateChat
    fun startPrivateChat(session: Int, name: String) = sessionManager.startPrivateChat(session, name)
    fun stopPrivateChat() = sessionManager.stopPrivateChat()

    fun sendText(channelId: Int, message: String, html: String? = null) =
        sessionManager.sendText(channelId, message, html)

    fun sendImage(channelId: Int, uri: android.net.Uri) = sessionManager.sendImage(channelId, uri)

    fun onAudioPermissionGranted() = sessionManager.onAudioPermissionGranted()

    // Voice controls.
    fun setPttHeld(held: Boolean) = sessionManager.setPttHeld(held)

    /** Mute both the local mic and our server-side self-mute flag. */
    fun setMuted(muted: Boolean, deaf: Boolean) {
        sessionManager.setMicMuted(muted)
        sessionManager.setSelfMuteDeaf(muted, deaf)
    }

    fun trustNewCertificate() = sessionManager.trustNewCertificate()

    fun disconnect() = sessionManager.disconnect()
}
