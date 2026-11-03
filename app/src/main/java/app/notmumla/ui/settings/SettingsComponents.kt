package app.notmumla.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.ScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.ui.theme.MumbleTheme
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlin.math.roundToInt

/* Building blocks shared by the settings hub and its sub-pages (design 03A–03.9). */

/** Sub-page chrome: back button + title, then a scrolling column with the design's 14dp gutter. */
@Composable
internal fun SettingsPageScaffold(
    title: String,
    onBack: () -> Unit,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MumbleTheme.colors
    Column(Modifier.fillMaxSize().background(c.surface)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(Modifier.size(42.dp).clip(CircleShape).clickable(onClick = onBack),
                contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.onSurface)
            }
            Text(title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = c.onSurface)
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll)
                .padding(start = 14.dp, end = 14.dp, bottom = 18.dp),
            content = content,
        )
    }
}

@Composable
internal fun SectionLabel(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 7.dp, start = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = MumbleTheme.colors.primary, fontWeight = FontWeight.Bold, fontSize = 12.sp,
            letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Gap the design puts above a group that has no section label. */
@Composable
internal fun GroupGap() = Spacer(Modifier.height(12.dp))

@Composable
internal fun SettingsGroup(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MumbleTheme.colors.surfContainer),
        content = content,
    )
}

@Composable
internal fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(MumbleTheme.colors.outlineVariant))
}

@Composable
internal fun RowBase(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    trailing: @Composable () -> Unit,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    indent: Boolean = false, // no icon, but text aligned with the iconned rows above
) {
    val c = MumbleTheme.colors
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null && enabled) it.clickable(onClick = onClick) else it }
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f).padding(start = if (indent && icon == null) 36.dp else 0.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = c.onSurfaceVar)
        }
        trailing()
    }
}

@Composable
internal fun NavRow(icon: ImageVector, title: String, subtitle: String?,
                    trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    RowBase(icon, title, subtitle, trailing = { trailing(); Chevron() }, onClick = onClick)
}

@Composable
internal fun ValueRow(icon: ImageVector, title: String, value: String, onClick: (() -> Unit)? = null) {
    RowBase(icon, title, null, trailing = {
        Text(value, fontSize = 14.sp, color = MumbleTheme.colors.onSurfaceVar)
    }, onClick = onClick)
}

@Composable
internal fun ToggleRow(icon: ImageVector?, title: String, subtitle: String?,
                       checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true,
                       indent: Boolean = false) {
    RowBase(icon, title, subtitle, trailing = { ToggleSwitch(checked, onChange, enabled) },
        onClick = { onChange(!checked) }, enabled = enabled, indent = indent)
}

@Composable
internal fun ToggleSwitch(checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val c = MumbleTheme.colors
    val shape = RoundedCornerShape(13.dp)
    Box(
        Modifier.size(width = 46.dp, height = 26.dp).clip(shape)
            .background(if (checked) c.primary else c.surfHighest)
            .let { if (checked) it else it.border(2.dp, c.outline, shape) }
            .clickable(enabled = enabled) { onChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.padding(start = if (checked) 23.dp else 6.dp)
                .size(if (checked) 20.dp else 14.dp).clip(CircleShape)
                .background(if (checked) c.onPrimary else c.outline),
        )
    }
}

/** A row whose title/subtitle sit above a full-width control (segmented toggle, slider…). */
@Composable
internal fun StackedRow(
    icon: ImageVector?,
    title: String,
    subtitle: String?,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MumbleTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (icon != null) Icon(icon, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = c.onSurfaceVar)
            }
            trailing?.invoke()
        }
        content()
    }
}

/**
 * The settings pages' segmented control: the selected segment is a raised surface pill (design 03.x),
 * unlike the primary-filled [app.notmumla.ui.SegmentedToggle] used on the main screens.
 */
