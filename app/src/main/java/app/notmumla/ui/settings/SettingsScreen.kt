package app.notmumla.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontFamily
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.BuildConfig
import app.notmumla.audio.TransmissionMode
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.AppSettings
import app.notmumla.data.NoiseSuppression
import app.notmumla.data.ThemeMode
import app.notmumla.ui.SegmentedToggle
import app.notmumla.ui.theme.MumbleTheme
import app.notmumla.vm.IdentityInfo
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private fun OutputRoute.displayLabel(): String = when (this) {
    OutputRoute.PHONE_SPEAKER -> "Phone speaker"
    OutputRoute.WIRED -> "Wired headset"
    OutputRoute.BT_A2DP_HQ -> "Bluetooth (High Quality)"
    OutputRoute.BT_HEADSET_SCO -> "Bluetooth headset"
}

private val BITRATES = listOf(16_000, 24_000, 40_000, 72_000, 96_000, 128_000, 160_000)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    settings: AppSettings,
    inputLevel: Float,
    vadCalibrating: Boolean,
    onCalibrateVad: () -> Unit,
    micTestLabel: String,
    micTestEnabled: Boolean,
    onTestMic: () -> Unit,
    identity: IdentityInfo?,
    availableRoutes: List<OutputRoute>,
    currentRoute: OutputRoute,
    hasEarpiece: Boolean,
    inCall: Boolean,
    onSelectRoute: (OutputRoute) -> Unit,
    onSetRoutePriority: (List<OutputRoute>) -> Unit,
    onToggleRememberRoute: (Boolean) -> Unit,
    onToggleAutoSwitchBluetooth: (Boolean) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetTransmission: (TransmissionMode) -> Unit,
    onSetVad: (Float) -> Unit,
    onToggleAutoSensitivity: (Boolean) -> Unit,
    onSetMicGainDb: (Float) -> Unit,
    onToggleAutoGain: (Boolean) -> Unit,
    onSetNoiseSuppression: (NoiseSuppression) -> Unit,
    onSetNoiseReduction: (Float) -> Unit,
    onToggleEchoCancellation: (Boolean) -> Unit,
    onToggleRawMic: (Boolean) -> Unit,
    onToggleShareMic: (Boolean) -> Unit,
    onToggleAudioLeveling: (Boolean) -> Unit,
    onToggleMediaVolume: (Boolean) -> Unit,
    onSetPhoneEarpiece: (Boolean) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onToggleAvatars: (Boolean) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onToggleMentionSound: (Boolean) -> Unit,
    onToggleAllowNudges: (Boolean) -> Unit,
    onToggleRichComposer: (Boolean) -> Unit,
    onToggleDebugOverlay: (Boolean) -> Unit,
    onSetGamesUnlocked: (Boolean) -> Unit,
    onRegenerateIdentity: () -> Unit,
    onExportIdentity: suspend (password: String) -> ByteArray?,
    onImportIdentity: suspend (bytes: ByteArray, password: String) -> Boolean,
    onOpenLicenses: () -> Unit,
) {
    val c = MumbleTheme.colors
    val isDark = settings.theme == ThemeMode.DARK || (settings.theme == ThemeMode.SYSTEM && c.isDark)

    Column(Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onSurface)
            }
            Text("Settings", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
        }

        Column(Modifier.padding(horizontal = 14.dp, vertical = 4.dp)) {
            ProfileCard()

            SectionLabel("ACCOUNT")
            SettingsGroup {
                NavRow(Icons.Filled.Person, "Profile & status", "Display name, avatar, comment")
                Divider()
                var showIdentity by remember { mutableStateOf(false) }
                NavRow(
                    Icons.Filled.VerifiedUser, "Identity certificate", null,
                    trailing = {
                        Text("Verified", color = c.speaking, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    },
                    onClick = { showIdentity = true },
                )
                if (showIdentity) {
                    IdentityDialog(
                        info = identity,
                        onRegenerate = onRegenerateIdentity,
                        onExport = onExportIdentity,
                        onImport = onImportIdentity,
                        onDismiss = { showIdentity = false },
                    )
                }
            }

            SectionLabel("CONNECTION")
            SettingsGroup {
                ToggleRow(Icons.Filled.Autorenew, "Auto-reconnect", "Rejoin last channel automatically",
                    settings.autoReconnect, onToggleAutoReconnect)
                Divider()
                ValueRow(
                    Icons.Filled.BarChart, "Audio quality", "${settings.audioBitrate / 1000} kbit/s",
                    onClick = {
                        val next = BITRATES[(BITRATES.indexOf(settings.audioBitrate).coerceAtLeast(0) + 1) % BITRATES.size]
                        onSetBitrate(next)
                    },
                )
            }

            SectionLabel("APPEARANCE")
            SettingsGroup {
                ToggleRow(Icons.Filled.DarkMode, "Dark theme", "Force dark theme",
                    isDark, { onSetTheme(if (it) ThemeMode.DARK else ThemeMode.LIGHT) })
                Divider()
                ToggleRow(Icons.Filled.Person, "Show user avatars", null,
                    settings.showAvatars, onToggleAvatars)
                Divider()
                ToggleRow(Icons.Filled.Mic, "Keep screen awake in voice", null,
                    settings.keepScreenAwake, onToggleKeepAwake)
            }

            SectionLabel("AUDIO · INPUT")
            SettingsGroup {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Filled.Mic, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Transmission", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = c.onSurface)
                            Text("How your mic activates", fontSize = 13.sp, color = c.onSurfaceVar)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SegmentedToggle(
                        options = listOf("Push-to-Talk", "Voice Activated"),
                        selectedIndex = if (settings.transmissionMode == TransmissionMode.PTT) 0 else 1,
                        onSelect = { onSetTransmission(if (it == 0) TransmissionMode.PTT else TransmissionMode.VAD) },
                    )
                }
                val vad = settings.transmissionMode == TransmissionMode.VAD
                if (vad) {
                    Divider()
                    ToggleRow(Icons.Filled.GraphicEq, "Automatic sensitivity",
                        "Adjusts to your mic & room", settings.autoSensitivity, onToggleAutoSensitivity)
                }
                Divider()
                InputLevelRow(
                    level = inputLevel,
                    manualThreshold = if (vad && !settings.autoSensitivity) settings.vadSensitivity else null,
                    onThresholdChange = onSetVad,
                    vadMode = vad,
                    calibrating = vadCalibrating,
                    onCalibrate = onCalibrateVad,
                    testLabel = micTestLabel,
                    testEnabled = micTestEnabled,
                    onTest = onTestMic,
                )
                Divider()
                ToggleRow(Icons.Filled.GraphicEq, "Automatic gain control",
                    if (settings.noiseSuppression == NoiseSuppression.AI) "Boosts your voice after AI noise suppression"
                    else "Auto-level your mic", settings.autoGain, onToggleAutoGain)
                if (!settings.autoGain) {
                    Divider()
                    SliderRow(
                        "Microphone gain", "${if (settings.micGainDb >= 0) "+" else ""}${settings.micGainDb.roundToInt()} dB",
                        // -20..+30 dB: extra headroom to boost a quiet mic.
                        value = (settings.micGainDb + 20f) / 50f, range = 0f..1f,
                        onChange = { onSetMicGainDb(it * 50f - 20f) }, icon = Icons.Filled.GraphicEq,
                    )
                }
                Divider()
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Filled.GraphicEq, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Noise suppression", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = c.onSurface)
                            Text("AI uses RNNoise for cleaner voice", fontSize = 13.sp, color = c.onSurfaceVar)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    SegmentedToggle(
                        options = listOf("Off", "Standard", "AI"),
                        selectedIndex = settings.noiseSuppression.ordinal,
                        onSelect = { onSetNoiseSuppression(NoiseSuppression.entries[it]) },
                    )
                }
                if (settings.noiseSuppression == NoiseSuppression.AI) {
                    Divider()
                    SliderRow(
                        "Noise reduction strength", "${(settings.noiseReduction * 100).roundToInt()}%",
                        value = settings.noiseReduction, range = 0f..1f, onChange = onSetNoiseReduction,
                    )
                }
                Divider()
                ToggleRow(Icons.Filled.GraphicEq, "Echo cancellation", null,
                    settings.echoCancellation, onToggleEchoCancellation)
                Divider()
                // AI suppression always captures raw (RNNoise replaces the phone's processing), so
                // the toggle is locked on — shown, not written, so the saved choice returns with Off/Standard.
                val aiNs = settings.noiseSuppression == NoiseSuppression.AI
                ToggleRow(Icons.Filled.Mic, "Raw microphone",
                    if (aiNs) "Always on with AI noise suppression"
                    else "Skip the phone's voice processing — natural, no over-cancelling. No echo " +
                        "cancellation, so best with headphones.",
                    settings.rawMic || aiNs, enabled = !aiNs, onChange = onToggleRawMic)
                Divider()
                ToggleRow(Icons.Filled.Mic, "Share microphone",
                    "Let other apps use the mic while connected. You keep hearing the channel but " +
                        "can't talk until they're done.",
                    settings.shareMic, onChange = onToggleShareMic)
            }

            SectionLabel("AUDIO · OUTPUT")
            SettingsGroup {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Icon(Icons.Filled.Bluetooth, null, tint = c.onSurfaceVar,
                            modifier = Modifier.size(22.dp))
                        Text("Output device", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = c.onSurface)
                    }
                    Text(
                        if (settings.rememberLastRoute) "Starts on the output you last picked"
                        else "Tried top to bottom when you connect",
                        fontSize = 13.sp, color = c.onSurfaceVar, modifier = Modifier.padding(start = 36.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    val order = settings.routePriority
                    val reorderable = !settings.rememberLastRoute
                    // Out of a call the priority list decides the route, so a tap would just be
                    // overridden on connect — only allow picking when it takes effect.
                    val pickable = inCall || settings.rememberLastRoute
                    order.forEachIndexed { i, route ->
                        val available = route in availableRoutes
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = available && pickable) { onSelectRoute(route) }
                                .padding(vertical = 2.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${i + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = c.onSurfaceVar.copy(alpha = if (reorderable) 1f else 0.4f),
                                modifier = Modifier.width(22.dp),
                            )
                            Text(
                                route.displayLabel() + if (!available) " (unavailable)" else "",
                                fontSize = 14.sp,
                                color = if (available) c.onSurface else c.onSurfaceVar.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1f),
                            )
                            MoveButton(Icons.Filled.KeyboardArrowUp, "Move ${route.displayLabel()} up",
                                enabled = reorderable && i > 0) {
                                onSetRoutePriority(order.toMutableList().apply { add(i - 1, removeAt(i)) })
                            }
                            MoveButton(Icons.Filled.KeyboardArrowDown, "Move ${route.displayLabel()} down",
                                enabled = reorderable && i < order.lastIndex) {
                                onSetRoutePriority(order.toMutableList().apply { add(i + 1, removeAt(i)) })
                            }
                            Spacer(Modifier.width(8.dp))
                            RadioDot(selected = route == currentRoute)
                        }
                    }
                    if (hasEarpiece) {
                        Text("Phone speaker plays through", fontSize = 13.sp, color = c.onSurfaceVar,
                            modifier = Modifier.padding(top = 10.dp, bottom = 6.dp, start = 4.dp))
                        SegmentedToggle(
                            options = listOf("Speakerphone", "Ear speaker"),
                            selectedIndex = if (settings.phoneEarpiece) 1 else 0,
                            onSelect = { onSetPhoneEarpiece(it == 1) },
                        )
                        if (settings.phoneEarpiece) {
                            Text(
                                "Hold the phone to your ear. Uses call volume, and the screen turns " +
                                    "off near your face.",
                                fontSize = 12.sp, color = c.onSurfaceVar,
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                            )
                        }
                    }
                    if (currentRoute == OutputRoute.BT_A2DP_HQ) {
                        Text(
                            "High-quality audio to your Bluetooth headphones, mic from the phone. " +
                                "Higher latency — push-to-talk recommended.",
                            fontSize = 12.sp, color = c.onSurfaceVar,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                }
                Divider()
                ToggleRow(Icons.Filled.Autorenew, "Remember last output",
                    "Start on the output you last picked instead of the priority order",
                    settings.rememberLastRoute, onToggleRememberRoute)
                Divider()
                ToggleRow(Icons.Filled.Bluetooth, "Switch to Bluetooth when connected",
                    "Move audio to a Bluetooth device as soon as it connects during a call",
                    settings.autoSwitchBluetooth, onToggleAutoSwitchBluetooth)
                Divider()
                ToggleRow(Icons.AutoMirrored.Filled.VolumeUp, "Audio leveling",
                    "Even out quiet and loud talkers to a consistent volume",
                    settings.audioLeveling, onToggleAudioLeveling)
                Divider()
                ToggleRow(Icons.AutoMirrored.Filled.VolumeUp, "Use media volume",
                    "Volume buttons set media volume, shared with music and videos. Turn off if " +
                        "others hear an echo of themselves on speaker. Bluetooth headsets always " +
                        "use call volume.",
                    settings.mediaVolume, onToggleMediaVolume)
            }

            SectionLabel("CHAT")
            SettingsGroup {
                ToggleRow(Icons.Filled.FormatBold, "Formatting toolbar",
                    if (settings.richComposer) "Bold, italic, color… buttons above the message box"
                    else "Off: format by typing Markdown (**bold**, *italic*, `code`)",
                    settings.richComposer, onToggleRichComposer)
            }

            SectionLabel("NOTIFICATIONS")
            SettingsGroup {
                ToggleRow(null, "Read messages aloud", "Text-to-speech",
                    settings.ttsReadAloud, onToggleTts)
                Divider()
                ToggleRow(null, "Mention sound", null, settings.mentionSound, onToggleMentionSound)
                Divider()
                ToggleRow(null, "Allow nudges", "Other Grumbla users can shake your screen and buzz your phone",
                    settings.allowNudges, onToggleAllowNudges)
            }

            PermissionsSection()

            SectionLabel("DEVELOPER")
            SettingsGroup {
                ToggleRow(Icons.Filled.BarChart, "Debug overlay",
                    "Live packet counts + loss on the voice screen",
                    settings.debugOverlay, onToggleDebugOverlay)
            }

            SectionLabel("ABOUT")
            SettingsGroup {
                // Easter egg, Android-style: seven taps on the version toggle the games.
                val context = androidx.compose.ui.platform.LocalContext.current
                var versionTaps by remember { mutableStateOf(0) }
                var tapToast by remember { mutableStateOf<android.widget.Toast?>(null) }
                ValueRow(Icons.Filled.VerifiedUser, "Version",
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", showChevron = false,
                    onClick = {
                        versionTaps++
                        val left = 7 - versionTaps
                        val text = when {
                            left <= 0 -> {
                                versionTaps = 0
                                onSetGamesUnlocked(!settings.gamesUnlocked)
                                if (settings.gamesUnlocked) "Games hidden"
                                else "Games unlocked: long-press a user or your channel"
                            }
                            left <= 3 -> "$left more…"
                            else -> null
                        }
                        // Replace the previous toast so fast taps don't queue a backlog of them.
                        tapToast?.cancel()
                        tapToast = text?.let {
                            android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).also { t -> t.show() }
                        }
                    })
                Divider()
                NavRow(Icons.Filled.BarChart, "Open-source licenses", null, onClick = onOpenLicenses)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * Live meter of the mic level, always visible in Settings. It reads the level computed in the
 * capture loop *after* noise filtering (hardware NS + RNNoise) and gain, so it reflects exactly what
 * goes out. Green while healthy, amber loud, red = clipping risk. Moves only while connected.
 *
 * When [threshold] is non-null (Voice-Activated mode) it also overlays the VAD threshold marker and
 * a sensitivity slider, so the same bar doubles as VAD calibration — no second meter needed.
 */
