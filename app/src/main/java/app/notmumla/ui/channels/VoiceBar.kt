package app.notmumla.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Headset
import androidx.compose.material.icons.filled.HeadsetOff
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.SpeakingBars
import app.notmumla.ui.TransmissionMode
import app.notmumla.ui.theme.MumbleTheme

/**
 * Bottom voice control bar: mute, the big push-to-talk button (or VAD indicator), and deafen.
 * Transmission mode is chosen in Settings; this bar only reflects it.
 */
@Composable
fun VoiceBar(
    mode: TransmissionMode,
    muted: Boolean,
    deafened: Boolean,
    transmitting: Boolean,
    /** Name of the user we're whispering to, or null for normal talk. */
    whisperingTo: String?,
    onStopWhisper: () -> Unit,
    onPttHeld: (Boolean) -> Unit,
    onToggleMute: () -> Unit,
    onToggleDeafen: () -> Unit,
) {
    val c = MumbleTheme.colors

    // Transmission mode (PTT vs Voice-Activated) is chosen in Settings — the bar just reflects it.
    Column(
        Modifier.fillMaxWidth().background(c.surfContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (whisperingTo != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(c.primaryContainer)
                    .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Whispering to $whisperingTo",
                    Modifier.weight(1f),
                    color = c.onPrimaryContainer, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                    maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                androidx.compose.material3.TextButton(onClick = onStopWhisper) {
                    Text("Stop", color = c.onPrimaryContainer)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircleButton(
                bg = if (muted) c.muted else c.surfHigh,
                fg = if (muted) Color.White else c.onSurfaceVar,
                icon = if (muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                desc = "Mute",
                onClick = onToggleMute,
            )

            // Big PTT / VAD button. `transmitting` reflects the engine's live state.
            val active = transmitting || (mode == TransmissionMode.VAD)
            Box(
                Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(28.dp))
                    .background(if (active) c.primary else c.surfHigh)
                    .pointerInput(mode) {
                        detectTapGestures(
                            onPress = {
                                if (mode == TransmissionMode.PTT) {
                                    onPttHeld(true)
                                    tryAwaitRelease()
                                    onPttHeld(false)
                                }
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (mode == TransmissionMode.VAD) {
                        // Only animate while the VAD is actually sending; idle VAD shows still bars
                        // instead of running a frame-rate animation for the whole session.
                        SpeakingBars(color = c.onPrimary, maxHeight = 16.dp, barWidth = 4.dp,
                            animate = transmitting)
                    } else {
                        Icon(Icons.Filled.Mic, null,
                            tint = if (active) c.onPrimary else c.onSurfaceVar)
                    }
                    Text(
                        when {
                            mode == TransmissionMode.VAD -> "Voice Activated"
                            transmitting -> if (whisperingTo != null) "Whispering…" else "Transmitting…"
                            whisperingTo != null -> "Hold to Whisper"
                            else -> "Hold to Talk"
                        },
                        color = if (active) c.onPrimary else c.onSurface,
                        fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    )
                }
            }

            CircleButton(
                bg = if (deafened) c.muted else c.surfHigh,
                fg = if (deafened) Color.White else c.onSurfaceVar,
                icon = if (deafened) Icons.Filled.HeadsetOff else Icons.Filled.Headset,
                desc = "Deafen",
                onClick = onToggleDeafen,
            )
        }
    }
}

@Composable
private fun CircleButton(
    bg: Color,
    fg: Color,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = fg) }
}
