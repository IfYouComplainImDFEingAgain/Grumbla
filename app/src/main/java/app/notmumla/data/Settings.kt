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
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.ui.ChannelLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }
enum class NoiseSuppression { OFF, STANDARD, AI }

/** All persisted user settings, with sensible defaults. */
data class AppSettings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val channelLayout: ChannelLayout = ChannelLayout.TREE,
    val transmissionMode: TransmissionMode = TransmissionMode.PTT,
    val micGainDb: Float = 0f,
    val autoGain: Boolean = true,
    val vadSensitivity: Float = 0.008f, // normalized RMS threshold (used when autoSensitivity=false)
    val autoSensitivity: Boolean = true, // continuously adapt the VAD threshold
    val noiseSuppression: NoiseSuppression = NoiseSuppression.OFF,
    val noiseReduction: Float = 0.7f, // RNNoise (AI) strength 0..1; <1 softens over-suppression
    val rawMic: Boolean = false, // capture from an unprocessed source (no native NS/AGC/echo-cancel)
    val echoCancellation: Boolean = true,
    val shareMic: Boolean = true, // release the mic (and call mode) while another app records
    val audioLeveling: Boolean = false, // normalize incoming speakers to a consistent loudness
    val mediaVolume: Boolean = true, // play phone/wired audio as media, not a call (volume keys = media)
    val audioBitrate: Int = 128_000,
    val showAvatars: Boolean = true,
    val keepScreenAwake: Boolean = false,
    val autoReconnect: Boolean = true,
    val ttsReadAloud: Boolean = false,
    val mentionSound: Boolean = true,
    /** Chat composer with a formatting toolbar (WYSIWYG) instead of typed Markdown. */
    val richComposer: Boolean = false,
    val debugOverlay: Boolean = false,
    /** Output routes in preference order; on connect the first one currently available is used. */
    val routePriority: List<OutputRoute> = DEFAULT_ROUTE_PRIORITY,
    /** Start with the last manually chosen route instead of walking [routePriority]. */
    val rememberLastRoute: Boolean = false,
    val lastRoute: OutputRoute? = null,
    /** Mid-call, jump to Bluetooth output as soon as a Bluetooth device connects. */
    val autoSwitchBluetooth: Boolean = true,
)

val DEFAULT_ROUTE_PRIORITY = listOf(
    OutputRoute.WIRED, OutputRoute.BT_A2DP_HQ, OutputRoute.BT_HEADSET_SCO, OutputRoute.PHONE_SPEAKER,
)

