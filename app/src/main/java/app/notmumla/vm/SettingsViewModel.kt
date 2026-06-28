package app.notmumla.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notmumla.audio.TransmissionMode
import app.notmumla.data.AppSettings
import app.notmumla.data.SettingsRepository
import app.notmumla.data.ThemeMode
import app.notmumla.ui.ChannelLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
) : ViewModel() {

    val settings = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setTheme(v: ThemeMode) = update { repo.setTheme(v) }
    fun setChannelLayout(v: ChannelLayout) = update { repo.setChannelLayout(v) }
    fun setTransmissionMode(v: TransmissionMode) = update { repo.setTransmissionMode(v) }
    fun setMicGainDb(v: Float) = update { repo.setMicGainDb(v) }
    fun setAutoGain(v: Boolean) = update { repo.setAutoGain(v) }
    fun setVadSensitivity(v: Float) = update { repo.setVadSensitivity(v) }
    fun setNoiseSuppression(v: Boolean) = update { repo.setNoiseSuppression(v) }
    fun setEchoCancellation(v: Boolean) = update { repo.setEchoCancellation(v) }
    fun setAudioBitrate(v: Int) = update { repo.setAudioBitrate(v) }
    fun setShowAvatars(v: Boolean) = update { repo.setShowAvatars(v) }
    fun setKeepScreenAwake(v: Boolean) = update { repo.setKeepScreenAwake(v) }
    fun setAutoReconnect(v: Boolean) = update { repo.setAutoReconnect(v) }
    fun setTtsReadAloud(v: Boolean) = update { repo.setTtsReadAloud(v) }
    fun setMentionSound(v: Boolean) = update { repo.setMentionSound(v) }

    private inline fun update(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
