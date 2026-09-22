package app.notmumla.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.theme.MumbleTheme

/** Circular initial avatar, optionally pulsing when speaking. */
@Composable
fun Avatar(
    initials: String,
    color: Color,
    size: Dp = 40.dp,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (dimmed) color.copy(alpha = 0.55f) else color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

/**
 * Three animated bars indicating active speech (design `@keyframes bar`). Heights are read only in
 * the draw phase, so each animation frame is a redraw — no recomposition or relayout. With
 * [animate] false the bars sit still (no running animation at all).
 */
@Composable
fun SpeakingBars(
    color: Color = MumbleTheme.colors.speaking,
    barWidth: Dp = 3.dp,
    maxHeight: Dp = 14.dp,
    animate: Boolean = true,
) {
    val heights: List<State<Float>> = if (animate) {
        val transition = rememberInfiniteTransition(label = "speaking")
        listOf(0, 150, 300).map { delay ->
            transition.animateFloat(
                initialValue = 0.4f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 900, delayMillis = delay),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "bar",
            )
        }
    } else {
        remember { List(3) { mutableFloatStateOf(0.4f) } }
    }
    val gap = 2.dp
    Canvas(Modifier.size(width = barWidth * 3 + gap * 2, height = maxHeight)) {
        val w = barWidth.toPx()
        val step = w + gap.toPx()
        val radius = CornerRadius(2.dp.toPx())
        heights.forEachIndexed { i, h ->
            val barH = size.height * h.value
            drawRoundRect(
                color = color,
                topLeft = Offset(i * step, size.height - barH),
                size = Size(w, barH),
                cornerRadius = radius,
            )
        }
    }
}

/** Pill segmented control matching the design's rounded toggle group. */
@Composable
fun SegmentedToggle(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = MumbleTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(999.dp))
            .background(c.surfHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) c.primary else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (selected) c.onPrimary else c.onSurfaceVar,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
