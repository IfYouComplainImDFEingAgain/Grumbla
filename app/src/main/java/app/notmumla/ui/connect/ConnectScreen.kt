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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.data.db.ServerEntity
import app.notmumla.protocol.Mumble
import app.notmumla.ui.channels.avatarColorFor
import app.notmumla.ui.channels.initialsFor
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun ConnectScreen(
    savedServers: List<ServerEntity>,
    onConnectNew: (host: String, port: Int, username: String, password: String?) -> Unit,
    onConnectSaved: (ServerEntity) -> Unit,
    onDelete: (ServerEntity) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val c = MumbleTheme.colors
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(Mumble.DEFAULT_PORT.toString()) }
    var username by remember { mutableStateOf("user") }

    Column(
        Modifier.fillMaxSize().background(c.surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = c.onSurfaceVar)
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(78.dp).clip(RoundedCornerShape(24.dp)).background(c.primary),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Mic, null, tint = c.onPrimary, modifier = Modifier.size(40.dp)) }
            Spacer(Modifier.height(16.dp))
            Text("Mumble", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
            Text("Connect to a voice server", fontSize = 14.sp, color = c.onSurfaceVar,
                modifier = Modifier.padding(top = 4.dp))
        }

        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfContainer)
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) {
                    LabeledField("Server address", host, "example.com") { host = it }
                }
                Box(Modifier.width(110.dp)) {
                    LabeledField("Port", port, "64738", numeric = true) {
                        port = it.filter(Char::isDigit).take(5)
                    }
                }
            }
            LabeledField("Username", username, "user") { username = it }
            val canConnect = host.isNotBlank() && username.isNotBlank()
            Box(
                Modifier.fillMaxWidth().padding(top = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (canConnect) c.primary else c.surfHighest)
                    .clickable(enabled = canConnect) {
                        onConnectNew(host.trim(), port.toIntOrNull() ?: Mumble.DEFAULT_PORT,
                            username.trim(), null)
                    }
                    .padding(15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Connect", color = if (canConnect) c.onPrimary else c.onSurfaceVar,
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }

        if (savedServers.isNotEmpty()) {
            Text("RECENT SERVERS", color = c.onSurfaceVar, fontWeight = FontWeight.Bold,
                fontSize = 12.sp, letterSpacing = 0.06.sp,
                modifier = Modifier.padding(top = 24.dp, bottom = 8.dp, start = 4.dp))
            savedServers.forEach { server ->
                ServerRow(server, onClick = { onConnectSaved(server) }, onLongPress = { onDelete(server) })
                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun LabeledField(
    label: String,
    value: String,
    placeholder: String,
    numeric: Boolean = false,
    onChange: (String) -> Unit,
) {
    val c = MumbleTheme.colors
    Column {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVar,
            modifier = Modifier.padding(bottom = 6.dp, start = 4.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface)
                .border(1.dp, c.outlineVariant, RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 13.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                ),
                textStyle = LocalTextStyle.current.copy(color = c.onSurface, fontSize = 15.sp,
                    fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (value.isEmpty()) Text(placeholder, color = c.onSurfaceVar, fontSize = 15.sp)
                    inner()
                },
            )
        }
    }
}

@Composable
private fun ServerRow(server: ServerEntity, onClick: () -> Unit, onLongPress: () -> Unit) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surfContainer)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(avatarColorFor(server.label)),
            contentAlignment = Alignment.Center,
        ) {
            Text(initialsFor(server.label), color = Color.White, fontWeight = FontWeight.Bold,
                fontSize = 16.sp)
        }
        Column(Modifier.weight(1f)) {
            Text(server.label, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = c.onSurface)
            Text("${server.host}:${server.port} · ${server.username}",
                fontSize = 13.sp, color = c.onSurfaceVar)
        }
        Box(Modifier.size(36.dp).clip(CircleShape).clickable(onClick = onLongPress),
            contentAlignment = Alignment.Center) {
            Text("✕", color = c.onSurfaceVar, fontSize = 14.sp)
        }
    }
}
