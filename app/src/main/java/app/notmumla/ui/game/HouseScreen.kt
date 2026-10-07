package app.notmumla.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.game.house.Blocky
import app.notmumla.game.house.Emote
import app.notmumla.game.house.HouseInput
import app.notmumla.game.house.HouseView
import app.notmumla.game.house.HouseWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private val Hud = Color(0xFFFFFFFF)
private val HudShade = Color(0x99000000)
private val SlapColor = Color(0xFFFF5A4E)
private val Beyond = Color(0xFF3F7F34)
private val Lawn = Color(0xFF63B04F)
private val Skin = Color(0xFFF5CD30)
private val Pants = Color(0xFF3B4A6B)
private val WallInside = Color(0xFFEDE3CF)
private val WallOutside = Color(0xFFAFC2D6)
private val WallCap = Color(0xFF5E554C)
private val RailColor = Color(0xFF8B5E3C)
private val FenceColor = Color(0xFFF4F1EA)
/** Shirt colours, indexed by the shirt number every client sends. */
internal val Shirts = listOf(
    Color(0xFF2F6BFF), Color(0xFFE5383B), Color(0xFF2BB673), Color(0xFFFF8C1A),
    Color(0xFF9B5DE5), Color(0xFFF15BB5), Color(0xFF00BBF9), Color(0xFF2B2D42),
)
private val Light = norm3(doubleArrayOf(0.45, 0.85, -0.55))

private const val NEAR = 0.3
private const val FOV = 62.0 * PI / 180
/** How steeply the camera looks down. */
private const val PITCH = 0.95
/** Cut walls in front of you down to this, so you can see in. */
private const val CUT = 0.7
private val Zooms = listOf(12.5, 9.0, 17.0)

private var zoomChoice = 0
private var houseSound = true

/**
 * The block house, full screen: a dollhouse view that follows our figure from above, cutting away
 * walls in front of it and hiding the upstairs while we're downstairs. Stick to walk, buttons to
 * wave/cheer/dance/sit, SLAP to slap whoever's in front of us.
 */