// Meter/threshold operate on a dB scale so quiet levels are visible and the threshold sits mid-range.
private const val METER_MIN_DB = -60f
private const val METER_MAX_DB = 0f

/** RMS (0..1) → meter fraction (0..1) on a dB scale. */
private fun rmsToMeterFrac(rms: Float): Float {
    val db = if (rms > 1e-5f) 20f * log10(rms) else METER_MIN_DB
    return ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
}

@Composable
private fun InputLevelRow(
    level: Float,
    manualThreshold: Float?, // non-null → manual VAD: show threshold marker + slider + Auto-set
    onThresholdChange: (Float) -> Unit,
    vadMode: Boolean = false, // VAD active (auto or manual): show Test + the right hint
    calibrating: Boolean = false,
    onCalibrate: () -> Unit = {},
    testLabel: String = "Test",
    testEnabled: Boolean = false,
    onTest: () -> Unit = {},
) {
    val c = MumbleTheme.colors
    val frac = rmsToMeterFrac(level)
    val barColor = when {
        frac > 0.9f -> c.muted
        frac > 0.75f -> c.afk
        else -> c.speaking
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(Icons.Filled.GraphicEq, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text("Input level", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                Text(
                    when {
                        !vadMode -> "Live, after noise filtering & gain — keep peaks out of the red"
                        calibrating -> "Listening… talk normally for a few seconds"
                        manualThreshold == null -> "Automatic — sensitivity adapts to your mic & room"
                        else -> "Live, after filtering — set the line just above the bar's resting level"
                    },
                    fontSize = 12.sp, color = if (calibrating) c.primary else c.onSurfaceVar,
                )
            }
            if (manualThreshold != null) {
                TextButton(onClick = onCalibrate, enabled = !calibrating) {
                    Text(if (calibrating) "…" else "Auto-set")
                }
            }
            if (vadMode) {
                TextButton(onClick = onTest, enabled = testEnabled) { Text(testLabel) }
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfHighest)) {
            Box(Modifier.fillMaxWidth(frac).height(14.dp).clip(RoundedCornerShape(7.dp)).background(barColor))
            if (manualThreshold != null) {
                Box(Modifier.fillMaxWidth(rmsToMeterFrac(manualThreshold)).height(14.dp),
                    contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.width(3.dp).height(20.dp).background(c.primary))
                }
            }
        }
        if (manualThreshold != null) {
            // Slider is on the same dB scale as the meter, so the line lands where you see it.
            Slider(
                value = rmsToMeterFrac(manualThreshold),
                onValueChange = { p ->
                    val db = METER_MIN_DB + p * (METER_MAX_DB - METER_MIN_DB)
                    onThresholdChange(10f.pow(db / 20f))
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = c.primary, activeTrackColor = c.primary.copy(alpha = 0.4f),
                    inactiveTrackColor = c.surfHighest,
                ),
            )
        }
    }
}

