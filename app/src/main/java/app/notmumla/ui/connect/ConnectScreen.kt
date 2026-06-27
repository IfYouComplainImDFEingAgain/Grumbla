package app.notmumla.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Mic
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
import app.notmumla.ui.MockData
import app.notmumla.ui.UiServer
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun ConnectScreen(
    onConnect: () -> Unit,
) {
    val c = MumbleTheme.colors
    Column(
        Modifier
            .fillMaxSize()
            .background(c.surface)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        // App identity header
        Column(
            Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(78.dp).clip(RoundedCornerShape(24.dp)).background(c.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Mic, null, tint = c.onPrimary, modifier = Modifier.size(40.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("Mumble", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
            Text("Connect to a voice server", fontSize = 14.sp, color = c.onSurfaceVar,
                modifier = Modifier.padding(top = 4.dp))
        }

        // Connect form
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfContainer)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FormField(label = "Server address") {
                Text("example.com", color = c.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
                Text(":64738", color = c.onSurfaceVar, fontSize = 15.sp)
            }
            FormField(label = "Username") {
                Text("user", color = c.onSurface, fontWeight = FontWeight.Medium, fontSize = 15.sp)
            }
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(c.primary).clickable(onClick = onConnect).padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connect", color = c.onPrimary, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        Text(
            "RECENT SERVERS",
            color = c.onSurfaceVar, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            letterSpacing = 0.06.sp,
            modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp),
        )
        MockData.servers.forEach { server ->
            ServerRow(server, onClick = onConnect)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun FormField(label: String, content: @Composable () -> Unit) {
    val c = MumbleTheme.colors
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVar,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) { content() }
    }
}

@Composable
private fun ServerRow(server: UiServer, onClick: () -> Unit) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfContainer)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(server.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text(server.initial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(server.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.onSurface)
            Text("${server.onlineCount} online · ${server.pingMs} ms",
                fontSize = 13.sp, color = c.onSurfaceVar)
        }
        Box(
            Modifier.size(8.dp).clip(CircleShape)
                .background(if (server.online) c.speaking else c.outline),
        )
    }
}
