package app.notmumla.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.notmumla.audio.TransmissionMode
import app.notmumla.data.AppSettings
import app.notmumla.data.IdentityStore
import app.notmumla.data.SettingsRepository
import app.notmumla.data.ThemeMode
import app.notmumla.protocol.identity.IdentityCertificate
import app.notmumla.protocol.net.CertFingerprint
import app.notmumla.ui.ChannelLayout
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        viewModelScope.launch { loadIdentity() }
    }

    private suspend fun loadIdentity() = withContext(Dispatchers.IO) {
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

    /** Generate a brand-new identity (servers that knew the old cert won't recognise it). */
    fun regenerateIdentity() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val cn = _identity.value?.commonName ?: "Mumble User"
            runCatching { identityStore.replace(IdentityCertificate.generate(cn)) }
        }
        loadIdentity()
    }

    /** Export the current identity as a password-protected PKCS#12 blob (null on failure). */
    suspend fun exportIdentity(password: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { identityStore.exportPkcs12(password.toCharArray()) }.getOrNull()
    }

    /** Import an identity from a PKCS#12 blob; returns true on success. */
    suspend fun importIdentity(bytes: ByteArray, password: String): Boolean {
        val ok = withContext(Dispatchers.IO) {
            runCatching { identityStore.importPkcs12(bytes, password.toCharArray()) }.isSuccess
        }
        if (ok) loadIdentity()
        return ok
    }

    fun setTheme(v: ThemeMode) = update { repo.setTheme(v) }
    fun setChannelLayout(v: ChannelLayout) = update { repo.setChannelLayout(v) }
    fun setTransmissionMode(v: TransmissionMode) = update { repo.setTransmissionMode(v) }
    fun setMicGainDb(v: Float) = update { repo.setMicGainDb(v) }
    fun setAutoGain(v: Boolean) = update { repo.setAutoGain(v) }
    fun setVadSensitivity(v: Float) = update { repo.setVadSensitivity(v) }
    fun setAutoSensitivity(v: Boolean) = update { repo.setAutoSensitivity(v) }
    fun setNoiseSuppression(v: app.notmumla.data.NoiseSuppression) = update { repo.setNoiseSuppression(v) }
    fun setNoiseReduction(v: Float) = update { repo.setNoiseReduction(v) }
    fun setRawMic(v: Boolean) = update { repo.setRawMic(v) }
    fun setEchoCancellation(v: Boolean) = update { repo.setEchoCancellation(v) }
    fun setShareMic(v: Boolean) = update { repo.setShareMic(v) }
    fun setAudioLeveling(v: Boolean) = update { repo.setAudioLeveling(v) }
    fun setMediaVolume(v: Boolean) = update { repo.setMediaVolume(v) }
    fun setPhoneEarpiece(v: Boolean) = update { repo.setPhoneEarpiece(v) }
    fun setAudioBitrate(v: Int) = update { repo.setAudioBitrate(v) }
    fun setShowAvatars(v: Boolean) = update { repo.setShowAvatars(v) }
    fun setKeepScreenAwake(v: Boolean) = update { repo.setKeepScreenAwake(v) }
    fun setAutoReconnect(v: Boolean) = update { repo.setAutoReconnect(v) }
    fun setTtsReadAloud(v: Boolean) = update { repo.setTtsReadAloud(v) }
    fun setMentionSound(v: Boolean) = update { repo.setMentionSound(v) }
    fun setRichComposer(v: Boolean) = update { repo.setRichComposer(v) }
    fun setDebugOverlay(v: Boolean) = update { repo.setDebugOverlay(v) }
    fun setGamesUnlocked(v: Boolean) = update { repo.setGamesUnlocked(v) }
    fun setRoutePriority(v: List<app.notmumla.audio.routing.OutputRoute>) = update { repo.setRoutePriority(v) }
    fun setRememberLastRoute(v: Boolean) = update { repo.setRememberLastRoute(v) }
    fun setAutoSwitchBluetooth(v: Boolean) = update { repo.setAutoSwitchBluetooth(v) }

    private inline fun update(crossinline block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
