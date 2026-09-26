@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package app.notmumla.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import kotlin.math.roundToInt
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.Avatar
import app.notmumla.ui.ChannelLayout
import app.notmumla.ui.SpeakingBars
import app.notmumla.ui.TransmissionMode
import app.notmumla.ui.UiChannel
import app.notmumla.ui.UiMessage
import app.notmumla.ui.UiUser
import app.notmumla.ui.UserStatus
import app.notmumla.ui.chat.ChatPanel
import app.notmumla.ui.theme.MonoFamily
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun ChannelsScreen(
    serverName: String,
    serverInitial: String,
    connectionLabel: String,
    channels: List<UiChannel>,
    chatMessages: List<UiMessage>,
    selfMuted: Boolean,
    selfDeafened: Boolean,
    transmitting: Boolean,
    unreadCount: Int,
    currentChannelName: String,
    transmissionMode: TransmissionMode,
    debugStats: kotlinx.coroutines.flow.StateFlow<app.notmumla.protocol.model.AudioDebugStats>?,
    onJoinChannel: (Int) -> Unit,
    onPttHeld: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
    onSendText: (String) -> Unit,
    onSendImage: (android.net.Uri) -> Unit,
    onChatRead: () -> Unit,
    onOpenSettings: () -> Unit,
    onSetUserVolume: (name: String, db: Float) -> Unit,
    /** Another app is recording and we've released the mic to it. */
    micInUseElsewhere: Boolean,
    whisperingTo: String?,
    onWhisper: (UiUser) -> Unit,
    onStopWhisper: () -> Unit,
    /** Name of the user the composer is privately messaging, or null for the channel. */
    privateChatWith: String?,
    onStartPrivateChat: (session: Int, name: String) -> Unit,
    onClosePrivateChat: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val c = MumbleTheme.colors
    var tab by remember { mutableStateOf(0) } // 0 = channels, 1 = chat
    androidx.compose.runtime.LaunchedEffect(tab) { if (tab == 1) onChatRead() }
    var layout by remember { mutableStateOf(ChannelLayout.TREE) }
    var quickSettings by remember { mutableStateOf(false) }
    var volumeUser by remember { mutableStateOf<UiUser?>(null) }

    Box(Modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize()) {
            // The header overflow button opens quick settings (per the design mockup).
            ServerHeader(serverName, serverInitial, connectionLabel, onOpenQuickSettings = { quickSettings = true })
            TabStrip(tab, unread = unreadCount, onSelect = { tab = it })
            debugStats?.let { DebugOverlay(it) }

            Box(Modifier.weight(1f)) {
                when (tab) {
                    0 -> {
                        val onUserLongPress: (UiUser) -> Unit = { if (!it.isYou) volumeUser = it }
                        when (layout) {
                            ChannelLayout.TREE -> TreeLayout(channels, onJoinChannel, onUserLongPress)
                            ChannelLayout.SPEAKERS -> SpeakersLayout(channels, onJoinChannel, onUserLongPress)
                            ChannelLayout.COMPACT -> CompactLayout(channels, onJoinChannel, onUserLongPress)
                        }
                    }
                    else -> ChatPanel(
                        onSend = onSendText,
                        onSendImage = onSendImage,
                        messages = chatMessages,
                        channelName = currentChannelName,
                        privateTo = privateChatWith,
                        onReplyPrivately = onStartPrivateChat,
                        onClosePrivate = onClosePrivateChat,
                    )
                }
            }

            VoiceBar(
                mode = transmissionMode,
                muted = selfMuted,
                deafened = selfDeafened,
                transmitting = transmitting,
                micInUseElsewhere = micInUseElsewhere,
                whisperingTo = whisperingTo,
                onStopWhisper = onStopWhisper,
                onPttHeld = onPttHeld,
                onToggleMute = onToggleMute,
                onToggleDeafen = onToggleDeafen,
            )
        }

        if (quickSettings) {
            QuickSettingsSheet(
                layout = layout,
                onLayout = { layout = it },
                onOpenAllSettings = { quickSettings = false; onOpenSettings() },
                onDisconnect = onDisconnect,
                onClose = { quickSettings = false },
            )
        }
        volumeUser?.let { u ->
            UserVolumeSheet(
                u, onSetUserVolume,
                onWhisper = { onWhisper(u); volumeUser = null },
                onMessage = { onStartPrivateChat(u.id, u.name); volumeUser = null; tab = 1 },
                onDismiss = { volumeUser = null },
            )
        }
    }
}