@Composable
fun HouseScreen(
    step: (HouseInput) -> HouseView?,
    onSlap: () -> Unit,
    onEmote: (Emote) -> Unit,
    onShirt: () -> Unit,
    onLeave: () -> Unit,
    onPttHeld: ((Boolean) -> Unit)?,
    deafened: Boolean,
) {
    // Drawn in the activity, not a dialog window, for the real cutout and system-bar insets
    // (see TankScreen).
    androidx.activity.compose.BackHandler(onBack = onLeave)
    val view = LocalView.current
    val context = LocalContext.current
    val sfx = remember { HouseSounds(context.applicationContext) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; onPttHeld?.invoke(false); sfx.release() }
    }
    var sound by remember { mutableStateOf(houseSound) }
    val quiet by rememberUpdatedState(deafened || !sound)
    var stick by remember { mutableStateOf(Offset.Zero) }
    var turns by remember { mutableIntStateOf(0) }
    var zoom by remember { mutableIntStateOf(zoomChoice) }
    var frame by remember { mutableStateOf<HouseView?>(null) }
    var camYaw by remember { mutableStateOf(0.0) }
    var camY by remember { mutableStateOf(Double.NaN) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { ms ->
                val dt = if (last == 0L) 0.0 else (ms - last).coerceIn(0, 100) / 1000.0
                last = ms
                val target = turns * PI / 2
                camYaw += angleDiff(target, camYaw) * min(1.0, dt * 9)
                // Stick up walks away from the camera, whichever way it faces.
                val sx = stick.x.toDouble()
                val sy = -stick.y.toDouble()
                val f = step(HouseInput(sx * cos(camYaw) + sy * sin(camYaw), -sx * sin(camYaw) + sy * cos(camYaw)))
                if (f != null) {
                    val ty = f.me.y
                    camY = if (camY.isNaN()) ty else camY + (ty - camY) * min(1.0, dt * 6)
                    if (!quiet) sfx.play(f.sounds, f.me.x, f.me.z, camYaw)
                }
                frame = f
            }
        }
    }
    val feed by remember { derivedStateOf { frame?.feed.orEmpty() } }
    val players by remember { derivedStateOf { frame?.players.orEmpty() } }
    val activeEmote by remember { derivedStateOf { frame?.emote ?: Emote.NONE } }
    val shirt by remember { derivedStateOf { frame?.shirt ?: 0 } }
    val banner by remember { derivedStateOf { frame?.takeIf { it.down }?.let { "SLAPPED${it.slappedBy?.let { b -> " by $b" } ?: ""}!" } } }

    BoxWithConstraints(Modifier.fillMaxSize().background(Beyond).swallowTouches()) {
        val lift = controlLift(maxWidth, maxHeight)
        Canvas(Modifier.fillMaxSize()) {
            frame?.let { drawHouse(it, camYaw, if (camY.isNaN()) it.me.y else camY, Zooms[zoom]) }
        }

        Column(Modifier.safeDrawingPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("✕ Leave", Hud, onClick = onLeave)
                HudButton("⟲", Hud) { turns-- }
                HudButton("⟳", Hud) { turns++ }
                HudButton("Zoom", Hud) { zoom = (zoom + 1) % Zooms.size; zoomChoice = zoom }
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(Shirts[shirt])
                        .border(1.dp, Hud, RoundedCornerShape(8.dp)).clickable(onClick = onShirt),
                )
                HudButton(if (sound) "SFX" else "SFX off", if (sound) Hud else Hud.copy(alpha = 0.5f)) {
                    sound = !sound
                    houseSound = sound
                }
            }
            Column(Modifier.padding(top = 8.dp)) {
                feed.forEach { Text(it, color = Hud, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.background(HudShade).padding(horizontal = 4.dp)) }
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            players.forEachIndexed { i, name ->
                Text(
                    name.take(14), color = Hud, fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.background(HudShade).padding(horizontal = 4.dp),
                )
            }
            if (players.size <= 1) {
                Text("nobody else here yet…", color = Hud.copy(alpha = 0.85f), fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.background(HudShade).padding(horizontal = 4.dp))
            }
        }
        banner?.let {
            Text(
                it, color = SlapColor, fontSize = 26.sp, fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).offset(y = (-110).dp).background(HudShade).padding(8.dp),
            )
        }

        Stick(
            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 20.dp, bottom = lift),
            value = stick, color = Hud, onChange = { stick = it },
        )
        Column(
            Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 20.dp, bottom = lift),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((e, label) in listOf(Emote.WAVE to "WAVE", Emote.CHEER to "YAY", Emote.DANCE to "DANCE", Emote.SIT to "SIT")) {
                    TapButton(label, 56, active = activeEmote == e) { onEmote(e) }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                if (onPttHeld != null) HoldButton("TALK", 72, Hud, onHeld = onPttHeld)
                TapButton("SLAP", 104, active = false, color = SlapColor, onTap = onSlap)
            }
        }
    }
}

/** Fires on finger down, not up: a slap shouldn't wait for the thumb to lift. */
@Composable
private fun TapButton(label: String, sizeDp: Int, active: Boolean, color: Color = Hud, onTap: () -> Unit) {
    val tap by rememberUpdatedState(onTap)
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape)
            .background(if (active) color.copy(alpha = 0.45f) else HudShade)
            .border(2.dp, color, CircleShape)
            .tapDown { tap() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, fontSize = if (sizeDp < 70) 11.sp else 16.sp)
    }
}

private fun Modifier.tapDown(onDown: () -> Unit) = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown().consume()
        onDown()
    }
}

// ---- Rendering -------------------------------------------------------------------------------

private fun norm3(a: DoubleArray): DoubleArray {
    val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    return if (l < 1e-12) a else doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
}

