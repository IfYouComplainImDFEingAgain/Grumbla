package app.notmumla.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.filled.HowToReg
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ScreenLockPortrait
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SettingsBrightness
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.notmumla.BuildConfig
import app.notmumla.audio.TransmissionMode
import app.notmumla.audio.routing.CallModeOverride
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.AppSettings
import app.notmumla.data.FloatingPttMode
import app.notmumla.data.NoiseSuppression
import app.notmumla.data.ThemeMode
import app.notmumla.ui.theme.MumbleTheme
import app.notmumla.vm.IdentityInfo
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

internal fun OutputRoute.displayLabel(): String = when (this) {
    OutputRoute.PHONE_SPEAKER -> "Phone speaker"
    OutputRoute.WIRED -> "Wired headset"
    OutputRoute.BT_A2DP_HQ -> "Bluetooth (High Quality)"
    OutputRoute.BT_HEADSET_SCO -> "Bluetooth headset"
}

internal fun OutputRoute.icon(): ImageVector = when (this) {
    OutputRoute.PHONE_SPEAKER -> Icons.Filled.PhoneAndroid
    OutputRoute.WIRED -> Icons.Filled.Headset
    OutputRoute.BT_A2DP_HQ -> Icons.Filled.BluetoothAudio
    OutputRoute.BT_HEADSET_SCO -> Icons.Filled.HeadsetMic
}

internal val BITRATES = listOf(16_000, 24_000, 40_000, 72_000, 96_000, 128_000, 160_000)

/* ---------------- 03.1 Account ---------------- */

