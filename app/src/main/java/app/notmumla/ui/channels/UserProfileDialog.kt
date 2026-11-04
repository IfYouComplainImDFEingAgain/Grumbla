package app.notmumla.ui.channels

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.data.ImageUtil
import app.notmumla.ui.Avatar
import app.notmumla.ui.UiUserProfile
import app.notmumla.ui.chat.ChatHtml
import app.notmumla.ui.chat.ChatLinks
import app.notmumla.ui.chat.rememberChatImage
import app.notmumla.ui.theme.MonoFamily
import app.notmumla.ui.theme.MumbleTheme

/**
 * Another user's profile: avatar, name, registration and their comment. The comment and avatar are
 * whatever the server relays from that user, so they get the same treatment as chat: the limited
 * HTML renderer, vetted links, and off-thread downsampled image decoding.
 */
@Composable
internal fun UserProfileDialog(
    profile: UiUserProfile,
    onRequestBlobs: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = MumbleTheme.colors
    val context = LocalContext.current
    // Ask once per user when opened; the answer lands in live state and recomposes this dialog.
    LaunchedEffect(profile.id) { if (profile.commentPending || profile.avatarPending) onRequestBlobs() }
    var confirmLink by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surfContainer,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                ProfileAvatar(profile)
                Column {
                    Text(profile.name, fontWeight = FontWeight.Bold, color = c.onSurface, fontSize = 20.sp)
                    Text(
                        listOfNotNull(if (profile.registered) "Registered" else "Guest", profile.channelName)
                            .joinToString(" · "),
                        fontSize = 13.sp, color = c.onSurfaceVar,
                    )
                }
            }
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                val html = profile.commentHtml
                when {
                    html != null -> CommentBody(html) { url, shown ->
                        if (ChatLinks.opensWhatItShows(url, shown)) ChatLinks.open(context, url) else confirmLink = url
                    }
                    profile.commentPending -> Text("Loading comment…", fontSize = 14.sp, color = c.onSurfaceVar)
                    else -> Text("No comment.", fontSize = 14.sp, color = c.onSurfaceVar)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )

    confirmLink?.let { url ->
        AlertDialog(
            onDismissRequest = { confirmLink = null },
            containerColor = c.surfContainer,
            title = { Text("Open link?", fontWeight = FontWeight.Bold, color = c.onSurface) },
            text = { Text(url, color = c.onSurfaceVar, fontFamily = MonoFamily, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { confirmLink = null; ChatLinks.open(context, url) }) { Text("Open") }
            },
            dismissButton = { TextButton(onClick = { confirmLink = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ProfileAvatar(profile: UiUserProfile) {
    val size = 64.dp
    val bytes = profile.avatar
    val image = bytes?.let { rememberChatImage(it, 256).value }
    when {
        image != null -> Image(
            image, "${profile.name}'s avatar",
            modifier = Modifier.size(size).clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
        profile.avatarPending -> CircularProgressIndicator(Modifier.size(size), strokeWidth = 2.dp)
        else -> Avatar(initialsFor(profile.name), avatarColorFor(profile.name), size = size)
    }
}

/** The comment's text, then its first inline image (Mumble comments often carry a banner). */
@Composable
private fun CommentBody(html: String, onLink: (url: String, shown: String) -> Unit) {
    val c = MumbleTheme.colors
    val currentOnLink by rememberUpdatedState(onLink)
    val image = remember(html) { ImageUtil.extractImage(html) }
    val text = remember(html, c) {
        ChatHtml.render(if (image != null) ImageUtil.stripImageTags(html) else html, c.primary, c.surfContainer) { u, t ->
            currentOnLink(u, t)
        }
    }
    if (text.isNotBlank()) Text(text, fontSize = 15.sp, color = c.onSurface)
    if (image != null) {
        val bmp by rememberChatImage(image, 720)
        bmp?.let {
            if (text.isNotBlank()) Spacer(Modifier.height(12.dp))
            Image(it, null, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.FillWidth)
        }
    }
}
