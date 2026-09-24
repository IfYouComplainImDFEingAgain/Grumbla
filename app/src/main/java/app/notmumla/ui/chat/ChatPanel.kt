package app.notmumla.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    /** When set, the composer sends private messages to this user instead of the channel. */
    privateTo: String? = null,
    onReplyPrivately: (session: Int, name: String) -> Unit = { _, _ -> },
    onClosePrivate: () -> Unit = {},
) {
    val c = MumbleTheme.colors
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onSendImage) }
    var viewerImage by remember { mutableStateOf<ByteArray?>(null) }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        if (privateTo != null) {
            Row(
                Modifier.fillMaxWidth().background(c.primaryContainer)
                    .padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Lock, null, tint = c.onPrimaryContainer, modifier = Modifier.size(14.dp))
                Text("Private chat with $privateTo", fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    color = c.onPrimaryContainer, modifier = Modifier.weight(1f).padding(start = 6.dp))
                Box(Modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClosePrivate),
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Close, "Back to #$channelName", tint = c.onPrimaryContainer,
                        modifier = Modifier.size(18.dp))
                }
            }
        } else {
            Text("# $channelName", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVar,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        }
        Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(messages) { msg ->
                MessageRow(
                    msg,
                    onImageClick = { viewerImage = it },
                    onOpenPrivate = { s, n -> onReplyPrivately(s, n) },
                )
            }
        }
        Composer(
            placeholder = if (privateTo != null) "Message $privateTo privately" else "Message #$channelName",
            onSend = onSend,
            onAttach = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    viewerImage?.let { bytes ->
        FullScreenImageViewer(bytes) { viewerImage = null }
    }
}

@Composable
private fun MessageRow(
    msg: UiMessage,
    onImageClick: (ByteArray) -> Unit,
    onOpenPrivate: (session: Int, name: String) -> Unit,
) {
    val c = MumbleTheme.colors
    val isPrivate = msg.privateWith != null
    // Private bubbles get a primary outline so they stand out from channel chatter; tapping one
    // opens (or returns to) the private chat with that user.
    fun privateMod(shape: androidx.compose.ui.graphics.Shape): Modifier {
        val peer = msg.privateWith ?: return Modifier
        val session = msg.privateSession ?: return Modifier.border(1.5.dp, c.primary, shape)
        return Modifier.clip(shape).border(1.5.dp, c.primary, shape).clickable { onOpenPrivate(session, peer) }
    }
    when (msg.kind) {
        ChatKind.SYSTEM -> Text(
            msg.text, fontSize = 12.sp, color = c.onSurfaceVar,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        )
        ChatKind.ME -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (isPrivate) PrivateLabel("Private to ${msg.privateWith}")
            if (msg.imageBytes != null) {
                InlineImage(msg, modifier = privateMod(RoundedCornerShape(14.dp)), onClick = { onImageClick(msg.imageBytes) })
            } else {
                val shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
                Box(
                    privateMod(shape).clip(shape)
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
                    if (isPrivate) PrivateLabel("Private")
                }
                if (msg.imageBytes != null) {
                    InlineImage(msg, modifier = Modifier.padding(top = 3.dp).then(privateMod(RoundedCornerShape(14.dp))),
                        onClick = { onImageClick(msg.imageBytes) })
                }
                if (msg.text.isNotBlank()) {
                    val shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)
                    Box(
                        Modifier.padding(top = 3.dp).then(privateMod(shape)).clip(shape)
                            .background(c.surfHigh).padding(horizontal = 12.dp, vertical = 9.dp),
                    ) { Text(msg.text, color = c.onSurface, fontSize = 14.sp) }
                }
            }
        }
    }
}

@Composable
private fun PrivateLabel(text: String) {
    val c = MumbleTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 2.dp)) {
        Icon(Icons.Filled.Lock, null, tint = c.primary, modifier = Modifier.size(11.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = c.primary,
            modifier = Modifier.padding(start = 3.dp))
    }
}

@Composable
private fun InlineImage(msg: UiMessage, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val bytes = msg.imageBytes ?: return
    val (imgW, imgH) = remember(bytes) { ChatImages.bounds(bytes) } ?: return

    // Size the thumbnail box to the image's own aspect ratio, bounded so it never overflows.
    val maxW = 240f
    val maxH = 300f
    val aspect = imgW.toFloat() / imgH.toFloat().coerceAtLeast(1f)
    val (wDp, hDp) = if (aspect >= maxW / maxH) maxW to (maxW / aspect) else (maxH * aspect) to maxH
    val box = modifier.size(wDp.dp, hDp.dp).clip(RoundedCornerShape(14.dp))

    val maxPx = with(LocalDensity.current) { maxOf(wDp, hDp).dp.roundToPx() }
    val bitmap by rememberChatImage(bytes, maxPx)
    val loaded = bitmap
    if (loaded == null) {
        // Placeholder of the final size while decoding, so the chat doesn't jump.
        Box(box.background(MumbleTheme.colors.surfHigh))
        return
    }

    Image(
        bitmap = loaded,
        contentDescription = "Shared image — tap to view",
        modifier = box.clickable(onClick = onClick),
        contentScale = ContentScale.Fit,
    )
}

/** Full-screen image viewer with pinch-to-zoom and pan; back or the close button dismisses. */
@Composable
private fun FullScreenImageViewer(bytes: ByteArray, onClose: () -> Unit) {
    // Enough resolution for some pinch-zoom, capped so a huge photo can't blow up memory.
    val bitmap by rememberChatImage(bytes, maxPx = 2048)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            bitmap?.let { img ->
                var scale by remember { mutableStateOf(1f) }
                var offset by remember { mutableStateOf(Offset.Zero) }
                Image(
                    bitmap = img,
                    contentDescription = "Full image",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 6f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offset.x, translationY = offset.y,
                        ),
                )
            }
            Box(
                Modifier.align(Alignment.TopEnd).padding(16.dp).size(40.dp).clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.5f)).clickable(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
        }
    }
}

@Composable
private fun Composer(placeholder: String, onSend: (String) -> Unit, onAttach: () -> Unit) {
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
                    if (text.isEmpty()) Text(placeholder, color = c.onSurfaceVar, fontSize = 14.sp)
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
