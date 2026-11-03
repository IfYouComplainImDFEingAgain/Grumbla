package app.notmumla.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.ui.graphics.vector.ImageVector
import app.notmumla.audio.routing.OutputRoute

/**
 * One searchable setting (design 03C). Hand-maintained: when a setting is added, renamed or moved
 * between pages, update this list too. [advanced] opens the page with its Advanced block expanded;
 * [permissionLabel] makes the result show that permission's live status instead of [subtitle].
 */
internal data class SearchEntry(
    val title: String,
    val page: SettingsPage,
    val icon: ImageVector,
    val subtitle: String? = null,
    val section: String? = null,
    val keywords: String = "",
    val advanced: Boolean = false,
    val permissionLabel: String? = null,
) {
    val breadcrumb: String get() = if (section != null) "${page.title} › $section" else page.title
    private val haystack = "$title ${subtitle.orEmpty()} $keywords".lowercase()
    fun matches(tokens: List<String>) = tokens.all { it in haystack }
}

internal val SETTINGS_INDEX: List<SearchEntry> = listOf(
    // Account
    SearchEntry("Profile & status", SettingsPage.ACCOUNT, Icons.Filled.Person, "Display name, avatar, comment",
        keywords = "name username"),
    SearchEntry("Identity certificate", SettingsPage.ACCOUNT, Icons.Filled.VerifiedUser, "Import, export or regenerate",
        keywords = "cert certificate fingerprint p12 pkcs12 backup"),
    // Connection
    SearchEntry("Auto-reconnect", SettingsPage.CONNECTION, Icons.Filled.Autorenew, "Rejoin last channel automatically",
        keywords = "reconnect"),
    SearchEntry("Audio quality", SettingsPage.CONNECTION, Icons.Filled.SignalCellularAlt, "Higher uses more data",
        keywords = "bitrate kbit opus data"),
    // Appearance
    SearchEntry("Theme", SettingsPage.APPEARANCE, Icons.Filled.Palette, "Light, dark or system",
        keywords = "night mode"),
    SearchEntry("Show user avatars", SettingsPage.APPEARANCE, Icons.Filled.AccountCircle, keywords = "pictures"),
    SearchEntry("Keep screen awake in voice", SettingsPage.APPEARANCE, Icons.Filled.ScreenLockPortrait,
        keywords = "display sleep timeout"),
    // Audio input
    SearchEntry("Transmission", SettingsPage.INPUT, Icons.Filled.Mic, "Push-to-Talk or Voice Activated",
        keywords = "ptt vad voice activity"),
    SearchEntry("Mute speakers while talking", SettingsPage.INPUT, Icons.AutoMirrored.Filled.VolumeOff,
        "Silence incoming audio while push-to-talk is held", keywords = "ptt"),
    SearchEntry("Only on loudspeaker", SettingsPage.INPUT, Icons.AutoMirrored.Filled.VolumeOff,
        "Keep listening on headphones, Bluetooth and the ear speaker", keywords = "mute ptt"),
    SearchEntry("Automatic sensitivity", SettingsPage.INPUT, Icons.Filled.GraphicEq, "Adjusts to your mic & room",
        keywords = "vad voice activated threshold"),
    SearchEntry("Input level", SettingsPage.INPUT, Icons.Filled.BarChart, "Live mic meter, threshold and test",
        keywords = "meter vu threshold calibrate auto-set test mic"),
    SearchEntry("Noise suppression", SettingsPage.INPUT, Icons.Filled.GraphicEq, "AI uses RNNoise for cleaner voice",
        section = "Processing", keywords = "rnnoise denoise"),
    SearchEntry("Reduction strength", SettingsPage.INPUT, Icons.Filled.GraphicEq, "AI noise suppression",
        section = "Processing", keywords = "noise rnnoise"),
    SearchEntry("Automatic gain control", SettingsPage.INPUT, Icons.Filled.GraphicEq, section = "Processing",
        keywords = "agc volume loudness"),
    SearchEntry("Echo cancellation", SettingsPage.INPUT, Icons.Filled.GraphicEq, section = "Processing",
        keywords = "aec"),
    SearchEntry("Raw microphone", SettingsPage.INPUT, Icons.Filled.Mic, "Skip the phone's voice processing",
        section = "Advanced", keywords = "unprocessed", advanced = true),
    SearchEntry("Share microphone", SettingsPage.INPUT, Icons.Filled.Mic, "Let other apps use the mic",
        section = "Advanced", keywords = "concurrent capture", advanced = true),
    SearchEntry("Microphone gain", SettingsPage.INPUT, Icons.Filled.GraphicEq, section = "Advanced",
        keywords = "mic volume boost db", advanced = true),
    // Audio output
    SearchEntry(OutputRoute.BT_A2DP_HQ.displayLabel(), SettingsPage.OUTPUT, OutputRoute.BT_A2DP_HQ.icon(),
        "Better audio, higher latency", section = "Output device", keywords = "a2dp headphones"),
    SearchEntry(OutputRoute.WIRED.displayLabel(), SettingsPage.OUTPUT, OutputRoute.WIRED.icon(),
        section = "Output device", keywords = "headphones usb jack"),
    SearchEntry(OutputRoute.BT_HEADSET_SCO.displayLabel(), SettingsPage.OUTPUT, OutputRoute.BT_HEADSET_SCO.icon(),
        section = "Output device", keywords = "sco hfp"),
    SearchEntry(OutputRoute.PHONE_SPEAKER.displayLabel(), SettingsPage.OUTPUT, OutputRoute.PHONE_SPEAKER.icon(),
        section = "Output device", keywords = "loudspeaker"),
    SearchEntry("Phone speaker plays through", SettingsPage.OUTPUT, Icons.Filled.Speaker,
        "Speakerphone or ear speaker", section = "Output device", keywords = "earpiece"),
    SearchEntry("Remember last output", SettingsPage.OUTPUT, Icons.Filled.Autorenew,
        "Reordering is off while this is on", keywords = "route priority order"),
    SearchEntry("Switch to Bluetooth when connected", SettingsPage.OUTPUT, Icons.Filled.Bluetooth,
        keywords = "auto"),
    SearchEntry("Audio leveling", SettingsPage.OUTPUT, Icons.AutoMirrored.Filled.VolumeUp,
        "Even out quiet and loud talkers", keywords = "volume normalize loudness compressor"),
    SearchEntry("Speaker audio mode", SettingsPage.OUTPUT, Icons.AutoMirrored.Filled.VolumeUp, "Auto, Call or Media",
        section = "Advanced", keywords = "call mode media echo", advanced = true),
    // Chat
    SearchEntry("Formatting toolbar", SettingsPage.CHAT, Icons.Filled.FormatBold, "Bold, italic, color…",
        keywords = "markdown rich composer"),
    // Notifications
    SearchEntry("Read messages aloud", SettingsPage.NOTIFICATIONS, Icons.Filled.RecordVoiceOver, "Text-to-speech",
        keywords = "tts speak"),
    SearchEntry("Mention sound", SettingsPage.NOTIFICATIONS, Icons.Filled.NotificationsActive,
        keywords = "ping alert"),
    SearchEntry("Allow nudges", SettingsPage.NOTIFICATIONS, Icons.Filled.Vibration,
        "Others can shake your screen and buzz your phone", keywords = "vibrate shake buzz"),
    // Privacy & permissions
    SearchEntry("Microphone", SettingsPage.PRIVACY, Icons.Filled.Mic, keywords = "permission record audio",
        permissionLabel = "Microphone"),
    SearchEntry("Notifications", SettingsPage.PRIVACY, Icons.Filled.Notifications, keywords = "permission",
        permissionLabel = "Notifications"),
    SearchEntry("Bluetooth", SettingsPage.PRIVACY, Icons.Filled.Bluetooth, keywords = "permission nearby devices",
        permissionLabel = "Bluetooth"),
    SearchEntry("Run in background", SettingsPage.PRIVACY, Icons.Filled.BatteryChargingFull,
        keywords = "battery optimization doze permission", permissionLabel = "Run in background"),
    // About
    SearchEntry("Version", SettingsPage.ABOUT, Icons.Filled.Info, keywords = "build"),
    SearchEntry("Open-source licenses", SettingsPage.ABOUT, Icons.AutoMirrored.Filled.Article,
        keywords = "license legal"),
    SearchEntry("Debug overlay", SettingsPage.ABOUT, Icons.Filled.BarChart, "Live packet counts + loss",
        section = "Developer", keywords = "developer stats"),
)

/** Every whitespace-separated token must appear in the entry; title hits rank first. */
internal fun searchSettings(query: String): List<SearchEntry> {
    val tokens = query.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return emptyList()
    return SETTINGS_INDEX.filter { it.matches(tokens) }
        .sortedBy { if (it.title.contains(query.trim(), ignoreCase = true)) 0 else 1 }
}
