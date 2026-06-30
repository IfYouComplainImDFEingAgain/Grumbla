package app.notmumla.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notmumla.audio.TransmissionMode
import app.notmumla.data.AppSettings
import app.notmumla.data.IdentityStore
import app.notmumla.data.SettingsRepository
import app.notmumla.data.ThemeMode
import app.notmumla.protocol.net.CertFingerprint
import app.notmumla.ui.ChannelLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Human-readable details of the local identity certificate, for the Settings dialog. */
data class IdentityInfo(
    val commonName: String,
    val sha256: String,
    val sha1: String,
    val issued: String,
    val expires: String,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repo: SettingsRepository,
    private val identityStore: IdentityStore,
) : ViewModel() {

    val settings = repo.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _identity = MutableStateFlow<IdentityInfo?>(null)
    /** The local identity certificate's details, loaded lazily (decrypting the keystore is I/O). */
    val identity: StateFlow<IdentityInfo?> = _identity

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val cert = identityStore.getOrCreate().certificate
                val cn = Regex("CN=([^,]+)").find(cert.subjectX500Principal.name)
                    ?.groupValues?.get(1)?.trim() ?: "Mumble User"
                val df = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.US)
                IdentityInfo(
                    commonName = cn,
                    sha256 = CertFingerprint.sha256(cert),
                    sha1 = CertFingerprint.sha1(cert),
                    issued = df.format(cert.notBefore),
                    expires = df.format(cert.notAfter),
                )
            }.getOrNull()?.let { _identity.value = it }
        }
    }

    fun setTheme(v: ThemeMode) = update { repo.setTheme(v) }
    fun setChannelLayout(v: ChannelLayout) = update { repo.setChannelLayout(v) }
    fun setTransmissionMode(v: TransmissionMode) = update { repo.setTransmissionMode(v) }
    fun setMicGainDb(v: Float) = update { repo.setMicGainDb(v) }
    fun setAutoGain(v: Boolean) = update { repo.setAutoGain(v) }
    fun setVadSensitivity(v: Float) = update { repo.setVadSensitivity(v) }
    fun setNoiseSuppression(v: app.notmumla.data.NoiseSuppression) = update { repo.setNoiseSuppression(v) }
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