@Composable
private fun UserVolumeSheet(
    user: UiUser,
    onSet: (String, Float) -> Unit,
    onWhisper: () -> Unit,
    onMessage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MumbleTheme.colors
    var db by remember(user.id) { mutableStateOf(user.gainDb.toFloat()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfContainer,
        title = { Text("${user.name} · volume", fontWeight = FontWeight.Bold, color = c.onSurface) },
        text = {
            Column {
                Text(
                    if (db.toInt() == 0) "Default" else "${if (db > 0) "+" else ""}${db.roundToInt()} dB",
                    fontFamily = MonoFamily, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = c.primary,
                )
                androidx.compose.material3.Slider(
                    value = db,
                    onValueChange = { db = it },
                    onValueChangeFinished = { onSet(user.name, db) },
                    valueRange = -30f..15f,
                    colors = androidx.compose.material3.SliderDefaults.colors(
                        thumbColor = c.primary, activeTrackColor = c.primary,
                        inactiveTrackColor = c.surfHigh,
                    ),
                )
                Text("Only affects how you hear them.", fontSize = 12.sp, color = c.onSurfaceVar)
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.FilledTonalButton(
                    onClick = onWhisper,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Whisper to ${user.name}") }
                Text("Your voice goes only to them until you stop.", fontSize = 12.sp, color = c.onSurfaceVar)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.FilledTonalButton(
                    onClick = onMessage,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Message ${user.name} privately") }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Done") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { db = 0f; onSet(user.name, 0f) }) { Text("Reset") }
        },
    )
}

@Composable
private fun DebugOverlay(statsFlow: kotlinx.coroutines.flow.StateFlow<app.notmumla.protocol.model.AudioDebugStats>) {
    val stats by statsFlow.collectAsState()
    val c = MumbleTheme.colors
    val mono = androidx.compose.ui.text.font.FontFamily.Monospace
    Row(
        Modifier.fillMaxWidth().background(c.surfContainer).padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("TX ${stats.sentPerSec}/s·${stats.sent}", fontSize = 12.sp, fontFamily = mono, color = c.speaking)
        Text("RX ${stats.recvPerSec}/s·${stats.received}", fontSize = 12.sp, fontFamily = mono, color = c.primary)
        Text("LOST ${stats.lost}", fontSize = 12.sp, fontFamily = mono,
            color = if (stats.lost > 0) c.muted else c.onSurfaceVar)
        Text(if (stats.udp) "UDP" else "TCP", fontSize = 12.sp, fontFamily = mono,
            color = if (stats.udp) c.speaking else c.onSurfaceVar)
    }
}

@Composable
private fun ServerHeader(
    serverName: String,
    serverInitial: String,
    connectionLabel: String,
    onOpenQuickSettings: () -> Unit,
) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(c.speaking),
            contentAlignment = Alignment.Center,
        ) { Text(serverInitial, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp) }
        Column(Modifier.weight(1f)) {
            Text(serverName, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = c.onSurface)
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(c.speaking))
                Text(connectionLabel, fontSize = 12.sp, color = c.onSurfaceVar)
            }
        }
        Box(
            Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onOpenQuickSettings),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.MoreVert, "Quick settings", tint = c.onSurfaceVar) }
    }
}

@Composable
private fun TabStrip(selected: Int, unread: Int, onSelect: (Int) -> Unit) {
    val c = MumbleTheme.colors
    Row(Modifier.fillMaxWidth().height(48.dp).background(c.surface)) {
        TabButton("Channels", selected == 0, Modifier.weight(1f)) { onSelect(0) }
        TabButton("Chat", selected == 1, Modifier.weight(1f), badge = unread) { onSelect(1) }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVariant))
}

