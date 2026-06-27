package app.notmumla.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
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
import app.notmumla.ui.Avatar
import app.notmumla.ui.ChatKind
import app.notmumla.ui.MockData
import app.notmumla.ui.UiMessage
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun ChatPanel() {
    val c = MumbleTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Text("# General", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVar,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(MockData.messages) { msg -> MessageRow(msg) }
        }
        Composer()
    }
}

@Composable
private fun MessageRow(msg: UiMessage) {
    val c = MumbleTheme.colors
    when (msg.kind) {
        ChatKind.SYSTEM -> Text(
            msg.text, fontSize = 12.sp, color = c.onSurfaceVar,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        ChatKind.ME -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier.clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                    .background(c.primaryContainer).padding(horizontal = 12.dp, vertical = 9.dp),
            ) { Text(msg.text, color = c.onPrimaryContainer, fontSize = 14.sp) }
        }
        ChatKind.OTHER -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(msg.initials, msg.avatar, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.onSurface)
                    Text(msg.time, fontSize = 11.sp, color = c.onSurfaceVar)
                }
                Box(
                    Modifier.padding(top = 3.dp).clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                        .background(c.surfHigh).padding(horizontal = 12.dp, vertical = 9.dp),
                ) { Text(msg.text, color = c.onSurface, fontSize = 14.sp) }
            }
        }
        ChatKind.FILE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(msg.initials, msg.avatar, size = 32.dp)
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.surfHigh)
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(c.primary),
                    contentAlignment = Alignment.Center,
                ) { Text("PDF", color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 10.sp) }
                Column(Modifier.weight(1f)) {
                    Text(msg.fileName, fontWeight = FontWeight.SemiBold, fontSize = 13.sp,
                        color = c.onSurface, maxLines = 1)
                    Text(msg.fileSize, fontSize = 11.sp, color = c.onSurfaceVar)
                }
            }
        }
    }
}

@Composable
private fun Composer() {
    val c = MumbleTheme.colors
    Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Add, "Attach", tint = c.onSurfaceVar, modifier = Modifier.size(24.dp))
        Box(
            Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.surfHigh)
                .padding(horizontal = 16.dp, vertical = 11.dp),
        ) { Text("Message #General", color = c.onSurfaceVar, fontSize = 14.sp) }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(c.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = c.onPrimary, modifier = Modifier.size(20.dp)) }
    }
}
