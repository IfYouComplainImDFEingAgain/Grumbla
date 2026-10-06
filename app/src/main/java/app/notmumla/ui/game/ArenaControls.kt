package app.notmumla.ui.game

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.hypot
import kotlin.math.roundToInt

// Touch controls shared by the full-screen arena games.

/** Swallow every touch the controls don't take, or it reaches the channel list underneath. */
internal fun Modifier.swallowTouches() = pointerInput(Unit) {
    awaitEachGesture {
        do {
            val e = awaitPointerEvent()
            e.changes.forEach { it.consume() }
        } while (e.changes.any { it.pressed })
    }
}

/** How far above the bottom edge thumbs rest: well above it on a tall portrait screen. */
internal fun controlLift(maxWidth: Dp, maxHeight: Dp): Dp =
    16.dp + maxHeight * (if (maxHeight > maxWidth * 1.3f) 0.12f else 0.04f)

@Composable
internal fun HudButton(label: String, color: Color, onClick: () -> Unit) {
    Text(
        label, color = color, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, color, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Held = true from finger down to finger up; works alongside a finger on the stick. */
@Composable
internal fun HoldButton(label: String, sizeDp: Int, color: Color, onHeld: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape)
            .background(if (held) color.copy(alpha = 0.35f) else Color.Transparent)
            .border(2.dp, color, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    held = true; onHeld(true)
                    waitForUpOrCancellation()
                    held = false; onHeld(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            fontSize = if (sizeDp < 70) 12.sp else 14.sp,
        )
    }
}

/** A virtual stick, −1..1 on each axis (y grows downward); it springs back to centre on release. */
@Composable
internal fun Stick(modifier: Modifier, value: Offset, color: Color, onChange: (Offset) -> Unit) {
    val sizeDp = 150
    Box(
        modifier.size(sizeDp.dp).clip(CircleShape).border(2.dp, color.copy(alpha = 0.7f), CircleShape)
            .pointerInput(Unit) {
                val r = size.width / 2f
                fun at(p: Offset): Offset {
                    val d = (p - Offset(r, r)) / r
                    val len = hypot(d.x, d.y)
                    return if (len > 1f) d / len else d
                }
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    onChange(at(down.position))
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        if (c.positionChange() != Offset.Zero) c.consume()
                        onChange(at(c.position))
                    }
                    onChange(Offset.Zero)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val travel = sizeDp / 2 - 26
        Box(
            Modifier.offset { IntOffset((value.x * travel.dp.toPx()).roundToInt(), (value.y * travel.dp.toPx()).roundToInt()) }
                .size(52.dp).clip(CircleShape).background(color.copy(alpha = 0.45f)),
        )
    }
}
