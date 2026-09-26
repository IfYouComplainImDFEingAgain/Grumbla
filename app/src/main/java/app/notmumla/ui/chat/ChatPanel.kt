package app.notmumla.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatColorReset
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.scrollBy
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.notmumla.data.ChatMarkdown
import app.notmumla.ui.Avatar
import app.notmumla.ui.ChatKind
import app.notmumla.ui.MockData
import app.notmumla.ui.UiMessage
import app.notmumla.ui.theme.MumbleTheme
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun ChatPanel(
    /** Send [text]; [html] is set when the rich composer formatted it (else [text] is Markdown). */
    onSend: (text: String, html: String?) -> Unit = { _, _ -> },
    onSendImage: (Uri) -> Unit = {},
    messages: List<UiMessage> = MockData.messages,
    channelName: String = "General",
    /** When set, the composer sends private messages to this user instead of the channel. */
    privateTo: String? = null,
    onReplyPrivately: (session: Int, name: String) -> Unit = { _, _ -> },
    onClosePrivate: () -> Unit = {},
    onDeleteMessage: (id: Int) -> Unit = {},
    onClearChat: () -> Unit = {},
    /** Show the WYSIWYG formatting toolbar instead of typing Markdown. */
    richComposer: Boolean = false,
) {
    val c = MumbleTheme.colors
    val pickImage = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let(onSendImage) }
    var viewerImage by remember { mutableStateOf<ByteArray?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val pressClaim = remember { PressClaim() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var lastListHeight by remember { mutableStateOf(0) }
    val context = LocalContext.current
    var confirmLink by remember { mutableStateOf<String?>(null) }
    val onLink: (String, String) -> Unit = { url, shown ->
        if (ChatLinks.opensWhatItShows(url, shown)) ChatLinks.open(context, url) else confirmLink = url
    }

    // The panel is recreated on every tab switch, so open at the newest message.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = messages.lastIndex.coerceAtLeast(0))
    // Keyed on the newest id, not the count: the history is capped, so at the cap size stays flat.
    var lastSeenId by remember { mutableStateOf(messages.lastOrNull()?.id) }
    LaunchedEffect(messages.lastOrNull()?.id) {
        // Forget the old newest once the history is cleared, or the next message looks like it
        // arrived while scrolled up and wouldn't be followed.
        val newest = messages.lastOrNull() ?: run { lastSeenId = null; return@LaunchedEffect }
        val prevId = lastSeenId
        lastSeenId = newest.id
        if (newest.id == prevId) return@LaunchedEffect
        // Only follow new messages if the previous newest was on screen (or we sent it), so
        // reading scrollback isn't yanked away by incoming chatter.
        val wasAtBottom = prevId == null || listState.layoutInfo.visibleItemsInfo.any { it.key == prevId }
        if (wasAtBottom || newest.kind == ChatKind.ME) listState.animateScrollToItem(messages.lastIndex)
    }

    Column(Modifier.fillMaxSize().background(c.surface)) {
        Text("# $channelName", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onSurfaceVar,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
        Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth()
                // Keep the bottom edge anchored when the list shrinks (keyboard opening), like
                // messaging apps, instead of LazyColumn's default of pinning the top.
                .onSizeChanged { size ->
                    val shrunk = lastListHeight - size.height
                    lastListHeight = size.height
                    if (shrunk > 0) scope.launch { listState.scrollBy(shrunk.toFloat()) }
                }
                .pointerInput(Unit) { detectFreeSpaceLongPress(pressClaim) { confirmClear = true } }
                .padding(14.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(messages, key = { it.id }) { msg ->
                MessageRow(
                    msg,
                    onImageClick = { viewerImage = it },
                    onOpenPrivate = { s, n -> onReplyPrivately(s, n) },
                    onDelete = { onDeleteMessage(msg.id) },
                    pressClaim = pressClaim,
                    onLink = onLink,
                )
            }
        }
        Composer(
            placeholder = if (privateTo != null) "Message $privateTo privately" else "Message #$channelName",
            privateTo = privateTo,
            onClosePrivate = onClosePrivate,
            onSend = onSend,
            rich = richComposer,
            onAttach = {
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.surfContainer,
            title = { Text("Clear chat history?", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = {
                Text("Removes every message from this device. Others in the channel still have them.",
                    color = c.onSurfaceVar)
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClearChat() }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }

    confirmLink?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmLink = null },
            containerColor = c.surfContainer,
            title = { Text("Open link?", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = {
                // The link's text didn't match where it goes, so show the real destination.
                Text(url, color = c.onSurfaceVar, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 13.sp)
            },
            confirmButton = {
                TextButton(onClick = { confirmLink = null; ChatLinks.open(context, url) }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { confirmLink = null }) { Text("Cancel") } },
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
    onDelete: () -> Unit,
    pressClaim: PressClaim,
    onLink: (url: String, shown: String) -> Unit,
) {
    val c = MumbleTheme.colors
    val isPrivate = msg.privateWith != null
    // Tapping a private bubble opens (or returns to) the private chat with that user.
    val openPrivate: (() -> Unit)? = msg.privateWith?.let { peer ->
        msg.privateSession?.let { session -> { onOpenPrivate(session, peer) } }
    }
    // Private bubbles get a primary outline so they stand out from channel chatter.
    fun privateMod(shape: androidx.compose.ui.graphics.Shape): Modifier =
        if (isPrivate) Modifier.clip(shape).border(1.5.dp, c.primary, shape) else Modifier
    when (msg.kind) {
        ChatKind.SYSTEM -> MessageMenu(msg.text, onDelete, pressClaim, onTap = null) { hold ->
            Text(
                msg.text, fontSize = 12.sp, color = c.onSurfaceVar,
                modifier = Modifier.fillMaxWidth().then(hold).padding(vertical = 2.dp),
            )
        }
        ChatKind.ME -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
            if (isPrivate) PrivateLabel("Private to ${msg.privateWith}")
            if (msg.imageBytes != null) {
                MessageMenu(null, onDelete, pressClaim, onTap = { onImageClick(msg.imageBytes) }) { hold ->
                    InlineImage(msg, modifier = privateMod(RoundedCornerShape(14.dp)), hold = hold)
                }
            } else {
                val shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp)
                MessageMenu(msg.text, onDelete, pressClaim, onTap = openPrivate) { hold ->
                    Box(
                        privateMod(shape).clip(shape).then(hold)
                            .background(c.primaryContainer).padding(horizontal = 12.dp, vertical = 9.dp),
                    ) { RichText(msg, color = c.onPrimaryContainer, linkColor = c.onPrimaryContainer,
                        background = c.primaryContainer, onLink = onLink) }
                }
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
                    MessageMenu(null, onDelete, pressClaim, onTap = { onImageClick(msg.imageBytes) }) { hold ->
                        InlineImage(msg, modifier = Modifier.padding(top = 3.dp).then(privateMod(RoundedCornerShape(14.dp))),
                            hold = hold)
                    }
                }
                if (msg.text.isNotBlank()) {
                    val shape = RoundedCornerShape(4.dp, 14.dp, 14.dp, 14.dp)
                    MessageMenu(msg.text, onDelete, pressClaim, onTap = openPrivate) { hold ->
                        Box(
                            Modifier.padding(top = 3.dp).then(privateMod(shape)).clip(shape).then(hold)
                                .background(c.surfHigh).padding(horizontal = 12.dp, vertical = 9.dp),
                        ) { RichText(msg, color = c.onSurface, linkColor = c.primary,
                            background = c.surfHigh, onLink = onLink) }
                    }
                }
            }
        }
    }
}

/**
 * Wraps a message element so a long-press opens its menu (copy text, delete locally). [content]
 * receives the gesture modifier to apply after its clip, so the ripple follows the bubble shape.
 * Tap and hold share one modifier: separate clickables would swallow each other's long-press.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageMenu(
    copyText: String?,
    onDelete: () -> Unit,
    pressClaim: PressClaim,
    onTap: (() -> Unit)?,
    content: @Composable (hold: Modifier) -> Unit,
) {
    val c = MumbleTheme.colors
    val clipboard = LocalClipboardManager.current
    var open by remember { mutableStateOf(false) }
    Box {
        content(Modifier.claimPress(pressClaim).combinedClickable(onClick = { onTap?.invoke() }, onLongClick = { open = true }))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = c.surfContainer) {
            if (!copyText.isNullOrBlank()) {
                DropdownMenuItem(
                    text = { Text("Copy text", color = c.onSurface) },
                    leadingIcon = { Icon(Icons.Filled.ContentCopy, null, tint = c.onSurfaceVar) },
                    onClick = { clipboard.setText(AnnotatedString(copyText)); open = false },
                )
            }
            // "for me": Mumble can't retract a message, so others still have it.
            DropdownMenuItem(
                text = { Text("Delete for me", color = c.onSurface) },
                leadingIcon = { Icon(Icons.Filled.Delete, null, tint = c.onSurfaceVar) },
                onClick = { open = false; onDelete() },
            )
        }
    }
}

/**
 * Lets the chat list tell a press on a message apart from one on empty space. Main-pass events
 * reach children before parents, so a message marks the down as its own before the list sees it.
 */
private class PressClaim { var claimed = false }

private fun Modifier.claimPress(claim: PressClaim): Modifier = pointerInput(claim) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        claim.claimed = true
    }
}

/** Long-press on the list's empty space. Scrolling consumes the move, which cancels it. */
private suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectFreeSpaceLongPress(
    claim: PressClaim,
    onLongPress: () -> Unit,
) = awaitEachGesture {
    awaitFirstDown(requireUnconsumed = false)
    if (claim.claimed) { claim.claimed = false; return@awaitEachGesture }
    var ended = false
    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
        waitForUpOrCancellation()
        ended = true
    }
    if (!ended) onLongPress()
}

/** The message's rich text (Mumble HTML); a tap on a link doesn't reach the bubble's own tap/hold. */
@Composable
private fun RichText(
    msg: UiMessage,
    color: Color,
    linkColor: Color,
    background: Color,
    onLink: (url: String, shown: String) -> Unit,
) {
    val currentOnLink by androidx.compose.runtime.rememberUpdatedState(onLink)
    val rendered = remember(msg.html, msg.text, linkColor, background) {
        ChatHtml.render(msg.html ?: ChatMarkdown.escape(msg.text), linkColor, background) { u, t -> currentOnLink(u, t) }
    }
    Text(rendered, color = color, fontSize = 14.sp)
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
private fun InlineImage(msg: UiMessage, modifier: Modifier = Modifier, hold: Modifier) {
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
        Box(box.then(hold).background(MumbleTheme.colors.surfHigh))
        return
    }

    Image(
        bitmap = loaded,
        contentDescription = "Shared image — tap to view, hold for options",
        modifier = box.then(hold),
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
private fun Composer(
    placeholder: String,
    privateTo: String?,
    onClosePrivate: () -> Unit,
    onSend: (text: String, html: String?) -> Unit,
    rich: Boolean,
    onAttach: () -> Unit,
) {
    val c = MumbleTheme.colors
    var text by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf(RichDraft()) }
    Box(Modifier.fillMaxWidth().size(1.dp).background(c.outlineVariant))
    if (rich) FormatBar(draft, onChange = { draft = it })
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onAttach),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Add, "Attach image", tint = c.onSurfaceVar, modifier = Modifier.size(24.dp))
        }
        Row(Modifier.weight(1f).clip(RoundedCornerShape(20.dp)).background(c.surfHigh)
            .padding(start = if (privateTo != null) 6.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // Private-mode chip: shows who we're messaging and is the way back to the channel.
            if (privateTo != null) {
                Row(
                    Modifier.padding(end = 8.dp).clip(RoundedCornerShape(14.dp)).background(c.primaryContainer)
                        .clickable(onClick = onClosePrivate).padding(start = 8.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Lock, null, tint = c.onPrimaryContainer, modifier = Modifier.size(12.dp))
                    Text(privateTo, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = c.onPrimaryContainer,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 4.dp).widthIn(max = 110.dp))
                    Icon(Icons.Filled.Close, "Stop messaging $privateTo privately", tint = c.onPrimaryContainer,
                        modifier = Modifier.size(14.dp))
                }
            }
            val textStyle = LocalTextStyle.current.copy(color = c.onSurface, fontSize = 14.sp)
            val fieldMod = Modifier.weight(1f).padding(vertical = 11.dp)
            val empty = if (rich) draft.text.isEmpty() else text.isEmpty()
            val decoration: @Composable (@Composable () -> Unit) -> Unit = { inner ->
                if (empty) Text(placeholder, color = c.onSurfaceVar, fontSize = 14.sp)
                inner()
            }
            if (rich) {
                BasicTextField(
                    value = draft.value,
                    onValueChange = { draft = draft.edit(it) },
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(c.primary),
                    visualTransformation = RichDraftTransformation(draft.styles),
                    modifier = fieldMod,
                    decorationBox = decoration,
                )
            } else {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = SolidColor(c.primary),
                    modifier = fieldMod,
                    decorationBox = decoration,
                )
            }
        }
        val send = {
            if (rich) {
                val d = draft.trimmed()
                if (d.text.isNotEmpty()) { onSend(d.text, d.toHtml()); draft = RichDraft() }
            } else {
                val t = text.trim()
                if (t.isNotEmpty()) { onSend(t, null); text = "" }
            }
        }
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).background(c.primary)
                .clickable { send() },
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = c.onPrimary, modifier = Modifier.size(20.dp)) }
    }
}