@Composable
internal fun AccountPage(
    onBack: () -> Unit,
    profile: ProfileInfo,
    account: AccountState,
    identity: IdentityInfo?,
    onOpenProfile: () -> Unit,
    onRegister: () -> Unit,
    onRegenerateIdentity: () -> Unit,
    onExportIdentity: suspend (password: String) -> ByteArray?,
    onImportIdentity: suspend (bytes: ByteArray, password: String) -> Boolean,
) {
    val c = MumbleTheme.colors
    SettingsPageScaffold("Account", onBack) {
        Column(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ProfileAvatar(profile.initials, 84.dp, profile.online, ringColor = c.surface, image = profile.avatar)
            Text(profile.name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
            Text(profile.status, fontSize = 13.sp, color = c.onSurfaceVar)
        }
        SectionLabel("ACCOUNT")
        SettingsGroup {
            NavRow(Icons.Filled.Person, "Profile", "Avatar, comment, default username", onClick = onOpenProfile)
            Divider()
            var showIdentity by remember { mutableStateOf(false) }
            NavRow(
                Icons.Filled.VerifiedUser, "Identity certificate",
                identity?.let { "${it.commonName} · ${it.sha256.take(11)}…" } ?: "Import, export or regenerate",
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
            Divider()
            var confirmRegister by remember { mutableStateOf(false) }
            val canRegister = account.connected && !account.registered
            RowBase(
                Icons.Filled.HowToReg, "Server registration",
                when {
                    !account.connected -> "Connect to a server to register on it"
                    account.registered -> "${account.name} is registered on ${account.serverLabel}"
                    else -> "Guest on ${account.serverLabel}. Register to keep ${account.name}"
                },
                trailing = {
                    when {
                        account.connected && account.registered ->
                            Text("Registered", color = c.speaking, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        canRegister -> PillButton("Register", { confirmRegister = true }, tonal = true)
                    }
                },
                onClick = if (canRegister) ({ confirmRegister = true }) else null,
            )
            if (confirmRegister) {
                AlertDialog(
                    onDismissRequest = { confirmRegister = false },
                    title = { Text("Register as ${account.name}?") },
                    text = {
                        Text(
                            "This binds the name ${account.name} on ${account.serverLabel} to your identity " +
                                "certificate. It can't be undone from the app and the name can't be changed " +
                                "afterwards. Export a backup of the certificate: without it you lose the account.",
                        )
                    },
                    confirmButton = {
                        TextButton({ confirmRegister = false; onRegister() }) { Text("Register") }
                    },
                    dismissButton = { TextButton({ confirmRegister = false }) { Text("Cancel") } },
                )
            }
        }
        Text(
            "Mumble accounts are per server: your certificate is your login, and a registered name, " +
                "comment and avatar are kept by that server.",
            fontSize = 12.sp, color = c.onSurfaceVar,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp),
        )
    }
}

/* ---------------- 03.1a Profile ---------------- */

@Composable
internal fun ProfilePage(
    onBack: () -> Unit,
    profile: ProfileInfo,
    comment: String,
    defaultUsername: String,
    onSaveComment: (String) -> Unit,
    onPickAvatar: suspend (android.net.Uri) -> Boolean,
    onRemoveAvatar: () -> Unit,
    onSetDefaultUsername: (String) -> Unit,
) {
    val c = MumbleTheme.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            if (!onPickAvatar(uri)) {
                android.widget.Toast.makeText(ctx, "Couldn't read that image", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }
    SettingsPageScaffold("Profile", onBack) {
        SectionLabel("AVATAR")
        SettingsGroup {
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                ProfileAvatar(profile.initials, 72.dp, online = false, ringColor = c.surfContainer, image = profile.avatar)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (profile.avatar == null) "Choose image" else "Change", {
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, tonal = true)
                        if (profile.avatar != null) PillButton("Remove", onRemoveAvatar, tonal = false)
                    }
                    Text("Cropped square, 256 px", fontSize = 12.sp, color = c.onSurfaceVar)
                }
            }
        }

        SectionLabel("COMMENT")
        var draft by rememberSaveable(comment) { mutableStateOf(comment) }
        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileTextField(draft, "Tell people something about yourself", singleLine = false) { draft = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Shown when others open your user info.",
                        fontSize = 12.sp, color = c.onSurfaceVar, modifier = Modifier.weight(1f),
                    )
                    PillButton("Save", { onSaveComment(draft) }, tonal = true, enabled = draft.trim() != comment)
                }
            }
        }

        SectionLabel("NEW SERVERS")
        SettingsGroup {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileTextField(defaultUsername, "user", singleLine = true, onChange = onSetDefaultUsername)
                Text(
                    "Pre-filled username when adding a server. Each saved server keeps its own name.",
                    fontSize = 12.sp, color = c.onSurfaceVar,
                )
            }
        }
        Text(
            "Your avatar and comment are sent to every server you join. A server keeps them only for " +
                "registered users, so Grumbla sends them again each time you connect.",
            fontSize = 12.sp, color = c.onSurfaceVar,
            modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 10.dp),
        )
    }
}

@Composable
private fun ProfileTextField(value: String, placeholder: String, singleLine: Boolean, onChange: (String) -> Unit) {
    val c = MumbleTheme.colors
    Box(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
            .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            singleLine = singleLine,
            minLines = if (singleLine) 1 else 3,
            maxLines = if (singleLine) 1 else 8,
            textStyle = TextStyle(color = c.onSurface, fontSize = 15.sp),
            cursorBrush = SolidColor(c.primary),
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(placeholder, color = c.onSurfaceVar.copy(alpha = 0.6f), fontSize = 15.sp)
                inner()
            },
        )
    }
}

/* ---------------- 03.2 Connection ---------------- */

