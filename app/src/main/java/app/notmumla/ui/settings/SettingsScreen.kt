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
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.audio.TransmissionMode
import app.notmumla.audio.routing.OutputRoute
import app.notmumla.data.AppSettings
import app.notmumla.data.NoiseSuppression
import app.notmumla.data.ThemeMode
import app.notmumla.ui.SegmentedToggle
import app.notmumla.ui.theme.MumbleTheme
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

private fun OutputRoute.displayLabel(): String = when (this) {
    OutputRoute.PHONE_SPEAKER -> "Phone speaker"
    OutputRoute.WIRED -> "Wired headset"
    OutputRoute.BT_A2DP_HQ -> "Bluetooth (High Quality)"
    OutputRoute.BT_HEADSET_SCO -> "Bluetooth headset"
}

private val BITRATES = listOf(16_000, 24_000, 40_000, 72_000, 96_000)

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    settings: AppSettings,
    inputLevel: Float,
    availableRoutes: List<OutputRoute>,
    currentRoute: OutputRoute,
    onSelectRoute: (OutputRoute) -> Unit,
    onSetTheme: (ThemeMode) -> Unit,
    onSetTransmission: (TransmissionMode) -> Unit,
    onSetVad: (Float) -> Unit,
    onSetMicGainDb: (Float) -> Unit,
    onToggleAutoGain: (Boolean) -> Unit,
    onSetNoiseSuppression: (NoiseSuppression) -> Unit,
    onToggleEchoCancellation: (Boolean) -> Unit,
    onSetBitrate: (Int) -> Unit,
    onToggleAvatars: (Boolean) -> Unit,
    onToggleKeepAwake: (Boolean) -> Unit,
    onToggleAutoReconnect: (Boolean) -> Unit,
    onToggleTts: (Boolean) -> Unit,
    onToggleMentionSound: (Boolean) -> Unit,
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
                NavRow(Icons.Filled.VerifiedUser, "Identity certificate", null, trailing = {
                    Text("Verified", color = c.speaking, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                })
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
                    isDark) { onSetTheme(if (it) ThemeMode.DARK else ThemeMode.LIGHT) }
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
                if (settings.transmissionMode == TransmissionMode.VAD) {
                    Divider()
                    VadSensitivityRow(
                        level = inputLevel,
                        threshold = settings.vadSensitivity,
                        onChange = onSetVad,
                    )
                }
                Divider()
                ToggleRow(Icons.Filled.GraphicEq, "Automatic gain control",
                    "Auto-level your mic", settings.autoGain, onToggleAutoGain)
                if (!settings.autoGain) {
                    Divider()
                    SliderRow(
                        "Microphone gain", "${if (settings.micGainDb >= 0) "+" else ""}${settings.micGainDb.roundToInt()} dB",
                        value = (settings.micGainDb + 20f) / 40f, range = 0f..1f,
                        onChange = { onSetMicGainDb(it * 40f - 20f) }, icon = Icons.Filled.GraphicEq,
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
                Divider()
                ToggleRow(Icons.Filled.GraphicEq, "Echo cancellation", null,
                    settings.echoCancellation, onToggleEchoCancellation)
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
                    Spacer(Modifier.height(10.dp))
                    OutputRoute.entries.forEach { route ->
                        val enabled = route in availableRoutes
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable(enabled = enabled) { onSelectRoute(route) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                route.displayLabel() + if (!enabled) " (unavailable)" else "",
                                fontSize = 14.sp,
                                color = if (enabled) c.onSurface else c.onSurfaceVar.copy(alpha = 0.5f),
                                modifier = Modifier.weight(1f),
                            )
                            RadioDot(selected = route == currentRoute)
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
            }

            SectionLabel("NOTIFICATIONS")
            SettingsGroup {
                ToggleRow(null, "Read messages aloud", "Text-to-speech",
                    settings.ttsReadAloud, onToggleTts)
                Divider()
                ToggleRow(null, "Mention sound", null, settings.mentionSound, onToggleMentionSound)
            }

            SectionLabel("ABOUT")
            SettingsGroup {
                ValueRow(Icons.Filled.VerifiedUser, "Version", "0.1.0 (1)", showChevron = false)
                Divider()
                NavRow(Icons.Filled.BarChart, "Open-source licenses", null, onClick = onOpenLicenses)
            }
            Spacer(Modifier.height(20.dp))
        }
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
) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
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
                      checked: Boolean, onChange: (Boolean) -> Unit) {
    RowBase(icon, title, subtitle, trailing = { ToggleSwitch(checked, onChange) },
        onClick = { onChange(!checked) })
}

@Composable
private fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = MumbleTheme.colors
    Box(
        Modifier.size(width = 46.dp, height = 26.dp).clip(RoundedCornerShape(13.dp))
            .background(if (checked) c.primary else c.surfHighest)
            .clickable { onChange(!checked) },
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
private fun VadSensitivityRow(level: Float, threshold: Float, onChange: (Float) -> Unit) {
    val c = MumbleTheme.colors
    val meterMax = 0.3f // RMS scale: typical speech sits well within this
    val levelFrac = (level / meterMax).coerceIn(0f, 1f)
    val threshFrac = (threshold / meterMax).coerceIn(0f, 1f)
    val transmitting = level >= threshold && level > 0.001f

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Input sensitivity", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                Text("Speak, then set the line just above the bar's resting level",
                    fontSize = 12.sp, color = c.onSurfaceVar)
            }
        }
        Spacer(Modifier.size(10.dp))
        // Live level meter with the threshold marker.
        Box(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)).background(c.surfHighest)) {
            Box(
                Modifier.fillMaxWidth(levelFrac).height(14.dp).clip(RoundedCornerShape(7.dp))
                    .background(if (transmitting) c.speaking else c.outline),
            )
            Box(
                Modifier.fillMaxWidth(threshFrac).height(14.dp),
                contentAlignment = Alignment.CenterEnd,
            ) { Box(Modifier.width(3.dp).height(20.dp).background(c.primary)) }
        }
        // Logarithmic mapping: most of the slider travel covers the sensitive low end (0.0004–0.15),
        // which is where VAD actually operates, so fine adjustments are possible.
        val minT = 0.0004f
        val maxT = 0.15f
        val pos = (ln((threshold / minT).coerceAtLeast(1f)) / ln(maxT / minT)).coerceIn(0f, 1f)
        Slider(
            value = pos,
            onValueChange = { p -> onChange(minT * (maxT / minT).pow(p)) },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(
                thumbColor = c.primary, activeTrackColor = c.primary.copy(alpha = 0.4f),
                inactiveTrackColor = c.surfHighest,
            ),
        )
    }
}

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
private fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
        tint = MumbleTheme.colors.onSurfaceVar.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
}
