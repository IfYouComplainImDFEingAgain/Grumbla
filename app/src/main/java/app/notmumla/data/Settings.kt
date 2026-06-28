package app.notmumla.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.notmumla.audio.TransmissionMode
import app.notmumla.ui.ChannelLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** All persisted user settings, with sensible defaults. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val channelLayout: ChannelLayout = ChannelLayout.TREE,
    val transmissionMode: TransmissionMode = TransmissionMode.PTT,
    val micGainDb: Float = 0f,
    val vadSensitivity: Float = 0.02f, // normalized RMS threshold
    val noiseSuppression: Boolean = true,
    val echoCancellation: Boolean = true,
    val audioBitrate: Int = 72_000,
    val showAvatars: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val autoReconnect: Boolean = true,
    val ttsReadAloud: Boolean = false,
    val mentionSound: Boolean = true,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme")
        val LAYOUT = stringPreferencesKey("channel_layout")
        val TX_MODE = stringPreferencesKey("transmission_mode")
        val MIC_GAIN = floatPreferencesKey("mic_gain_db")
        val VAD = floatPreferencesKey("vad_sensitivity")
        val NS = booleanPreferencesKey("noise_suppression")
        val AEC = booleanPreferencesKey("echo_cancellation")
        val BITRATE = intPreferencesKey("audio_bitrate")
        val AVATARS = booleanPreferencesKey("show_avatars")
        val AWAKE = booleanPreferencesKey("keep_awake")
        val RECONNECT = booleanPreferencesKey("auto_reconnect")
        val TTS = booleanPreferencesKey("tts_read_aloud")
        val MENTION = booleanPreferencesKey("mention_sound")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            channelLayout = p[Keys.LAYOUT]?.let { runCatching { ChannelLayout.valueOf(it) }.getOrNull() } ?: ChannelLayout.TREE,
            transmissionMode = p[Keys.TX_MODE]?.let { runCatching { TransmissionMode.valueOf(it) }.getOrNull() } ?: TransmissionMode.PTT,
            micGainDb = p[Keys.MIC_GAIN] ?: 0f,
            vadSensitivity = p[Keys.VAD] ?: 0.02f,
            noiseSuppression = p[Keys.NS] ?: true,
            echoCancellation = p[Keys.AEC] ?: true,
            audioBitrate = p[Keys.BITRATE] ?: 72_000,
            showAvatars = p[Keys.AVATARS] ?: true,
            keepScreenAwake = p[Keys.AWAKE] ?: false,
            autoReconnect = p[Keys.RECONNECT] ?: true,
            ttsReadAloud = p[Keys.TTS] ?: false,
            mentionSound = p[Keys.MENTION] ?: true,
        )
    }

    suspend fun setTheme(v: ThemeMode) = edit { it[Keys.THEME] = v.name }
    suspend fun setChannelLayout(v: ChannelLayout) = edit { it[Keys.LAYOUT] = v.name }
    suspend fun setTransmissionMode(v: TransmissionMode) = edit { it[Keys.TX_MODE] = v.name }
    suspend fun setMicGainDb(v: Float) = edit { it[Keys.MIC_GAIN] = v }
    suspend fun setVadSensitivity(v: Float) = edit { it[Keys.VAD] = v }
    suspend fun setNoiseSuppression(v: Boolean) = edit { it[Keys.NS] = v }
    suspend fun setEchoCancellation(v: Boolean) = edit { it[Keys.AEC] = v }
    suspend fun setAudioBitrate(v: Int) = edit { it[Keys.BITRATE] = v }
    suspend fun setShowAvatars(v: Boolean) = edit { it[Keys.AVATARS] = v }
    suspend fun setKeepScreenAwake(v: Boolean) = edit { it[Keys.AWAKE] = v }
    suspend fun setAutoReconnect(v: Boolean) = edit { it[Keys.RECONNECT] = v }
    suspend fun setTtsReadAloud(v: Boolean) = edit { it[Keys.TTS] = v }
    suspend fun setMentionSound(v: Boolean) = edit { it[Keys.MENTION] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