/* ---------------- identity ---------------- */

@Composable
private fun IdentityDialog(
    info: IdentityInfo?,
    onRegenerate: () -> Unit,
    onExport: suspend (password: String) -> ByteArray?,
    onImport: suspend (bytes: ByteArray, password: String) -> Boolean,
    onDismiss: () -> Unit,
) {
    val c = MumbleTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun toast(msg: String) = android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

    var confirmRegen by remember { mutableStateOf(false) }
    var pendingExportPw by remember { mutableStateOf<String?>(null) }
    var pwMode by remember { mutableStateOf<String?>(null) } // "export" | "import"
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-pkcs12"),
    ) { uri ->
        val pw = pendingExportPw
        pendingExportPw = null
        if (uri != null && pw != null) scope.launch {
            val bytes = onExport(pw)
            val ok = bytes != null && withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) } }.isSuccess
            }
            toast(if (ok) "Identity exported" else "Export failed")
        }
    }
    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) { importUri = uri; pwMode = "import" } }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfContainer,
        title = { Text("Identity certificate", fontWeight = FontWeight.Bold, color = c.onSurface) },
        text = {
            if (info == null) {
                Text("Generating…", color = c.onSurfaceVar)
            } else {
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        IdentityField("Name", info.commonName, mono = false)
                        IdentityField("Valid", "${info.issued} – ${info.expires}", mono = false)
                        IdentityField("SHA-256 fingerprint", info.sha256, mono = true)
                        IdentityField("SHA-1 (server identifies you by this)", info.sha1, mono = true)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { importPicker.launch(arrayOf("*/*")) }) { Text("Import") }
                TextButton(onClick = { pwMode = "export" }) { Text("Export") }
                TextButton(onClick = { confirmRegen = true }) { Text("Regenerate") }
            }
        },
    )

    if (confirmRegen) {
        AlertDialog(
            onDismissRequest = { confirmRegen = false },
            containerColor = c.surfContainer,
            title = { Text("Regenerate identity?", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = {
                Text(
                    "This creates a brand-new certificate. Servers that recognised your old identity " +
                        "(registrations, permissions) will see you as a new user.",
                    color = c.onSurfaceVar,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmRegen = false; onRegenerate(); toast("New identity generated") }) {
                    Text("Regenerate", color = c.afk)
                }
            },
            dismissButton = { TextButton(onClick = { confirmRegen = false }) { Text("Cancel") } },
        )
    }

    pwMode?.let { mode ->
        PasswordDialog(
            title = if (mode == "export") "Set a password for the file" else "Enter the file's password",
            onConfirm = { pw ->
                pwMode = null
                if (mode == "export") {
                    pendingExportPw = pw
                    exportPicker.launch("identity.p12")
                } else {
                    val uri = importUri
                    importUri = null
                    if (uri != null) scope.launch {
                        val bytes = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                        }
                        val ok = bytes != null && onImport(bytes, pw)
                        toast(if (ok) "Identity imported" else "Import failed — wrong password or file")
                    }
                }
            },
            onDismiss = { pwMode = null; importUri = null },
        )
    }
}

