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
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.OutputRoute
import app.notmumla.ui.SegmentedToggle
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val c = MumbleTheme.colors
    var dark by remember { mutableStateOf(c.isDark) }
    var autoReconnect by remember { mutableStateOf(true) }
    var priority by remember { mutableStateOf(false) }
    var showAvatars by remember { mutableStateOf(true) }
    var keepAwake by remember { mutableStateOf(false) }
    var noiseSuppression by remember { mutableStateOf(true) }
    var echoCancel by remember { mutableStateOf(true) }
    var transmission by remember { mutableStateOf(0) }
    var outputRoute by remember { mutableStateOf(OutputRoute.PHONE_SPEAKER) }

    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState()),
    ) {
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
                Divider()
                ToggleRow(Icons.Filled.Star, "Priority speaker", "Cut through when you talk",
                    priority) { priority = it }
            }

            SectionLabel("CONNECTION")
            SettingsGroup {
                ValueRow(Icons.Filled.Storage, "Server", "example.com:64738")
                Divider()
                ToggleRow(Icons.Filled.Autorenew, "Auto-reconnect", "Rejoin last channel automatically",
                    autoReconnect) { autoReconnect = it }
                Divider()
                ValueRow(Icons.Filled.BarChart, "Audio quality", "High · 72 kbit/s")
            }

            SectionLabel("APPEARANCE")
            SettingsGroup {
                ToggleRow(Icons.Filled.DarkMode, "Dark theme", "Match system or force dark",
                    dark) { dark = it }
                Divider()
                ToggleRow(Icons.Filled.Person, "Show user avatars", null,
                    showAvatars) { showAvatars = it }
                Divider()
                ToggleRow(Icons.Filled.Mic, "Keep screen awake in voice", null,
                    keepAwake) { keepAwake = it }
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
                        selectedIndex = transmission, onSelect = { transmission = it },
                    )
                }
                Divider()
                SliderRow("Input sensitivity", "−42 dB", 0.62f)
                Divider()
                SliderRow("Microphone gain", "+6 dB", 0.55f, icon = Icons.Filled.GraphicEq)
                Divider()
                ToggleRow(Icons.Filled.GraphicEq, "Noise suppression", null,
                    noiseSuppression) { noiseSuppression = it }
                Divider()
                ToggleRow(Icons.Filled.GraphicEq, "Echo cancellation", null,
                    echoCancel) { echoCancel = it }
            }

            SectionLabel("AUDIO · OUTPUT")
            SettingsGroup {
                SliderRow("Master volume", "82%", 0.82f, icon = Icons.AutoMirrored.Filled.VolumeUp)
                Divider()
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
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable { outputRoute = route }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(route.label, fontSize = 14.sp, color = c.onSurface,
                                modifier = Modifier.weight(1f))
                            RadioDot(selected = route == outputRoute)
                        }
                    }
                    if (outputRoute == OutputRoute.BT_A2DP_HQ) {
                        Text(
                            "High-quality audio to your Bluetooth headphones, mic from the phone. " +
                                "Higher latency — push-to-talk recommended.",
                            fontSize = 12.sp, color = c.onSurfaceVar,
                            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                        )
                    }
                }
            }

            SectionLabel("ABOUT")
            SettingsGroup {
                ValueRow(Icons.Filled.VerifiedUser, "Version", "0.1.0 (1)", showChevron = false)
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
            Text("Online · “shipping the build 🚀”", fontSize = 13.sp, color = c.onSurfaceVar)
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
                   trailing: @Composable () -> Unit = {}) {
    RowBase(icon, title, subtitle, trailing = { trailing(); Chevron() }, onClick = {})
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, value: String, showChevron: Boolean = true) {
    val c = MumbleTheme.colors
    RowBase(icon, title, null, trailing = {
        Text(value, fontSize = 14.sp, color = c.onSurfaceVar)
        if (showChevron) Chevron()
    }, onClick = if (showChevron) ({}) else null)
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String?,
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
private fun SliderRow(label: String, value: String, fraction: Float, icon: ImageVector? = null) {
    val c = MumbleTheme.colors
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
                Spacer(Modifier.size(14.dp))
            }
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface,
                modifier = Modifier.weight(1f))
            Text(value, fontSize = 13.sp, color = c.onSurfaceVar)
        }
        Spacer(Modifier.height(11.dp))
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
            .background(c.surfHighest)) {
            Box(Modifier.fillMaxWidth(fraction).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(c.primary))
        }
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
