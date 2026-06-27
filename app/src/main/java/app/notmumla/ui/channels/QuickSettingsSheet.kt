package app.notmumla.ui.channels

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.ChannelLayout
import app.notmumla.ui.SegmentedToggle
import app.notmumla.ui.TransmissionMode
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun QuickSettingsSheet(
    layout: ChannelLayout,
    mode: TransmissionMode,
    onLayout: (ChannelLayout) -> Unit,
    onMode: (TransmissionMode) -> Unit,
    onDisconnect: () -> Unit,
    onClose: () -> Unit,
) {
    val c = MumbleTheme.colors
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(c.surfContainer)
                .clickable(enabled = false) {}
                .padding(horizontal = 18.dp, vertical = 6.dp),
        ) {
            Box(Modifier.fillMaxWidth().padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp))
                    .background(c.outline.copy(alpha = 0.6f)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Quick settings", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    color = c.onSurface, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(c.surfHigh)
                        .clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "Close", tint = c.onSurfaceVar, modifier = Modifier.size(20.dp)) }
            }
            Spacer(Modifier.height(14.dp))

            SheetCard {
                Text("Channel layout", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = c.onSurfaceVar, modifier = Modifier.padding(bottom = 10.dp))
                SegmentedToggle(
                    options = listOf("Tree", "Speakers", "Compact"),
                    selectedIndex = layout.ordinal,
                    onSelect = { onLayout(ChannelLayout.entries[it]) },
                )
            }
            Spacer(Modifier.height(10.dp))
            SheetCard {
                Text("Transmission", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = c.onSurfaceVar, modifier = Modifier.padding(bottom = 10.dp))
                SegmentedToggle(
                    options = listOf("Push-to-Talk", "Voice Activated"),
                    selectedIndex = if (mode == TransmissionMode.PTT) 0 else 1,
                    onSelect = { onMode(if (it == 0) TransmissionMode.PTT else TransmissionMode.VAD) },
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.errContainer)
                    .clickable { onClose(); onDisconnect() }.padding(14.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, null, tint = c.onErrContainer,
                    modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Disconnect", color = c.onErrContainer, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SheetCard(content: @Composable () -> Unit) {
    val c = MumbleTheme.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) { content() }
}