@Composable
private fun PasswordDialog(title: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val c = MumbleTheme.colors
    var pw by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfContainer,
        title = { Text(title, fontWeight = FontWeight.Bold, color = c.onSurface) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = pw, onValueChange = { pw = it }, singleLine = true,
                label = { Text("Password") },
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(pw) }, enabled = pw.isNotEmpty()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun IdentityField(label: String, value: String, mono: Boolean) {
    val c = MumbleTheme.colors
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVar)
        Text(
            value, fontSize = 13.sp, color = c.onSurface,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
        )
    }
}

/* ---------------- permissions ---------------- */

private data class PermSpec(val label: String, val why: String, val icon: ImageVector, val permission: String)

/**
 * Shows the status of each runtime permission the app needs and lets the user (re)grant ones they
 * missed or denied. Tapping a missing permission re-requests it; if it was permanently denied (the
 * system won't show the dialog), it sends the user to the app's settings page instead.
 */
@Composable
private fun PermissionsSection() {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    var refresh by remember { mutableIntStateOf(0) }

    // Re-check whenever the screen resumes (e.g. coming back from system app settings).
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++
        val p = pending
        pending = null
        // Permanently denied → no system dialog was shown; route the user to app settings.
        if (!granted && p != null && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, p)
        ) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    val specs = buildList {
        add(PermSpec("Microphone", "Required to transmit your voice", Icons.Filled.Mic,
            android.Manifest.permission.RECORD_AUDIO))
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            add(PermSpec("Notifications", "Show the ongoing call controls", Icons.Filled.Notifications,
                android.Manifest.permission.POST_NOTIFICATIONS))
        }
        add(PermSpec("Bluetooth", "Use Bluetooth headsets for audio", Icons.Filled.Bluetooth,
            android.Manifest.permission.BLUETOOTH_CONNECT))
    }

    SectionLabel("PERMISSIONS")
    SettingsGroup {
        specs.forEachIndexed { i, spec ->
            if (i > 0) Divider()
            val granted = remember(refresh, spec.permission) {
                ContextCompat.checkSelfPermission(ctx, spec.permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            PermissionRow(spec.icon, spec.label, spec.why, granted) {
                pending = spec.permission
                launcher.launch(spec.permission)
            }
        }

        // Battery-optimization exemption — a special access, not a runtime permission. Needed so the
        // foreground voice service isn't killed in the background / under Doze.
        Divider()
        val pm = remember { ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager }
        val unrestricted = remember(refresh) { pm.isIgnoringBatteryOptimizations(ctx.packageName) }
        PermissionRow(
            Icons.Filled.Autorenew, "Run in background",
            "Keep the call alive when the screen is off", unrestricted,
        ) {
            runCatching {
                ctx.startActivity(
                    android.content.Intent(
                        android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        android.net.Uri.parse("package:${ctx.packageName}"),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(icon: ImageVector, label: String, why: String, granted: Boolean, onTap: () -> Unit) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .let { if (granted) it else it.clickable(onClick = onTap) }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            Text(
                if (granted) "Granted" else "$why · tap to grant",
                fontSize = 12.sp, color = c.onSurfaceVar,
            )
        }
        Icon(
            if (granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = if (granted) c.speaking else c.afk,
            modifier = Modifier.size(22.dp),
        )
    }
}

/* ---------------- building blocks ---------------- */

@Composable
private fun ProfileCard() {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)
            .clip(RoundedCornerShape(20.dp)).background(c.surfContainer).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(54.dp).clip(CircleShape).background(c.primary),
            contentAlignment = Alignment.Center) {
            Text("YO", color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Column(Modifier.weight(1f)) {
            Text("user", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
            Text("Connected", fontSize = 13.sp, color = c.onSurfaceVar)
        }
        Chevron()
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, color = MumbleTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp,
        letterSpacing = 0.05.sp, modifier = Modifier.padding(top = 18.dp, bottom = 7.dp, start = 8.dp))
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MumbleTheme.colors.surfContainer),
    ) { content() }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MumbleTheme.colors.outlineVariant))
}

@Composable
private fun RowBase(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = c.onSurfaceVar)
        }
        trailing()
    }
}