@Composable
private fun TabButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    badge: Int = 0,
    onClick: () -> Unit,
) {
    val c = MumbleTheme.colors
    Row(
        modifier.fillMaxSize().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            color = if (selected) c.primary else c.onSurfaceVar)
        if (badge > 0) {
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(9.dp)).background(c.primary)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
                contentAlignment = Alignment.Center,
            ) { Text("$badge", color = c.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

@Composable
private fun EmptyChannels() {
    val c = MumbleTheme.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Connecting…", color = c.onSurfaceVar, fontSize = 14.sp)
    }
}

/* ---------------- Tree layout ---------------- */

@Composable
private fun TreeLayout(channels: List<UiChannel>, onJoin: (Int) -> Unit, onUserLongPress: (UiUser) -> Unit) {
    val c = MumbleTheme.colors
    if (channels.isEmpty()) { EmptyChannels(); return }
    LazyColumn(Modifier.fillMaxSize().padding(10.dp)) {
        item {
            Text("VOICE CHANNELS", color = c.onSurfaceVar, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, letterSpacing = 0.07.sp,
                modifier = Modifier.padding(start = 10.dp, top = 8.dp, bottom = 6.dp))
        }
        items(channels) { channel ->
            ChannelHeaderRow(channel, onJoin)
            channel.users.forEach { user -> UserRow(user) { onUserLongPress(user) } }
        }
    }
}

/** Small inline badge showing a user's local volume adjustment, e.g. "+10" / "-3". */
@Composable
private fun GainBadge(gainDb: Int) {
    val c = MumbleTheme.colors
    Text(
        "${if (gainDb > 0) "+" else ""}$gainDb",
        fontFamily = MonoFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold,
        color = if (gainDb > 0) c.speaking else c.muted,
    )
}

@Composable
private fun ChannelHeaderRow(channel: UiChannel, onJoin: (Int) -> Unit) {
    val c = MumbleTheme.colors
    val bg = if (channel.isCurrent) c.primaryContainer else Color.Transparent
    val fg = if (channel.isCurrent) c.onPrimaryContainer else c.onSurface
    Row(
        Modifier.fillMaxWidth().padding(start = (channel.depth * 16).dp)
            .clip(RoundedCornerShape(14.dp)).background(bg)
            .clickable { onJoin(channel.id) }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            if (channel.locked) Icons.Filled.Lock else Icons.AutoMirrored.Filled.VolumeUp,
            null, tint = if (channel.isCurrent) c.onPrimaryContainer else c.onSurfaceVar,
            modifier = Modifier.size(18.dp),
        )
        Text(channel.name, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = fg,
            modifier = Modifier.weight(1f))
        if (channel.users.isNotEmpty()) {
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (channel.isCurrent) c.onPrimaryContainer.copy(alpha = 0.14f) else c.surfHigh)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text("${channel.users.size}", fontFamily = MonoFamily, fontSize = 12.sp,
                    fontWeight = FontWeight.Bold, color = fg)
            }
        }
    }
}

@Composable
private fun UserRow(user: UiUser, onLongPress: () -> Unit) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
            .padding(start = 38.dp, end = 12.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        Avatar(user.initials, user.avatar, size = 28.dp, dimmed = user.status == UserStatus.AFK)
        Text(user.name, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = c.onSurface,
            modifier = Modifier.weight(1f))
        if (user.gainDb != 0) GainBadge(user.gainDb)
        when (user.status) {
            UserStatus.SPEAKING -> SpeakingBars()
            UserStatus.MUTED -> Icon(Icons.Filled.MicOff, "muted", tint = c.muted,
                modifier = Modifier.size(18.dp))
            UserStatus.AFK -> Text("Zzz", color = c.afk, fontWeight = FontWeight.Bold,
                fontFamily = MonoFamily, fontSize = 13.sp)
            UserStatus.ACTIVE -> {}
        }
        if (user.isYou) {
            Spacer(Modifier.width(6.dp))
            Text("YOU", color = c.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        }
    }
}

/* ---------------- Speakers layout ---------------- */