@Composable
internal fun ConnectionPage(
    onBack: () -> Unit,
    settings: AppSettings,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onSetBitrate: (Int) -> Unit,
) {
    val c = MumbleTheme.colors
    SettingsPageScaffold("Connection", onBack) {
        GroupGap()
        SettingsGroup {
            ToggleRow(Icons.Filled.Autorenew, "Auto-reconnect", "Rejoin last channel automatically",
                settings.autoReconnect, onToggleAutoReconnect)
            Divider()
            val idx = BITRATES.indexOf(settings.audioBitrate).let { if (it < 0) BITRATES.indexOfFirst { b -> b >= settings.audioBitrate }.coerceAtLeast(0) else it }
            StackedRow(
                Icons.Filled.SignalCellularAlt, "Audio quality", "Higher uses more data",
                trailing = {
                    Text("${settings.audioBitrate / 1000} kbit/s", fontSize = 13.sp, color = c.onSurfaceVar,
                        fontFamily = FontFamily.Monospace)
                },
            ) {
                Column(Modifier.padding(start = 36.dp)) {
                    DesignSlider(
                        value = idx / (BITRATES.size - 1).toFloat(),
                        onChange = { f ->
                            val next = BITRATES[(f * (BITRATES.size - 1)).roundToInt()]
                            if (next != settings.audioBitrate) onSetBitrate(next)
                        },
                        modifier = Modifier.padding(horizontal = 9.dp),
                        steps = BITRATES.size,
                        thumbSize = 20.dp,
                    )
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        BITRATES.forEachIndexed { i, b ->
                            Text(
                                "${b / 1000}", fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                                color = if (i == idx) c.primary else c.onSurfaceVar,
                                fontWeight = if (i == idx) FontWeight.Bold else FontWeight.Normal,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.width(26.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ---------------- 03.3 Appearance ---------------- */

@Composable
internal fun AppearancePage(
    onBack: () -> Unit,
    settings: AppSettings,
    onSetTheme: (ThemeMode) -> Unit,
    onToggleAvatars: (Boolean) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
) {
    SettingsPageScaffold("Appearance", onBack) {
        GroupGap()
        SettingsGroup {
            StackedRow(
                when (settings.theme) {
                    ThemeMode.LIGHT -> Icons.Filled.LightMode
                    ThemeMode.DARK -> Icons.Filled.DarkMode
                    ThemeMode.SYSTEM -> Icons.Filled.SettingsBrightness
                },
                "Theme",
                when (settings.theme) {
                    ThemeMode.LIGHT -> "Always light"
                    ThemeMode.DARK -> "Always dark"
                    ThemeMode.SYSTEM -> "System follows your phone’s setting"
                },
            ) {
                val modes = listOf(ThemeMode.LIGHT, ThemeMode.DARK, ThemeMode.SYSTEM)
                SettingsSegmented(listOf("Light", "Dark", "System"), modes.indexOf(settings.theme),
                    { onSetTheme(modes[it]) })
            }
        }
        GroupGap()
        SettingsGroup {
            ToggleRow(Icons.Filled.AccountCircle, "Show user avatars", null, settings.showAvatars, onToggleAvatars)
            Divider()
            ToggleRow(Icons.Filled.ScreenLockPortrait, "Keep screen awake in voice", null,
                settings.keepScreenAwake, onToggleKeepAwake)
        }
    }
}

/* ---------------- 03.4 Audio input ---------------- */

@Composable
internal fun AudioInputPage(
    onBack: () -> Unit,
    settings: AppSettings,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    inputLevel: Float,
    vadCalibrating: Boolean,
    onCalibrateVad: () -> Unit,
    micTestLabel: String,
    micTestEnabled: Boolean,
    onTestMic: () -> Unit,
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
    onToggleMuteOutputWhilePtt: (Boolean) -> Unit,
    onToggleMuteOutputLoudspeakerOnly: (Boolean) -> Unit,
    onToggleFloatingPtt: (Boolean) -> Unit,
    onSetFloatingPttMode: (FloatingPttMode) -> Unit,
) {
    val c = MumbleTheme.colors
    val vad = settings.transmissionMode == TransmissionMode.VAD
    SettingsPageScaffold("Audio input", onBack) {
        SectionLabel("TRANSMISSION")
        SettingsGroup {
            StackedRow(Icons.Filled.Mic, "Transmission", "How your mic activates") {
                SettingsSegmented(
                    listOf("Push-to-Talk", "Voice Activated"), if (vad) 1 else 0,
                    { onSetTransmission(if (it == 0) TransmissionMode.PTT else TransmissionMode.VAD) },
                )
                if (vad) {
                    Row(Modifier.padding(start = 36.dp, top = 4.dp).clickable { onToggleAutoSensitivity(!settings.autoSensitivity) },
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic sensitivity", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = c.onSurface)
                            Text("Adjusts to your mic & room", fontSize = 13.sp, color = c.onSurfaceVar)
                        }
                        ToggleSwitch(settings.autoSensitivity, onToggleAutoSensitivity)
                    }
                }
            }
            if (!vad) {
                Divider()
                ToggleRow(Icons.AutoMirrored.Filled.VolumeOff, "Mute speakers while talking",
                    "Silence incoming audio while push-to-talk is held",
                    settings.muteOutputWhilePtt, onToggleMuteOutputWhilePtt)
                if (settings.muteOutputWhilePtt) {
                    Divider()
                    ToggleRow(null, "Only on loudspeaker",
                        "Keep listening on headphones, Bluetooth and the ear speaker",
                        settings.muteOutputLoudspeakerOnly, onToggleMuteOutputLoudspeakerOnly, indent = true)
                }
                Divider()
                FloatingPttRows(settings, onToggleFloatingPtt, onSetFloatingPttMode)
            }
        }

        SectionLabel("INPUT LEVEL")
        SettingsGroup {
            InputLevelBlock(
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
        }

        SectionLabel("PROCESSING")
        SettingsGroup {
            StackedRow(Icons.Filled.GraphicEq, "Noise suppression", "AI uses RNNoise for cleaner voice") {
                SettingsSegmented(listOf("Off", "Standard", "AI"), settings.noiseSuppression.ordinal,
                    { onSetNoiseSuppression(NoiseSuppression.entries[it]) })
                if (settings.noiseSuppression == NoiseSuppression.AI) {
                    ValueLine("Reduction strength", "${(settings.noiseReduction * 100).roundToInt()}%",
                        Modifier.padding(start = 36.dp, top = 4.dp))
                    DesignSlider(settings.noiseReduction, onSetNoiseReduction)
                }
            }
            Divider()
            ToggleRow(Icons.Filled.GraphicEq, "Automatic gain control",
                if (settings.noiseSuppression == NoiseSuppression.AI) "Runs after AI noise suppression"
                else "Auto-level your mic", settings.autoGain, onToggleAutoGain)
            Divider()
            ToggleRow(Icons.Filled.GraphicEq, "Echo cancellation", null,
                settings.echoCancellation, onToggleEchoCancellation)
        }

        AdvancedSection(advancedExpanded, onAdvancedExpandedChange, "Raw microphone, share microphone, mic gain") {
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
            Divider()
            if (settings.autoGain) {
                RowBase(Icons.Filled.GraphicEq, "Microphone gain", "Set by automatic gain control",
                    trailing = {}, enabled = false)
            } else {
                StackedRow(Icons.Filled.GraphicEq, "Microphone gain", null, trailing = {
                    Text("${if (settings.micGainDb >= 0) "+" else ""}${settings.micGainDb.roundToInt()} dB",
                        fontSize = 13.sp, color = c.onSurfaceVar, fontFamily = FontFamily.Monospace)
                }) {
                    // -20..+30 dB: extra headroom to boost a quiet mic.
                    DesignSlider((settings.micGainDb + 20f) / 50f, { onSetMicGainDb(it * 50f - 20f) })
                }
            }
        }
    }
}

/*
 * Live meter of the mic level. It reads the level computed in the capture loop *after* noise
 * filtering (hardware NS + RNNoise) and gain, so it reflects exactly what goes out. Green while
 * healthy, amber loud, red = clipping risk. Moves only while connected or during the preview.
 *
 * When [manualThreshold] is non-null (Voice-Activated, manual sensitivity) it also overlays the VAD
 * threshold marker and a sensitivity slider, so the same bar doubles as VAD calibration.
 */
// Meter/threshold operate on a dB scale so quiet levels are visible and the threshold sits mid-range.
private const val METER_MIN_DB = -60f
private const val METER_MAX_DB = 0f
private const val METER_SEGMENTS = 28

/** RMS (0..1) → meter fraction (0..1) on a dB scale. */
private fun rmsToMeterFrac(rms: Float): Float {
    val db = if (rms > 1e-5f) 20f * log10(rms) else METER_MIN_DB
    return ((db - METER_MIN_DB) / (METER_MAX_DB - METER_MIN_DB)).coerceIn(0f, 1f)
}

@Composable
private fun InputLevelBlock(
    level: Float,
    manualThreshold: Float?, // non-null → manual VAD: show threshold marker + slider + Auto-set
    onThresholdChange: (Float) -> Unit,
    vadMode: Boolean, // VAD active (auto or manual): show Test
    calibrating: Boolean,
    onCalibrate: () -> Unit,
    testLabel: String,
    testEnabled: Boolean,
    onTest: () -> Unit,
) {
    val c = MumbleTheme.colors
    val frac = rmsToMeterFrac(level)
    StackedRow(
        Icons.Filled.BarChart, "Input level",
        when {
            calibrating -> "Listening… talk normally for a few seconds"
            !vadMode -> "After noise filtering & gain — keep peaks out of the red"
            manualThreshold == null -> "Sensitivity adapts to your mic & room"
            else -> "Set the line just above the bar's resting level"
        },
        trailing = if (vadMode) ({ PillButton(testLabel, onTest, tonal = false, enabled = testEnabled) }) else null,
    ) {
        Box(Modifier.padding(start = 36.dp).fillMaxWidth().height(22.dp), contentAlignment = Alignment.CenterStart) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                for (i in 0 until METER_SEGMENTS) {
                    val pos = (i + 1) / METER_SEGMENTS.toFloat()
                    val lit = pos <= frac + 0.5f / METER_SEGMENTS
                    val color = when {
                        !lit -> c.surfHighest
                        pos > 0.9f -> c.muted
                        pos > 0.75f -> c.afk
                        else -> c.speaking
                    }
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(2.dp)).background(color))
                }
            }
            if (manualThreshold != null) {
                Box(Modifier.fillMaxWidth(rmsToMeterFrac(manualThreshold)).height(22.dp),
                    contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.width(2.dp).height(22.dp).clip(RoundedCornerShape(1.dp)).background(c.onSurface))
                }
            }
        }
        if (manualThreshold != null) {
            val p = rmsToMeterFrac(manualThreshold)
            val db = METER_MIN_DB + p * (METER_MAX_DB - METER_MIN_DB)
            ValueLine("Threshold", "${if (db < 0) "−" else ""}${kotlin.math.abs(db).roundToInt()} dB",
                Modifier.padding(start = 36.dp, top = 6.dp))
            // Slider is on the same dB scale as the meter, so the line lands where you see it.
            DesignSlider(p, { f ->
                val d = METER_MIN_DB + f * (METER_MAX_DB - METER_MIN_DB)
                onThresholdChange(10f.pow(d / 20f))
            })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PillButton(if (calibrating) "Listening…" else "Auto-set", onCalibrate, tonal = true,
                    enabled = !calibrating)
            }
        }
    }
}

/* ---------------- 03.5 Audio output ---------------- */

@Composable
internal fun AudioOutputPage(
    onBack: () -> Unit,
    settings: AppSettings,
    advancedExpanded: Boolean,
    onAdvancedExpandedChange: (Boolean) -> Unit,
    availableRoutes: List<OutputRoute>,
    currentRoute: OutputRoute,
    hasEarpiece: Boolean,
    inCall: Boolean,
    onSelectRoute: (OutputRoute) -> Unit,
    onSetRoutePriority: (List<OutputRoute>) -> Unit,
    onToggleRememberRoute: (Boolean) -> Unit,
    onToggleAutoSwitchBluetooth: (Boolean) -> Unit,
    onToggleAudioLeveling: (Boolean) -> Unit,
    onSetCallMode: (CallModeOverride) -> Unit,
    onSetPhoneEarpiece: (Boolean) -> Unit,
) {
    val c = MumbleTheme.colors
    SettingsPageScaffold("Audio output", onBack) {
        SectionLabel("OUTPUT DEVICE")
        SettingsGroup {
            Text(
                if (settings.rememberLastRoute) "Starts on the output you last picked."
                else "Uses the highest-ranked route that’s available.",
                fontSize = 13.sp, color = c.onSurfaceVar,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
            )
            val order = settings.routePriority
            val reorderable = !settings.rememberLastRoute
            // Out of a call the priority list decides the route, so a tap would just be
            // overridden on connect — only allow picking when it takes effect.
            val pickable = inCall || settings.rememberLastRoute
            order.forEachIndexed { i, route ->
                val available = route in availableRoutes
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(enabled = available && pickable) { onSelectRoute(route) }
                        .padding(start = 16.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Box(
                        Modifier.size(22.dp).clip(CircleShape).background(c.surfHighest)
                            .alpha(if (reorderable) 1f else 0.5f),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("${i + 1}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVar)
                    }
                    Row(
                        Modifier.weight(1f).alpha(if (available) 1f else 0.5f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(route.icon(), null, tint = c.onSurfaceVar, modifier = Modifier.size(20.dp))
                        Column {
                            Text(route.displayLabel(), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                                color = c.onSurface)
                            if (!available) Text("Unavailable", fontSize = 12.sp, color = c.onSurfaceVar)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        MoveButton(Icons.Filled.KeyboardArrowUp, "Move ${route.displayLabel()} up",
                            enabled = reorderable && i > 0) {
                            onSetRoutePriority(order.toMutableList().apply { add(i - 1, removeAt(i)) })
                        }
                        MoveButton(Icons.Filled.KeyboardArrowDown, "Move ${route.displayLabel()} down",
                            enabled = reorderable && i < order.lastIndex) {
                            onSetRoutePriority(order.toMutableList().apply { add(i + 1, removeAt(i)) })
                        }
                    }
                    RadioDot(selected = route == currentRoute)
                }
            }
            if (currentRoute == OutputRoute.BT_A2DP_HQ) {
                Text(
                    "High-quality audio to your Bluetooth headphones, mic from the phone. " +
                        "Higher latency — push-to-talk recommended.",
                    fontSize = 12.sp, color = c.onSurfaceVar,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
            if (hasEarpiece) {
                Divider()
                StackedRow(Icons.Filled.Speaker, "Phone speaker plays through", null) {
                    SettingsSegmented(listOf("Speakerphone", "Ear speaker"), if (settings.phoneEarpiece) 1 else 0,
                        { onSetPhoneEarpiece(it == 1) })
                    if (settings.phoneEarpiece) {
                        Text(
                            "Hold the phone to your ear. Uses call volume, and the screen turns " +
                                "off near your face.",
                            fontSize = 12.sp, color = c.onSurfaceVar, modifier = Modifier.padding(start = 36.dp),
                        )
                    }
                }
            }
        }
        GroupGap()
        SettingsGroup {
            ToggleRow(Icons.Filled.Autorenew, "Remember last output", "Reordering is off while this is on",
                settings.rememberLastRoute, onToggleRememberRoute)
            Divider()
            ToggleRow(Icons.Filled.Bluetooth, "Switch to Bluetooth when connected", null,
                settings.autoSwitchBluetooth, onToggleAutoSwitchBluetooth)
            Divider()
            ToggleRow(Icons.AutoMirrored.Filled.VolumeUp, "Audio leveling", "Even out quiet and loud talkers",
                settings.audioLeveling, onToggleAudioLeveling)
        }
        AdvancedSection(advancedExpanded, onAdvancedExpandedChange, "Speaker audio mode") {
            StackedRow(
                Icons.AutoMirrored.Filled.VolumeUp, "Speaker audio mode",
                when (settings.callMode) {
                    CallModeOverride.AUTO ->
                        if (settings.speakerEchoModel == android.os.Build.MODEL)
                            "Call audio: this phone's speaker echoed in media mode"
                        else "Media volume. Switches to call audio by itself if others " +
                            "would hear an echo of themselves."
                    CallModeOverride.CALL -> "Call volume and the phone's call echo cancelling"
                    CallModeOverride.MEDIA -> "Always media volume, even if Auto would pick Call"
                },
            ) {
                SettingsSegmented(listOf("Auto", "Call", "Media"), settings.callMode.ordinal,
                    { onSetCallMode(CallModeOverride.entries[it]) })
            }
        }
    }
}

/* ---------------- 03.6 Chat ---------------- */

@Composable
internal fun ChatPage(onBack: () -> Unit, settings: AppSettings, onToggleRichComposer: (Boolean) -> Unit) {
    SettingsPageScaffold("Chat", onBack) {
        GroupGap()
        SettingsGroup {
            ToggleRow(Icons.Filled.FormatBold, "Formatting toolbar",
                if (settings.richComposer) "Bold, italic, color… buttons above the message box"
                else "Off: format by typing Markdown (**bold**, *italic*, `code`)",
                settings.richComposer, onToggleRichComposer)
        }
    }
}

/* ---------------- 03.7 Notifications ---------------- */

@Composable
internal fun NotificationsPage(
    onBack: () -> Unit,
    settings: AppSettings,
    onToggleTts: (Boolean) -> Unit,
    onToggleMentionSound: (Boolean) -> Unit,
    onToggleAllowNudges: (Boolean) -> Unit,
    onToggleJoinAlerts: (Boolean) -> Unit,
) {
    SettingsPageScaffold("Notifications", onBack) {
        GroupGap()
        SettingsGroup {
            ToggleRow(Icons.Filled.RecordVoiceOver, "Read messages aloud", "Text-to-speech",
                settings.ttsReadAloud, onToggleTts)
            Divider()
            ToggleRow(Icons.Filled.NotificationsActive, "Mention sound", null, settings.mentionSound,
                onToggleMentionSound)
            Divider()
            ToggleRow(Icons.Filled.Vibration, "Allow nudges",
                "Other Grumbla users can shake your screen and buzz your phone",
                settings.allowNudges, onToggleAllowNudges)
            Divider()
            ToggleRow(Icons.Filled.PersonAdd, "Join alerts",
                "A sound and a pop-up when someone enters your channel",
                settings.joinAlerts, onToggleJoinAlerts)
        }
    }
}

/* ---------------- 03.8 Privacy & permissions ---------------- */

internal data class PermStatus(
    val label: String,
    val why: String,
    val icon: ImageVector,
    val permission: String?, // runtime permission, null for the battery exemption
    val granted: Boolean,
)

/**
 * Floating talk button (BACKLOG 3.3): the toggle, the hold/toggle choice, and a prompt for the
 * "display over other apps" special access, re-checked on resume (the grant is a system screen).
 */
@Composable
private fun FloatingPttRows(
    settings: AppSettings,
    onToggle: (Boolean) -> Unit,
    onSetMode: (FloatingPttMode) -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(ctx)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) granted = android.provider.Settings.canDrawOverlays(ctx)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val requestGrant = {
        runCatching {
            ctx.startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${ctx.packageName}"),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    ToggleRow(Icons.Filled.TouchApp, "Floating talk button",
        "Talk from other apps while connected",
        settings.floatingPtt, { on -> onToggle(on); if (on && !granted) requestGrant() })
    if (!settings.floatingPtt) return
    Divider()
    StackedRow(null, "Button action", if (settings.floatingPttMode == FloatingPttMode.HOLD)
        "Talk while pressed. Flick it to move it." else "Tap to start, tap again to stop. Drag to move.",
        indent = true) {
        SettingsSegmented(
            listOf("Hold to talk", "Tap to toggle"),
            if (settings.floatingPttMode == FloatingPttMode.HOLD) 0 else 1,
            { onSetMode(if (it == 0) FloatingPttMode.HOLD else FloatingPttMode.TOGGLE) },
        )
    }
    if (!granted) {
        Divider()
        RowBase(null, "Display over other apps", "Off · tap to allow",
            trailing = {
                Icon(Icons.Filled.Error, contentDescription = "Not allowed", tint = MumbleTheme.colors.muted,
                    modifier = Modifier.size(22.dp))
            },
            onClick = requestGrant, indent = true)
    }
}

/**
 * Current status of each permission the app needs, re-checked whenever the screen resumes (e.g.
 * coming back from system app settings). The second value forces a re-check.
 */
@Composable
internal fun rememberPermissionStatus(): Pair<List<PermStatus>, () -> Unit> {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val list = remember(refresh) {
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        buildList {
            add(PermStatus("Microphone", "Required to transmit your voice", Icons.Filled.Mic,
                android.Manifest.permission.RECORD_AUDIO, granted(android.Manifest.permission.RECORD_AUDIO)))
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                add(PermStatus("Notifications", "Show the ongoing call controls", Icons.Filled.Notifications,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    granted(android.Manifest.permission.POST_NOTIFICATIONS)))
            }
            add(PermStatus("Bluetooth", "Use Bluetooth headsets for audio", Icons.Filled.Bluetooth,
                android.Manifest.permission.BLUETOOTH_CONNECT,
                granted(android.Manifest.permission.BLUETOOTH_CONNECT)))
            // Battery-optimization exemption — a special access, not a runtime permission. Needed so
            // the foreground voice service isn't killed in the background / under Doze.
            val pm = ctx.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
            add(PermStatus("Run in background", "Keep the call alive when the screen is off",
                Icons.Filled.BatteryChargingFull, null, pm.isIgnoringBatteryOptimizations(ctx.packageName)))
        }
    }
    return list to { refresh++ }
}

/**
 * Lets the user (re)grant permissions they missed or denied. Tapping a missing permission re-requests
 * it; if it was permanently denied (the system won't show the dialog), it sends the user to the app's
 * settings page instead.
 */
@Composable
internal fun PrivacyPage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val activity = ctx as? android.app.Activity
    val (perms, refresh) = rememberPermissionStatus()

    var pending by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
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

    SettingsPageScaffold("Privacy & permissions", onBack) {
        SectionLabel("PERMISSIONS")
        SettingsGroup {
            perms.forEachIndexed { i, p ->
                if (i > 0) Divider()
                PermissionRow(p) {
                    if (p.permission != null) {
                        pending = p.permission
                        launcher.launch(p.permission)
                    } else runCatching {
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
    }
}

@Composable
private fun PermissionRow(p: PermStatus, onTap: () -> Unit) {
    val c = MumbleTheme.colors
    RowBase(
        p.icon, p.label, if (p.granted) "Allowed" else "Off · tap to allow",
        trailing = {
            Icon(
                if (p.granted) Icons.Filled.CheckCircle else Icons.Filled.Error,
                contentDescription = if (p.granted) "Allowed" else "Not allowed",
                tint = if (p.granted) c.speaking else c.muted,
                modifier = Modifier.size(22.dp),
            )
        },
        onClick = if (p.granted) null else onTap,
    )
}

/* ---------------- 03.9 About ---------------- */

@Composable
internal fun AboutPage(
    onBack: () -> Unit,
    settings: AppSettings,
    onSetGamesUnlocked: (Boolean) -> Unit,
    onToggleDebugOverlay: (Boolean) -> Unit,
    onOpenLicenses: () -> Unit,
) {
    SettingsPageScaffold("About", onBack) {
        GroupGap()
        SettingsGroup {
            // Easter egg, Android-style: seven taps on the version toggle the games.
            val context = LocalContext.current
            var versionTaps by remember { mutableStateOf(0) }
            var tapToast by remember { mutableStateOf<android.widget.Toast?>(null) }
            ValueRow(Icons.Filled.Info, "Version", "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
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
            NavRow(Icons.AutoMirrored.Filled.Article, "Open-source licenses", null, onClick = onOpenLicenses)
        }
        SectionLabel("DEVELOPER")
        SettingsGroup {
            ToggleRow(Icons.Filled.BarChart, "Debug overlay", "Live packet counts + loss on the voice screen",
                settings.debugOverlay, onToggleDebugOverlay)
        }
    }
}