@Composable
private fun NavRow(icon: ImageVector, title: String, subtitle: String?,
                   trailing: @Composable () -> Unit = {}, onClick: () -> Unit = {}) {
    RowBase(icon, title, subtitle, trailing = { trailing(); Chevron() }, onClick = onClick)
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String,
                     showChevron: Boolean = true, onClick: (() -> Unit)? = null) {
    val c = MumbleTheme.colors
    RowBase(icon, title, null, trailing = {
        Text(value, fontSize = 14.sp, color = c.onSurfaceVar)
        if (showChevron) Chevron()
    }, onClick = onClick ?: if (showChevron) ({}) else null)
}

@Composable
private fun ToggleRow(icon: ImageVector?, title: String, subtitle: String?,
                      checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    RowBase(icon, title, subtitle, trailing = { ToggleSwitch(checked, onChange, enabled) },
        onClick = { onChange(!checked) }, enabled = enabled)
}

@Composable
private fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = MumbleTheme.colors
    Box(
        Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (checked) c.primary else c.surfHighest)
            .clickable(enabled = enabled) { onChange(!checked) },
    ) {
        Box(
            Modifier.padding(start = if (checked) 23.dp else 5.dp, top = if (checked) 3.dp else 5.dp)
                .size(if (checked) 20.dp else 14.dp).clip(CircleShape)
                .background(if (checked) c.onPrimary else c.outline),
        )
    }
}

@Composable
private fun SliderRow(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    icon: ImageVector? = null,
) {
    val c = MumbleTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(14.dp))
            }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface,
                modifier = Modifier.weight(1f))
            Text(valueText, fontSize = 13.sp, color = c.onSurfaceVar)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive), onValueChange = onChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = c.primary, activeTrackColor = c.primary,
                inactiveTrackColor = c.surfHighest,
            ),
        )
    }
}

/**
 * VAD calibration: a live mic-level meter with a draggable threshold. Speak and set the threshold
 * just above the bar's resting (background-noise) level; the bar turns green when you're above it
 * and transmitting. Far clearer than a blind percentage.
 */
@Composable
private fun RadioDot(selected: Boolean) {
    val c = MumbleTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape)
            .background(if (selected) c.primary else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(8.dp).clip(CircleShape).background(c.onPrimary))
        else Box(Modifier.size(18.dp).clip(CircleShape).background(c.outline.copy(alpha = 0.3f)))
    }
}

@Composable
private fun MoveButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MumbleTheme.colors
    Box(
        Modifier.size(36.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = c.onSurfaceVar.copy(alpha = if (enabled) 1f else 0.25f),
            modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
        tint = MumbleTheme.colors.onSurfaceVar.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
}