@Composable
private fun SpeakersLayout(channels: List<UiChannel>, onJoin: (Int) -> Unit, onUserLongPress: (UiUser) -> Unit) {
    val c = MumbleTheme.colors
    val current = channels.firstOrNull { it.isCurrent } ?: channels.firstOrNull()
    if (current == null) { EmptyChannels(); return }
    val speakers = current.users.filter { it.status == UserStatus.SPEAKING }
    val others = current.users.filter { it.status != UserStatus.SPEAKING }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
        item {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surfContainer)
                    .padding(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(c.speaking))
                    Text("${speakers.size} talking now", fontWeight = FontWeight.Bold,
                        fontSize = 13.sp, color = c.onSurface)
                }
                if (speakers.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                    ) { speakers.forEach { s -> SpeakerBubble(s) { onUserLongPress(s) } } }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text("IN ${current.name.uppercase()}", color = c.onSurfaceVar, fontWeight = FontWeight.Bold,
                fontSize = 11.sp, letterSpacing = 0.06.sp,
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        items(others) { user ->
            Row(
                Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp)).background(c.surfContainer)
                    .combinedClickable(onClick = {}, onLongClick = { onUserLongPress(user) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(user.initials, user.avatar, size = 32.dp)
                Text(user.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    color = c.onSurface, modifier = Modifier.weight(1f))
                if (user.gainDb != 0) GainBadge(user.gainDb)
                if (user.status == UserStatus.MUTED)
                    Icon(Icons.Filled.MicOff, "muted", tint = c.muted, modifier = Modifier.size(16.dp))
                if (user.isYou) Text("YOU", color = c.primary, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun SpeakerBubble(user: UiUser, onLongPress: () -> Unit) {
    val c = MumbleTheme.colors
    Column(
        Modifier.combinedClickable(onClick = {}, onLongClick = onLongPress),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Avatar(user.initials, user.avatar, size = 56.dp)
        Text(user.name.substringBefore(" "), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = c.onSurface)
        if (user.gainDb != 0) GainBadge(user.gainDb)
    }
}

/* ---------------- Compact layout ---------------- */

@Composable
private fun CompactLayout(channels: List<UiChannel>, onJoin: (Int) -> Unit, onUserLongPress: (UiUser) -> Unit) {
    val c = MumbleTheme.colors
    if (channels.isEmpty()) { EmptyChannels(); return }
    LazyColumn(Modifier.fillMaxSize().padding(vertical = 6.dp)) {
        items(channels) { channel ->
            Row(
                Modifier.fillMaxWidth().padding(start = (channel.depth * 20).dp)
                    .background(if (channel.isCurrent) c.primaryContainer.copy(alpha = 0.4f) else Color.Transparent)
                    .clickable { onJoin(channel.id) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Icon(
                    if (channel.locked) Icons.Filled.Lock else Icons.AutoMirrored.Filled.VolumeUp,
                    null, tint = if (channel.isCurrent) c.primary else c.onSurfaceVar,
                    modifier = Modifier.size(16.dp),
                )
                Text(channel.name, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    color = c.onSurface, modifier = Modifier.weight(1f))
                if (channel.users.isNotEmpty())
                    Text("${channel.users.size}", fontFamily = MonoFamily, fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (channel.isCurrent) c.primary else c.onSurfaceVar)
            }
            channel.users.forEach { user ->
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(onClick = {}, onLongClick = { onUserLongPress(user) })
                        .padding(start = 40.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (user.status) {
                        UserStatus.SPEAKING -> SpeakingBars(maxHeight = 11.dp, barWidth = 2.5.dp)
                        UserStatus.MUTED -> Icon(Icons.Filled.MicOff, null, tint = c.muted,
                            modifier = Modifier.size(14.dp))
                        else -> Box(Modifier.size(6.dp).clip(CircleShape)
                            .background(if (user.isYou) c.primary else c.outline))
                    }
                    Text(user.name, fontSize = 13.sp, color = c.onSurface,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    if (user.gainDb != 0) GainBadge(user.gainDb)
                    if (user.isYou) Text("YOU", color = c.primary, fontWeight = FontWeight.Bold,
                        fontFamily = MonoFamily, fontSize = 10.sp)
                }
            }
        }
    }
}