@Composable
internal fun SettingsSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier.padding(start = 36.dp),
) {
    val c = MumbleTheme.colors
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(999.dp)).background(c.surfHigh).padding(3.dp)) {
        options.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            Box(
                Modifier.weight(1f)
                    .let { if (selected) it.shadow(1.5.dp, RoundedCornerShape(999.dp)) else it }
                    .clip(RoundedCornerShape(999.dp))
                    .background(if (selected) c.surface else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (selected) c.onSurface else c.onSurfaceVar,
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** "Label ……… value" line above an inline slider. */
@Composable
internal fun ValueLine(label: String, value: String, modifier: Modifier = Modifier.padding(start = 36.dp)) {
    val c = MumbleTheme.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface,
            modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, color = c.onSurfaceVar, fontFamily = FontFamily.Monospace)
    }
}

/**
 * The design's slider: 6dp track, primary fill, round thumb with a soft shadow. [steps] > 0 makes it
 * discrete with dot ticks at each stop (filled ticks on-primary, empty ones outline).
 */
@Composable
internal fun DesignSlider(
    value: Float, // 0..1
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier.padding(start = 45.dp, end = 9.dp),
    steps: Int = 0, // number of stops, including both ends; 0 = continuous
    thumbSize: Dp = 18.dp,
) {
    val c = MumbleTheme.colors
    val onChangeNow by rememberUpdatedState(onChange)
    fun snap(f: Float): Float {
        val v = f.coerceIn(0f, 1f)
        return if (steps > 1) (v * (steps - 1)).roundToInt() / (steps - 1).toFloat() else v
    }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(thumbSize + 6.dp)
            .pointerInput(steps) {
                detectTapGestures { onChangeNow(snap(it.x / size.width)) }
            }
            .pointerInput(steps) {
                detectHorizontalDragGestures { change, _ -> onChangeNow(snap(change.position.x / size.width)) }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val v = value.coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.surfHighest))
        Box(Modifier.fillMaxWidth(v).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c.primary))
        if (steps > 1) {
            for (i in 0 until steps) {
                val f = i / (steps - 1).toFloat()
                Box(
                    Modifier.offset(x = maxWidth * f - 2.dp).size(4.dp).clip(CircleShape)
                        .background(if (f <= v + 1e-4f) c.onPrimary else c.outline),
                )
            }
        }
        Box(
            Modifier.offset(x = maxWidth * v - thumbSize / 2).size(thumbSize)
                .shadow(2.dp, CircleShape).clip(CircleShape).background(c.primary),
        )
    }
}

/** Small pill button: tonal (primary container) or outlined. */
@Composable
internal fun PillButton(text: String, onClick: () -> Unit, tonal: Boolean, enabled: Boolean = true) {
    val c = MumbleTheme.colors
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier.clip(shape)
            .let { if (tonal) it.background(c.primaryContainer) else it.border(1.dp, c.outlineVariant, shape) }
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 9.dp),
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (tonal) c.onPrimaryContainer else c.primary)
    }
}

/**
 * Collapsible "Advanced" block. Collapsed: a dashed card listing what's inside. Expanded: an
 * ADVANCED section label with a Hide control, followed by [content] in a normal group.
 */
@Composable
internal fun AdvancedSection(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    summary: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = MumbleTheme.colors
    if (!expanded) {
        val dash = c.outlineVariant
        Row(
            Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .drawBehind {
                    drawRoundRect(
                        dash, cornerRadius = CornerRadius(18.dp.toPx()),
                        style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))),
                    )
                }
                .clickable { onExpandedChange(true) }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(Icons.Filled.Tune, null, tint = c.onSurfaceVar, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text("Advanced", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                Text(summary, fontSize = 13.sp, color = c.onSurfaceVar)
            }
            Icon(Icons.Filled.KeyboardArrowDown, "Show advanced", tint = c.onSurfaceVar,
                modifier = Modifier.size(20.dp))
        }
    } else {
        SectionLabel("ADVANCED") {
            Row(
                Modifier.clip(RoundedCornerShape(8.dp)).clickable { onExpandedChange(false) }
                    .padding(start = 6.dp, end = 6.dp, top = 2.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text("Hide", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceVar)
                Icon(Icons.Filled.KeyboardArrowUp, null, tint = c.onSurfaceVar, modifier = Modifier.size(16.dp))
            }
        }
        SettingsGroup(content)
    }
}

/** Avatar image (or initials) with an optional green presence dot (hub card 54dp, Account header 84dp). */
@Composable
internal fun ProfileAvatar(initials: String, size: Dp, online: Boolean, ringColor: Color, image: ByteArray? = null) {
    val c = MumbleTheme.colors
    val dot = if (size > 60.dp) 24.dp else 15.dp
    val bitmap = remember(image) {
        image?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
    }
    Box(Modifier.size(size)) {
        if (bitmap != null) {
            Image(bitmap, "Avatar", contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(CircleShape))
        } else Box(Modifier.fillMaxSize().clip(CircleShape).background(c.primary), contentAlignment = Alignment.Center) {
            Text(initials, color = c.onPrimary, fontWeight = FontWeight.Bold,
                fontSize = if (size > 60.dp) 31.sp else 20.sp, textAlign = TextAlign.Center)
        }
        if (online) {
            Box(
                Modifier.align(Alignment.BottomEnd).offset(1.dp, 1.dp).size(dot).clip(CircleShape)
                    .background(ringColor).padding(3.dp).clip(CircleShape).background(c.speaking),
            )
        }
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
        tint = MumbleTheme.colors.onSurfaceVar.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
}

@Composable
internal fun RadioDot(selected: Boolean) {
    val c = MumbleTheme.colors
    Box(
        Modifier.size(20.dp).clip(CircleShape).border(2.dp, if (selected) c.primary else c.outline, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(c.primary))
    }
}

@Composable
internal fun MoveButton(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    val c = MumbleTheme.colors
    Box(
        Modifier.size(32.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = c.onSurfaceVar.copy(alpha = if (enabled) 1f else 0.3f),
            modifier = Modifier.size(22.dp))
    }
}