private val TEXT_COLORS = listOf(0xE53935, 0xF57C00, 0xFBC02D, 0x43A047, 0x1E88E5, 0x8E24AA, 0xD81B60)

/** WYSIWYG toolbar: toggles apply to the selection, or to what's typed next when nothing is selected. */
@Composable
private fun FormatBar(draft: RichDraft, onChange: (RichDraft) -> Unit) {
    val c = MumbleTheme.colors
    var colors by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = 60.dp, end = 12.dp, top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        @Composable
        fun toggle(f: Format, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String) {
            val on = draft.isOn(f)
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.primaryContainer else Color.Transparent)
                    .clickable { onChange(draft.toggle(f)) },
                contentAlignment = Alignment.Center,
            ) { Icon(icon, label, tint = if (on) c.onPrimaryContainer else c.onSurfaceVar, modifier = Modifier.size(20.dp)) }
        }
        toggle(Format.BOLD, Icons.Filled.FormatBold, "Bold")
        toggle(Format.ITALIC, Icons.Filled.FormatItalic, "Italic")
        toggle(Format.UNDERLINE, Icons.Filled.FormatUnderlined, "Underline")
        toggle(Format.STRIKE, Icons.Filled.FormatStrikethrough, "Strikethrough")
        toggle(Format.CODE, Icons.Filled.Code, "Code")
        val current = if (draft.value.selection.collapsed) draft.typingStyle.color
            else draft.styles.getOrNull(draft.value.selection.min)?.color
        Box {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).clickable { colors = true },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.FormatColorText, "Text color",
                    tint = current?.let { Color(0xFF000000 or it.toLong()) } ?: c.onSurfaceVar,
                    modifier = Modifier.size(20.dp))
            }
            DropdownMenu(expanded = colors, onDismissRequest = { colors = false }, containerColor = c.surfContainer) {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    // Default color first: clears any color.
                    Box(
                        Modifier.size(28.dp).clip(CircleShape).border(1.5.dp, c.onSurfaceVar, CircleShape)
                            .clickable { onChange(draft.color(null)); colors = false },
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Filled.FormatColorReset, "Default color", tint = c.onSurfaceVar, modifier = Modifier.size(16.dp)) }
                    TEXT_COLORS.forEach { rgb ->
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).background(Color(0xFF000000 or rgb.toLong()))
                                .clickable { onChange(draft.color(rgb)); colors = false },
                        )
                    }
                }
            }
        }
    }
}