private fun dot3(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross3(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun sub3(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
private fun add3(a: DoubleArray, b: DoubleArray, k: Double = 1.0) = doubleArrayOf(a[0] + b[0] * k, a[1] + b[1] * k, a[2] + b[2] * k)
private fun v3(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

private fun angleDiff(a: Double, b: Double): Double {
    var d = (a - b) % (2 * PI)
    if (d > PI) d -= 2 * PI
    if (d <= -PI) d += 2 * PI
    return d
}

private fun Color.shade(k: Double): Color {
    val f = k.toFloat().coerceIn(0f, 1.2f)
    return Color((red * f).coerceAtMost(1f), (green * f).coerceAtMost(1f), (blue * f).coerceAtMost(1f), alpha)
}

private fun lit(n: DoubleArray) = 0.55 + 0.5 * max(0.0, dot3(n, Light))

private class HouseCam(target: DoubleArray, yaw: Double, dist: Double, w: Float, hgt: Float) {
    val fwd = v3(sin(yaw) * cos(PITCH), -sin(PITCH), cos(yaw) * cos(PITCH))
    val right = v3(cos(yaw), 0.0, -sin(yaw))
    val up = v3(sin(yaw) * sin(PITCH), cos(PITCH), cos(yaw) * sin(PITCH))
    val eye = add3(target, fwd, -dist)
    private val fx = sin(yaw)
    private val fz = cos(yaw)
    val cx = w / 2
    val cy = hgt * 0.47f
    val focal = (min(w, hgt) / 2 / tan(FOV / 2)).toFloat()

    fun toCam(x: Double, y: Double, z: Double): DoubleArray {
        val d = v3(x - eye[0], y - eye[1], z - eye[2])
        return v3(dot3(d, right), dot3(d, up), dot3(d, fwd))
    }

    fun toCam(p: DoubleArray) = toCam(p[0], p[1], p[2])

    fun screen(c: DoubleArray) = Offset(cx + (c[0] / c[2] * focal).toFloat(), cy - (c[1] / c[2] * focal).toFloat())

    /** How far (x, z) is from the camera along the ground: the painter's sort key within a floor. */
    fun depthH(x: Double, z: Double) = (x - eye[0]) * fx + (z - eye[2]) * fz
}

private fun clipZ(inside: DoubleArray, outside: DoubleArray): DoubleArray {
    val t = (inside[2] - NEAR) / (inside[2] - outside[2])
    return DoubleArray(3) { inside[it] + (outside[it] - inside[it]) * t }
}

private fun clipNear(poly: List<DoubleArray>): List<DoubleArray> {
    val out = ArrayList<DoubleArray>(poly.size + 2)
    for (i in poly.indices) {
        val p = poly[i]
        val q = poly[(i + 1) % poly.size]
        val pIn = p[2] >= NEAR
        val qIn = q[2] >= NEAR
        if (pIn) out += p
        if (pIn != qIn) out += if (pIn) clipZ(p, q) else clipZ(q, p)
    }
    return out
}

/** Fill a world-space polygon; the same-colour hairline hides seams between neighbours. */
private fun DrawScope.fillWorld(cam: HouseCam, pts: List<DoubleArray>, color: Color, seam: Boolean = true) {
    val c = pts.map(cam::toCam)
    if (c.all { it[2] < NEAR }) return
    val clipped = clipNear(c)
    if (clipped.size < 3) return
    val scr = clipped.map(cam::screen)
    if (scr.all { it.x < 0 } || scr.all { it.y < 0 } || scr.all { it.x > size.width } || scr.all { it.y > size.height }) return
    val path = Path()
    scr.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    path.close()
    drawPath(path, color)
    if (seam) drawPath(path, color, style = Stroke(width = 1f))
}

private fun DrawScope.floorQuad(cam: HouseCam, x0: Double, x1: Double, z0: Double, z1: Double, y: Double, color: Color) =
    fillWorld(cam, listOf(v3(x0, y, z0), v3(x1, y, z0), v3(x1, y, z1), v3(x0, y, z1)), color)

private fun DrawScope.drawFloor(cam: HouseCam, f: HouseWorld.Floor, y: Double) {
    val base = Color(f.color)
    if (!f.checker) return floorQuad(cam, f.x0, f.x1, f.z0, f.z1, y, base)
    val dark = base.shade(0.82)
    var i = 0
    var x = f.x0
    while (x < f.x1 - 1e-6) {
        val x1 = min(f.x1, x + 1.0)
        var z = f.z0
        var j = 0
        while (z < f.z1 - 1e-6) {
            val z1 = min(f.z1, z + 1.0)
            floorQuad(cam, x, x1, z, z1, y, if ((i + j) % 2 == 0) base else dark)
            z = z1; j++
        }
        x = x1; i++
    }
}

/**
 * An axis-aligned box: only the faces turned toward the eye (they never overlap each other).
 * [faceColor] picks a face's base colour by its outward axis (0..5 = −x, +x, −z, +z, top, bottom).
 */
private fun DrawScope.drawAabb(
    cam: HouseCam, x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double,
    faceColor: (Int) -> Color,
) {
    val e = cam.eye
    fun face(k: Int, n: DoubleArray, vararg p: DoubleArray) = fillWorld(cam, p.toList(), faceColor(k).shade(lit(n)))
    if (e[0] < x0) face(0, v3(-1.0, 0.0, 0.0), v3(x0, y0, z0), v3(x0, y0, z1), v3(x0, y1, z1), v3(x0, y1, z0))
    if (e[0] > x1) face(1, v3(1.0, 0.0, 0.0), v3(x1, y0, z0), v3(x1, y0, z1), v3(x1, y1, z1), v3(x1, y1, z0))
    if (e[2] < z0) face(2, v3(0.0, 0.0, -1.0), v3(x0, y0, z0), v3(x1, y0, z0), v3(x1, y1, z0), v3(x0, y1, z0))
    if (e[2] > z1) face(3, v3(0.0, 0.0, 1.0), v3(x0, y0, z1), v3(x1, y0, z1), v3(x1, y1, z1), v3(x0, y1, z1))
    if (e[1] > y1) face(4, v3(0.0, 1.0, 0.0), v3(x0, y1, z0), v3(x1, y1, z0), v3(x1, y1, z1), v3(x0, y1, z1))
}

private fun DrawScope.drawBlock(cam: HouseCam, b: HouseWorld.Box, top: Double) {
    val h = top
    when (b.kind) {
        HouseWorld.Kind.EXTERIOR -> {
            val cut = h < b.y1 - 1e-6
            drawAabb(cam, b.x0, b.x1, b.y0, h, b.z0, b.z1) { k ->
                // The face on the house's outline is siding; the rest is the inside wall.
                val outer = when (k) {
                    0 -> abs(b.x0 - HouseWorld.HX0) < 1e-6
                    1 -> abs(b.x1 - HouseWorld.HX1) < 1e-6
                    2 -> abs(b.z0 - HouseWorld.HZ0) < 1e-6
                    3 -> abs(b.z1 - HouseWorld.HZ1) < 1e-6
                    else -> false
                }
                when {
                    k == 4 -> if (cut) WallCap else WallInside.shade(0.85)
                    outer -> WallOutside
                    else -> WallInside
                }
            }
        }
        HouseWorld.Kind.WALL -> {
            val cut = h < b.y1 - 1e-6
            drawAabb(cam, b.x0, b.x1, b.y0, h, b.z0, b.z1) { k -> if (k == 4) (if (cut) WallCap else WallInside.shade(0.85)) else WallInside }
        }
        HouseWorld.Kind.RAIL -> drawAabb(cam, b.x0, b.x1, b.y0, h, b.z0, b.z1) { RailColor }
        HouseWorld.Kind.FENCE -> drawAabb(cam, b.x0, b.x1, b.y0, h, b.z0, b.z1) { FenceColor }
        else -> drawAabb(cam, b.x0, b.x1, b.y0, h, b.z0, b.z1) { k -> Color(if (k == 4) b.top else b.color) }
    }
}

/** Whether a box downstairs shows through the stairwell from upstairs. */
private fun nearStairwell(x: Double, z: Double) =
    x > HouseWorld.SX0 - 1.2 && x < HouseWorld.GARAGE_X + 0.3 && z > HouseWorld.SZ0 - 1.2 && z < HouseWorld.SZ1 + 0.3

private fun DrawScope.drawHouse(f: HouseView, yaw: Double, camY: Double, dist: Double) {
    val me = f.me
    val cam = HouseCam(v3(me.x, camY + 0.9, me.z), yaw, dist, size.width, size.height)
    val myLevel = me.level
    val indoors = HouseWorld.inHouse(me.x, me.z)
    // Downstairs indoors, the upstairs would be a lid over everything: leave it off.
    val showUpper = myLevel == 1 || !indoors
    val myDepth = cam.depthH(me.x, me.z)

    floorQuad(cam, -HouseWorld.YARD_X, HouseWorld.YARD_X, HouseWorld.YARD_Z0, HouseWorld.YARD_Z1, 0.0, Lawn)
    for (p in HouseWorld.paths) drawFloor(cam, p, 0.0)

    val figures = listOf(me) + f.others
    fun hidden(fig: HouseView.Figure) =
        (fig.level == 1 && !showUpper) ||
            (fig.level == 0 && myLevel == 1 && HouseWorld.inHouse(fig.x, fig.z) && !nearStairwell(fig.x, fig.z))

    for (level in 0..(if (showUpper) 1 else 0)) {
        val y = level * HouseWorld.STORY
        for (fl in HouseWorld.floors) if (fl.level == level) drawFloor(cam, fl, y)
        // Within a floor, everything stands on the same plane: far to near along the ground works.
        val items = ArrayList<Pair<Double, DrawScope.() -> Unit>>()
        val active = level == myLevel || !indoors
        for (b in HouseWorld.boxes) {
            if (b.level != level) continue
            // From upstairs, the downstairs rooms are under the floor; only the stairwell shows.
            if (level == 0 && myLevel == 1 && b.inside && !nearStairwell(b.cx, b.cz)) continue
            val d = cam.depthH(b.cx, b.cz)
            var top = b.y1
            if (b.kind == HouseWorld.Kind.LEAVES && d < myDepth - 0.5 &&
                abs(dot3(v3(b.cx - me.x, 0.0, b.cz - me.z), cam.right)) < 4
            ) continue
            if (active && (b.kind == HouseWorld.Kind.WALL || b.kind == HouseWorld.Kind.EXTERIOR) && d < myDepth - 0.3) {
                top = b.y0 + CUT
            }
            items += d to { drawBlock(cam, b, top) }
        }
        for (fig in figures) {
            if (fig.level != level || hidden(fig)) continue
            val shirt = Shirts[fig.shirt.coerceIn(0, Shirts.size - 1)]
            items += cam.depthH(fig.x, fig.z) to { drawFigure(cam, fig, shirt, 1f) }
        }
        items.sortByDescending { it.first }
        for ((_, draw) in items) draw()
    }
    // A faint copy of us over everything, so a tree or a wall never loses us.
    drawFigure(cam, me, Shirts[me.shirt.coerceIn(0, Shirts.size - 1)], 0.3f)

    // Name tags on top of everything; players we can't see get a faded one.
    for (fig in f.others) {
        val head = v3(fig.joints[Blocky.HEAD * 3], fig.joints[Blocky.HEAD * 3 + 1] + 0.55, fig.joints[Blocky.HEAD * 3 + 2])
        val c = cam.toCam(head)
        if (c[2] < NEAR) continue
        nameTag(cam.screen(c), fig.name, if (hidden(fig)) 0.4f else 1f)
    }
}

private val tagPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
    textAlign = android.graphics.Paint.Align.CENTER
    typeface = android.graphics.Typeface.DEFAULT_BOLD
}
private val tagBack = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

private fun DrawScope.nameTag(at: Offset, name: String, alpha: Float) {
    tagPaint.textSize = 13.dp.toPx()
    tagPaint.color = Color.White.copy(alpha = alpha).toArgb()
    tagBack.color = Color.Black.copy(alpha = 0.45f * alpha).toArgb()
    val text = name.take(16)
    val w = tagPaint.measureText(text) / 2 + 6.dp.toPx()
    val canvas = drawContext.canvas.nativeCanvas
    canvas.drawRoundRect(at.x - w, at.y - 16.dp.toPx(), at.x + w, at.y + 4.dp.toPx(), 6.dp.toPx(), 6.dp.toPx(), tagBack)
    canvas.drawText(text, at.x, at.y, tagPaint)
}

/** A box with its own axes [ax] (three unit vectors) and half-sizes [half]. */
private class OBox(val center: DoubleArray, val ax: Array<DoubleArray>, val half: DoubleArray, val color: Color, val face: Boolean = false)

private fun DrawScope.drawFigure(cam: HouseCam, fig: HouseView.Figure, shirt: Color, alpha: Float) {
    val j = fig.joints
    fun p(i: Int) = v3(j[i * 3], j[i * 3 + 1], j[i * 3 + 2])
    val hip = add3(p(Blocky.HIP_L), sub3(p(Blocky.HIP_R), p(Blocky.HIP_L)), 0.5)
    val neck = p(Blocky.NECK)
    val up = norm3(sub3(neck, hip))
    val rawRight = sub3(p(Blocky.SHOULDER_R), p(Blocky.SHOULDER_L))
    val right = norm3(add3(rawRight, up, -dot3(rawRight, up)))
    val fwd = cross3(right, up)
    val axes = arrayOf(right, up, fwd)

    fun limb(from: DoubleArray, to: DoubleArray, above: Double, len: Double, color: Color): OBox {
        val a = norm3(sub3(to, from))
        var r = cross3(a, fwd)
        if (dot3(r, r) < 1e-6) r = cross3(a, up)
        r = norm3(r)
        val f = cross3(r, a)
        val center = add3(from, a, (len - above) / 2)
        return OBox(center, arrayOf(r, a, f), doubleArrayOf(Blocky.LIMB, (len + above) / 2, Blocky.LIMB), color)
    }

    val boxes = listOf(
        OBox(add3(hip, sub3(neck, hip), 0.5), axes, doubleArrayOf(Blocky.TORSO_X, (Blocky.NECK_Y - Blocky.HIP_Y) / 2, Blocky.TORSO_Z), shirt),
        OBox(p(Blocky.HEAD), axes, doubleArrayOf(Blocky.HEAD_HALF, Blocky.HEAD_HALF, Blocky.HEAD_HALF), Skin, face = true),
        limb(p(Blocky.SHOULDER_L), p(Blocky.HAND_L), Blocky.ARM_ABOVE, Blocky.ARM, Skin),
        limb(p(Blocky.SHOULDER_R), p(Blocky.HAND_R), Blocky.ARM_ABOVE, Blocky.ARM, Skin),
        limb(p(Blocky.HIP_L), p(Blocky.FOOT_L), 0.0, Blocky.LEG, Pants),
        limb(p(Blocky.HIP_R), p(Blocky.FOOT_R), 0.0, Blocky.LEG, Pants),
    ).sortedByDescending { cam.toCam(it.center)[2] }
    for (b in boxes) drawOBox(cam, b, alpha, fig.down)
}

private fun DrawScope.drawOBox(cam: HouseCam, b: OBox, alpha: Float, dazed: Boolean) {
    for (axis in 0..2) for (sign in listOf(-1.0, 1.0)) {
        val n = DoubleArray(3) { b.ax[axis][it] * sign }
        val fc = add3(b.center, n, b.half[axis])
        if (dot3(n, sub3(cam.eye, fc)) <= 0) continue
        val u = b.ax[(axis + 1) % 3]
        val w = b.ax[(axis + 2) % 3]
        val hu = b.half[(axis + 1) % 3]
        val hw = b.half[(axis + 2) % 3]
        val pts = listOf(
            add3(add3(fc, u, -hu), w, -hw), add3(add3(fc, u, hu), w, -hw),
            add3(add3(fc, u, hu), w, hw), add3(add3(fc, u, -hu), w, hw),
        )
        fillWorld(cam, pts, b.color.shade(lit(n)).copy(alpha = alpha), seam = alpha >= 1f)
        if (b.face && axis == 2 && sign > 0) drawFace(cam, fc, b.ax[0], b.ax[1], dazed)
    }
}

/** Two eyes and a smile on the head's front; knocked-out figures get X eyes and an "o". */
private fun DrawScope.drawFace(cam: HouseCam, c: DoubleArray, right: DoubleArray, up: DoubleArray, dazed: Boolean) {
    val depth = cam.toCam(c)[2]
    if (depth < NEAR) return
    val w = (cam.focal * 0.035 / depth).toFloat().coerceAtLeast(1.5f)
    fun at(r: Double, u: Double) = cam.screen(cam.toCam(add3(add3(c, right, r), up, u)))
    fun line(r0: Double, u0: Double, r1: Double, u1: Double) =
        drawLine(Color(0xFF1E1E1E), at(r0, u0), at(r1, u1), strokeWidth = w, cap = StrokeCap.Round)
    if (dazed) {
        for (s in listOf(-1.0, 1.0)) {
            line(s * 0.09 - 0.03, 0.08, s * 0.09 + 0.03, 0.02)
            line(s * 0.09 - 0.03, 0.02, s * 0.09 + 0.03, 0.08)
        }
        val m = at(0.0, -0.09)
        drawCircle(Color(0xFF1E1E1E), radius = w * 1.3f, center = m, style = Stroke(width = w * 0.7f))
    } else {
        line(-0.09, 0.03, -0.09, 0.09)
        line(0.09, 0.03, 0.09, 0.09)
        line(-0.11, -0.06, -0.05, -0.11)
        line(-0.05, -0.11, 0.05, -0.11)
        line(0.05, -0.11, 0.11, -0.06)
    }
}

/** Volume by distance, pan by where it is across the camera's view. */
private fun HouseSounds.play(events: List<app.notmumla.game.house.HouseSoundEvent>, meX: Double, meZ: Double, yaw: Double) {
    for (e in events) {
        val dx = e.x - meX
        val dz = e.z - meZ
        val d = hypot(dx, dz)
        val vol = (1.0 / (1.0 + d / 5.0)).toFloat()
        val pan = ((dx * cos(yaw) - dz * sin(yaw)) / (d + 2.0)).toFloat()
        play(e.sound, vol, pan)
    }
}
