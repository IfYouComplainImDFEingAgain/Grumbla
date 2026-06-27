package app.notmumla.vm

import androidx.lifecycle.ViewModel
import app.notmumla.audio.TransmissionMode
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.SessionManager
import app.notmumla.protocol.model.ServerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val sessionManager: SessionManager,
) : ViewModel() {

    val state: StateFlow<ServerState> = sessionManager.state
    val serverLabel: StateFlow<String> = sessionManager.serverLabel
    val speakingSessions: StateFlow<Set<Int>> = sessionManager.speakingSessions
    val localTransmitting: StateFlow<Boolean> = sessionManager.localTransmitting
    val availableRoutes: StateFlow<List<OutputRoute>> = sessionManager.availableRoutes
    val currentRoute: StateFlow<OutputRoute> = sessionManager.currentRoute

    fun selectRoute(route: OutputRoute) = sessionManager.selectRoute(route)

    fun joinChannel(channelId: Int) = sessionManager.joinChannel(channelId)

    fun sendText(channelId: Int, message: String) = sessionManager.sendText(channelId, message)

    fun onAudioPermissionGranted() = sessionManager.onAudioPermissionGranted()

    // Voice controls.
    fun setPttHeld(held: Boolean) = sessionManager.setPttHeld(held)
    fun setTransmissionMode(mode: TransmissionMode) = sessionManager.setTransmissionMode(mode)

    /** Mute both the local mic and our server-side self-mute flag. */
    fun setMuted(muted: Boolean, deaf: Boolean) {
        sessionManager.setMicMuted(muted)
        sessionManager.setSelfMuteDeaf(muted, deaf)
    }

    fun disconnect() = sessionManager.disconnect()
}