/** Parse a stored priority list, tolerating unknown names and appending routes added since. */
internal fun decodeRoutePriority(s: String?): List<OutputRoute> {
    val parsed = s.orEmpty().split(',').mapNotNull { n -> runCatching { OutputRoute.valueOf(n.trim()) }.getOrNull() }
        .distinct()
    return parsed + DEFAULT_ROUTE_PRIORITY.filter { it !in parsed }
}

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
        val AUTO_GAIN = booleanPreferencesKey("auto_gain")
        val VAD = floatPreferencesKey("vad_sensitivity")
        val AUTO_SENS = booleanPreferencesKey("auto_sensitivity")
        val NS_MODE = stringPreferencesKey("ns_mode")
        val NS = booleanPreferencesKey("noise_suppression") // legacy (boolean) — for migration only
        val NS_STRENGTH = floatPreferencesKey("ns_strength")
        val RAW_MIC = booleanPreferencesKey("raw_mic")
        val AEC = booleanPreferencesKey("echo_cancellation")
        val SHARE_MIC = booleanPreferencesKey("share_mic")
        val MEDIA_VOLUME = booleanPreferencesKey("media_volume")
        val LEVELING = booleanPreferencesKey("audio_leveling")
        val BITRATE = intPreferencesKey("audio_bitrate")
        val AVATARS = booleanPreferencesKey("show_avatars")
        val AWAKE = booleanPreferencesKey("keep_awake")
        val RECONNECT = booleanPreferencesKey("auto_reconnect")
        val TTS = booleanPreferencesKey("tts_read_aloud")
        val RICH_COMPOSER = booleanPreferencesKey("rich_composer")
        val MENTION = booleanPreferencesKey("mention_sound")
        val DEBUG = booleanPreferencesKey("debug_overlay")
        val ROUTE_PRIORITY = stringPreferencesKey("route_priority") // comma-separated OutputRoute names
        val REMEMBER_ROUTE = booleanPreferencesKey("remember_last_route")
        val LAST_ROUTE = stringPreferencesKey("last_route")
        val AUTO_BT = booleanPreferencesKey("auto_switch_bluetooth")
        val USER_VOLUMES = stringPreferencesKey("user_volumes") // JSON: {name: gainDb}
    }

    /** Persisted local per-user volume adjustments (username -> gain in dB). */
    val userVolumes: Flow<Map<String, Float>> = context.dataStore.data.map { decodeVolumes(it[Keys.USER_VOLUMES]) }

    suspend fun setUserVolume(name: String, db: Float) = edit { p ->
        val map = decodeVolumes(p[Keys.USER_VOLUMES]).toMutableMap()
        if (db == 0f) map.remove(name) else map[name] = db
        p[Keys.USER_VOLUMES] = encodeVolumes(map)
    }

    private fun decodeVolumes(s: String?): Map<String, Float> {
        if (s.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = org.json.JSONObject(s)
            buildMap { o.keys().forEach { k -> put(k, o.getDouble(k).toFloat()) } }
        }.getOrDefault(emptyMap())
    }

    private fun encodeVolumes(map: Map<String, Float>): String {
        val o = org.json.JSONObject()
        map.forEach { (k, v) -> o.put(k, v.toDouble()) }
        return o.toString()
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            theme = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            channelLayout = p[Keys.LAYOUT]?.let { runCatching { ChannelLayout.valueOf(it) }.getOrNull() } ?: ChannelLayout.TREE,
            transmissionMode = p[Keys.TX_MODE]?.let { runCatching { TransmissionMode.valueOf(it) }.getOrNull() } ?: TransmissionMode.PTT,
            micGainDb = p[Keys.MIC_GAIN] ?: 0f,
            autoGain = p[Keys.AUTO_GAIN] ?: true,
            vadSensitivity = p[Keys.VAD] ?: 0.008f,
            autoSensitivity = p[Keys.AUTO_SENS] ?: true,
            noiseSuppression = p[Keys.NS_MODE]?.let { runCatching { NoiseSuppression.valueOf(it) }.getOrNull() }
                ?: p[Keys.NS]?.let { if (it) NoiseSuppression.STANDARD else NoiseSuppression.OFF }
                ?: NoiseSuppression.OFF,
            noiseReduction = p[Keys.NS_STRENGTH] ?: 0.7f,
            rawMic = p[Keys.RAW_MIC] ?: false,
            echoCancellation = p[Keys.AEC] ?: true,
            shareMic = p[Keys.SHARE_MIC] ?: true,
            audioLeveling = p[Keys.LEVELING] ?: false,
            mediaVolume = p[Keys.MEDIA_VOLUME] ?: true,
            audioBitrate = p[Keys.BITRATE] ?: 128_000,
            showAvatars = p[Keys.AVATARS] ?: true,
            keepScreenAwake = p[Keys.AWAKE] ?: false,
            autoReconnect = p[Keys.RECONNECT] ?: true,
            ttsReadAloud = p[Keys.TTS] ?: false,
            mentionSound = p[Keys.MENTION] ?: true,
            richComposer = p[Keys.RICH_COMPOSER] ?: false,
            debugOverlay = p[Keys.DEBUG] ?: false,
            routePriority = decodeRoutePriority(p[Keys.ROUTE_PRIORITY]),
            rememberLastRoute = p[Keys.REMEMBER_ROUTE] ?: false,
            lastRoute = p[Keys.LAST_ROUTE]?.let { runCatching { OutputRoute.valueOf(it) }.getOrNull() },
            autoSwitchBluetooth = p[Keys.AUTO_BT] ?: true,
        )
    }

    suspend fun setTheme(v: ThemeMode) = edit { it[Keys.THEME] = v.name }
    suspend fun setChannelLayout(v: ChannelLayout) = edit { it[Keys.LAYOUT] = v.name }
    suspend fun setTransmissionMode(v: TransmissionMode) = edit { it[Keys.TX_MODE] = v.name }
    suspend fun setMicGainDb(v: Float) = edit { it[Keys.MIC_GAIN] = v }
    suspend fun setAutoGain(v: Boolean) = edit { it[Keys.AUTO_GAIN] = v }
    suspend fun setVadSensitivity(v: Float) = edit { it[Keys.VAD] = v }
    suspend fun setAutoSensitivity(v: Boolean) = edit { it[Keys.AUTO_SENS] = v }
    suspend fun setNoiseSuppression(v: NoiseSuppression) = edit { it[Keys.NS_MODE] = v.name }
    suspend fun setNoiseReduction(v: Float) = edit { it[Keys.NS_STRENGTH] = v }
    suspend fun setRawMic(v: Boolean) = edit { it[Keys.RAW_MIC] = v }
    suspend fun setEchoCancellation(v: Boolean) = edit { it[Keys.AEC] = v }
    suspend fun setShareMic(v: Boolean) = edit { it[Keys.SHARE_MIC] = v }
    suspend fun setAudioLeveling(v: Boolean) = edit { it[Keys.LEVELING] = v }
    suspend fun setMediaVolume(v: Boolean) = edit { it[Keys.MEDIA_VOLUME] = v }
    suspend fun setAudioBitrate(v: Int) = edit { it[Keys.BITRATE] = v }
    suspend fun setShowAvatars(v: Boolean) = edit { it[Keys.AVATARS] = v }
    suspend fun setKeepScreenAwake(v: Boolean) = edit { it[Keys.AWAKE] = v }
    suspend fun setAutoReconnect(v: Boolean) = edit { it[Keys.RECONNECT] = v }
    suspend fun setTtsReadAloud(v: Boolean) = edit { it[Keys.TTS] = v }
    suspend fun setMentionSound(v: Boolean) = edit { it[Keys.MENTION] = v }
    suspend fun setRichComposer(v: Boolean) = edit { it[Keys.RICH_COMPOSER] = v }
    suspend fun setDebugOverlay(v: Boolean) = edit { it[Keys.DEBUG] = v }
    suspend fun setRoutePriority(v: List<OutputRoute>) = edit { it[Keys.ROUTE_PRIORITY] = v.joinToString(",") { r -> r.name } }
    suspend fun setRememberLastRoute(v: Boolean) = edit { it[Keys.REMEMBER_ROUTE] = v }
    suspend fun setLastRoute(v: OutputRoute) = edit { it[Keys.LAST_ROUTE] = v.name }
    suspend fun setAutoSwitchBluetooth(v: Boolean) = edit { it[Keys.AUTO_BT] = v }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }
}
