package app.notmumla.ui.chat

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.Avatar
import app.notmumla.ui.ChatKind
import app.notmumla.ui.MockData
import app.notmumla.ui.UiMessage
import app.notmumla.ui.theme.MumbleTheme

@Composable
fun ChatPanel(
    onSend: (String) -> Unit = {},
    onSendImage: (Uri) -> Unit = {},
    messages: List<UiMessage> = MockData.messages,
    channelName: String = "General",
) {
    val c = MumbleTheme.colors
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onSendImage) }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        Text("# $channelName", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVar,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(messages) { msg -> MessageRow(msg) }
        }
        Composer(
            channelName = channelName,
            onSend = onSend,
            onAttach = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
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
            if (msg.imageBytes != null) {
                InlineImage(msg)
            } else {
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp))
                        .background(c.primaryContainer).padding(horizontal = 12.dp, vertical = 9.dp),
                ) { Text(msg.text, color = c.onPrimaryContainer, fontSize = 14.sp) }
            }
        }
        ChatKind.OTHER, ChatKind.FILE -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Avatar(msg.initials, msg.avatar, size = 32.dp)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(msg.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = c.onSurface)
                    Text(msg.time, fontSize = 11.sp, color = c.onSurfaceVar)
                }
                if (msg.imageBytes != null) {
                    InlineImage(msg, modifier = Modifier.padding(top = 3.dp))
                }
                if (msg.text.isNotBlank()) {
                    Box(
                        Modifier.padding(top = 3.dp).clip(RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp))
                            .background(c.surfHigh).padding(horizontal = 12.dp, vertical = 9.dp),
                    ) { Text(msg.text, color = c.onSurface, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun InlineImage(msg: UiMessage, modifier: Modifier = Modifier) {
    val bytes = msg.imageBytes ?: return
    val bitmap = remember(msg.id) {
        runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap() }.getOrNull()
    } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = "Shared image",
        modifier = modifier.widthIn(max = 220.dp).heightIn(max = 280.dp).clip(RoundedCornerShape(14.dp)),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun Composer(channelName: String, onSend: (String) -> Unit, onAttach: () -> Unit) {
    val c = MumbleTheme.colors
    var text by remember { mutableStateOf("") }
    Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onAttach),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, "Attach image", tint = c.onSurfaceVar, modifier = Modifier.size(24.dp))
        }
        Box(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.surfHigh)
            .padding(horizontal = 16.dp, vertical = 11.dp)) {
            BasicTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                textStyle = LocalTextStyle.current.copy(color = c.onSurface, fontSize = 14.sp),
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (text.isEmpty()) Text("Message #$channelName", color = c.onSurfaceVar, fontSize = 14.sp)
                    inner()
                },
            )
        }
        val send = {
            val t = text.trim()
            if (t.isNotEmpty()) { onSend(t); text = "" }
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(c.primary)
                .clickable { send() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = c.onPrimary, modifier = Modifier.size(20.dp)) }
    }
}
