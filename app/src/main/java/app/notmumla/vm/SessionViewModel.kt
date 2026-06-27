package app.notmumla.vm

import androidx.lifecycle.ViewModel
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

    fun joinChannel(channelId: Int) = sessionManager.joinChannel(channelId)

    fun setSelfMuteDeaf(mute: Boolean, deaf: Boolean) = sessionManager.setSelfMuteDeaf(mute, deaf)

    fun sendText(channelId: Int, message: String) = sessionManager.sendText(channelId, message)

    fun disconnect() = sessionManager.disconnect()
}
