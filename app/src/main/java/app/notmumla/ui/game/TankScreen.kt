package app.notmumla.ui.game

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.notmumla.game.tank.Pose
import app.notmumla.game.tank.TankInput
import app.notmumla.game.tank.TankView
import app.notmumla.game.tank.TankWorld
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

private val Vector = Color(0xFF39FF6A)
private val Enemy = Color(0xFFFF5A4E)
private val ShellColor = Color(0xFFFFE45C)
private const val EYE_Y = 2.0
private const val NEAR = 0.5
private const val FAR = 260.0
private const val FOV = 70.0 * PI / 180

/**
 * The tank arena, full screen: a Battlezone-style wireframe view from our own tank, with a radar,
 * a drive stick and a fire button. [step] advances the shared simulation once per frame.
 */
@Composable
fun TankScreen(
    step: (TankInput) -> TankView?,
    onLeave: () -> Unit,
    /** Push-to-talk, when the user talks that way (the voice bar is hidden behind the game). */
    onPttHeld: ((Boolean) -> Unit)?,
) {
    Dialog(
        onDismissRequest = onLeave,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        DisposableEffect(Unit) {
            view.keepScreenOn = true
            onDispose { view.keepScreenOn = false; onPttHeld?.invoke(false) }
        }
        var stick by remember { mutableStateOf(Offset.Zero) } // x = turn, y = -throttle, both −1..1
        var firing by remember { mutableStateOf(false) }
        var frame by remember { mutableStateOf<TankView?>(null) }
        LaunchedEffect(Unit) {
            while (true) {
                withFrameMillis {
                    frame = step(TankInput(throttle = -stick.y, turn = stick.x, fire = firing))
                }
            }
        }
        val scores by remember { derivedStateOf { frame?.scores.orEmpty() } }
        val feed by remember { derivedStateOf { frame?.feed.orEmpty() } }
        val deadText by remember {
            derivedStateOf {
                frame?.takeIf { !it.alive }?.let { "DESTROYED by ${it.killedBy}\nback in ${(it.respawnInMs + 999) / 1000}" }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Canvas(Modifier.fillMaxSize()) { frame?.let { drawArena(it) } }

            Column(Modifier.safeDrawingPadding().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    HudButton("✕ Leave", onClick = onLeave)
                }
                Column(Modifier.padding(top = 8.dp)) {
                    feed.forEach { Text(it, color = Vector.copy(alpha = 0.85f), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
                }
            }
            Column(
                Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
                horizontalAlignment = Alignment.End,
            ) {
                scores.forEach { s ->
                    Text(
                        "${s.name.take(12)}  ${s.kills}/${s.deaths}",
                        color = if (s.me) Vector else Enemy,
                        fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                        fontWeight = if (s.me) FontWeight.Bold else FontWeight.Normal,
                    )
                }
                if (scores.size == 1) {
                    Text("waiting for players…", color = Vector.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
            deadText?.let {
                Text(
                    it, color = Enemy, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).offset(y = 60.dp),
                )
            }

            DriveStick(
                Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(20.dp),
                value = stick, onChange = { stick = it },
            )
            Row(
                Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                if (onPttHeld != null) HoldButton("TALK", 72, onHeld = onPttHeld)
                HoldButton("FIRE", 104, onHeld = { firing = it })
            }
        }
    }
}

@Composable
private fun HudButton(label: String, onClick: () -> Unit) {
    Text(
        label, color = Vector, fontSize = 14.sp, fontFamily = FontFamily.Monospace,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, Vector, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** Held = true from finger down to finger up; works alongside a finger on the stick. */
@Composable
private fun HoldButton(label: String, sizeDp: Int, onHeld: (Boolean) -> Unit) {
    var held by remember { mutableStateOf(false) }
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape)
            .background(if (held) Vector.copy(alpha = 0.35f) else Color.Transparent)
            .border(2.dp, Vector, CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    held = true; onHeld(true)
                    waitForUpOrCancellation()
                    held = false; onHeld(false)
                }
            },
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Vector, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
}

/** A virtual stick: up/down drives, left/right turns; it springs back to centre on release. */
@Composable
private fun DriveStick(modifier: Modifier, value: Offset, onChange: (Offset) -> Unit) {
    val sizeDp = 150
    Box(
        modifier.size(sizeDp.dp).clip(CircleShape).border(2.dp, Vector.copy(alpha = 0.7f), CircleShape)
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
                .size(52.dp).clip(CircleShape).background(Vector.copy(alpha = 0.45f)),
        )
    }
}

// ---- Rendering -------------------------------------------------------------------------------

private class Camera(val me: Pose, w: Float, h: Float) {
    val cx = w / 2
    val horizon = h * 0.42f
    val focal = (w / 2) / tan(FOV / 2).toFloat()
    private val c = cos(me.h)
    private val s = sin(me.h)

    /** World → camera space: x right, y up, z forward. */
    fun toCam(x: Double, y: Double, z: Double): DoubleArray {
        val dx = x - me.x
        val dz = z - me.z
        return doubleArrayOf(dx * c - dz * s, y - EYE_Y, dx * s + dz * c)
    }

    fun screen(p: DoubleArray) = Offset(cx + (p[0] / p[2] * focal).toFloat(), horizon - (p[1] / p[2] * focal).toFloat())
}

private fun DrawScope.line3(cam: Camera, a: DoubleArray, b: DoubleArray, color: Color, width: Float = 2f) {
    var p = cam.toCam(a[0], a[1], a[2])
    var q = cam.toCam(b[0], b[1], b[2])
    if (p[2] < NEAR && q[2] < NEAR) return
    if (p[2] > FAR && q[2] > FAR) return
    // Clip against the near plane so lines passing beside or behind us don't flip across the screen.
    if (p[2] < NEAR) p = clip(q, p) else if (q[2] < NEAR) q = clip(p, q)
    val fade = (1 - ((p[2] + q[2]) / 2 / FAR)).coerceIn(0.25, 1.0).toFloat()
    drawLine(color.copy(alpha = color.alpha * fade), cam.screen(p), cam.screen(q), strokeWidth = width)
}

private fun clip(inside: DoubleArray, outside: DoubleArray): DoubleArray {
    val t = (inside[2] - NEAR) / (inside[2] - outside[2])
    return DoubleArray(3) { inside[it] + (outside[it] - inside[it]) * t }
}

private fun v(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

/** Tank model in local space (x right, y up, z forward) as edge pairs. */
private val TankEdges: List<Pair<DoubleArray, DoubleArray>> = run {
    val bottom = listOf(v(-1.6, 0.0, -2.4), v(1.6, 0.0, -2.4), v(1.6, 0.0, 2.4), v(-1.6, 0.0, 2.4))
    val deck = listOf(v(-1.9, 0.9, -2.6), v(1.9, 0.9, -2.6), v(1.9, 0.9, 2.9), v(-1.9, 0.9, 2.9))
    val ring = listOf(v(-1.0, 0.9, -1.2), v(1.0, 0.9, -1.2), v(0.8, 0.9, 0.8), v(-0.8, 0.9, 0.8))
    val top = listOf(v(-0.7, 1.7, -1.0), v(0.7, 1.7, -1.0), v(0.5, 1.6, 0.5), v(-0.5, 1.6, 0.5))
    buildList {
        for (loop in listOf(bottom, deck, ring, top)) for (i in 0..3) add(loop[i] to loop[(i + 1) % 4])
        for (i in 0..3) { add(bottom[i] to deck[i]); add(ring[i] to top[i]) }
        add(v(-0.12, 1.35, 0.6) to v(-0.12, 1.35, 3.7))
        add(v(0.12, 1.35, 0.6) to v(0.12, 1.35, 3.7))
    }
}

private fun local(p: Pose, l: DoubleArray): DoubleArray {
    val c = cos(p.h)
    val s = sin(p.h)
    return v(p.x + l[0] * c + l[2] * s, l[1], p.z - l[0] * s + l[2] * c)
}

private val markPaint = Paint().apply {
    isAntiAlias = true
    typeface = Typeface.MONOSPACE
    textAlign = Paint.Align.CENTER
}

private fun DrawScope.drawArena(f: TankView) {
    val cam = Camera(f.me, size.width, size.height)

    drawLine(Vector, Offset(0f, cam.horizon), Offset(size.width, cam.horizon), strokeWidth = 2f)
    drawMountains(cam)

    // Ground markers every 25 m give a sense of speed on the empty plain.
    val m = 0.5
    for (gx in -4..4) for (gz in -4..4) {
        val x = gx * 25.0
        val z = gz * 25.0
        line3(cam, v(x - m, 0.0, z), v(x + m, 0.0, z), Vector.copy(alpha = 0.6f), 1.5f)
        line3(cam, v(x, 0.0, z - m), v(x, 0.0, z + m), Vector.copy(alpha = 0.6f), 1.5f)
    }

    // Arena fence.
    val hw = TankWorld.HALF
    val corners = listOf(v(-hw, 0.0, -hw), v(hw, 0.0, -hw), v(hw, 0.0, hw), v(-hw, 0.0, hw))
    for (i in 0..3) {
        val a = corners[i]
        val b = corners[(i + 1) % 4]
        line3(cam, a, b, Vector)
        line3(cam, v(a[0], 1.5, a[2]), v(b[0], 1.5, b[2]), Vector)
        line3(cam, a, v(a[0], 1.5, a[2]), Vector)
    }

    for (b in TankWorld.blocks) drawBlock(cam, b)

    for (t in f.tanks) {
        for ((a, b) in TankEdges) line3(cam, local(t.pose, a), local(t.pose, b), Enemy)
        val head = cam.toCam(t.pose.x, 3.2, t.pose.z)
        if (head[2] > NEAR && head[2] < FAR) {
            val at = cam.screen(head)
            markPaint.color = android.graphics.Color.argb(230, 255, 90, 78)
            markPaint.textSize = (14 + 600 / head[2]).coerceAtMost(40.0).toFloat()
            drawContext.canvas.nativeCanvas.drawText(t.name.take(16), at.x, at.y, markPaint)
        }
    }

    for (s in f.shells) {
        val p = cam.toCam(s.x, TankWorld.SHELL_Y, s.z)
        if (p[2] < NEAR || p[2] > FAR) continue
        drawCircle(if (s.mine) ShellColor else Enemy, radius = (cam.focal * 0.3 / p[2]).toFloat().coerceIn(2f, 14f), center = cam.screen(p))
    }

    for (e in f.explosions) drawExplosion(cam, e)

    drawReticle(cam, f)
    drawRadar(f)
    if (!f.alive) drawRect(Enemy.copy(alpha = 0.18f))
}

private fun DrawScope.drawBlock(cam: Camera, b: TankWorld.Block) {
    val h = b.half
    val base = listOf(v(b.x - h, 0.0, b.z - h), v(b.x + h, 0.0, b.z - h), v(b.x + h, 0.0, b.z + h), v(b.x - h, 0.0, b.z + h))
    for (i in 0..3) line3(cam, base[i], base[(i + 1) % 4], Vector)
    when (b.shape) {
        TankWorld.Shape.PYRAMID -> {
            val apex = v(b.x, b.height, b.z)
            for (p in base) line3(cam, p, apex, Vector)
        }
        TankWorld.Shape.CUBE -> {
            val top = base.map { v(it[0], b.height, it[2]) }
            for (i in 0..3) { line3(cam, top[i], top[(i + 1) % 4], Vector); line3(cam, base[i], top[i], Vector) }
        }
    }
}

/** Distant peaks, drawn by bearing only, so they never get closer — the classic backdrop. */
private val Peaks = doubleArrayOf(0.02, 0.07, 0.03, 0.05, 0.11, 0.04, 0.02, 0.06, 0.03, 0.09, 0.05, 0.02,
    0.04, 0.08, 0.03, 0.06, 0.12, 0.05)

private fun DrawScope.drawMountains(cam: Camera) {
    val step = 2 * PI / Peaks.size
    for (i in Peaks.indices) {
        val a0 = wrapPi(i * step - cam.me.h)
        val a1 = wrapPi((i + 1) * step - cam.me.h)
        if (abs(a0) > 1.2 || abs(a1) > 1.2 || abs(a1 - a0) > PI) continue
        val p0 = Offset(cam.cx + (tan(a0) * cam.focal).toFloat(), cam.horizon - (Peaks[i] * cam.focal).toFloat())
        val p1 = Offset(cam.cx + (tan(a1) * cam.focal).toFloat(), cam.horizon - (Peaks[(i + 1) % Peaks.size] * cam.focal).toFloat())
        drawLine(Vector.copy(alpha = 0.55f), p0, p1, strokeWidth = 1.5f)
    }
}

private fun wrapPi(a: Double): Double {
    var d = a % (2 * PI)
    if (d > PI) d -= 2 * PI
    if (d < -PI) d += 2 * PI
    return d
}

private fun DrawScope.drawExplosion(cam: Camera, e: TankView.Explosion) {
    val t = e.ageMs / app.notmumla.game.tank.TankArena.EXPLOSION_MS.toDouble()
    val r = 1 + t * 7
    val color = ShellColor.copy(alpha = (1 - t).toFloat().coerceIn(0f, 1f))
    for (i in 0 until 10) {
        val a = i * 2 * PI / 10 + i * 0.37
        val y = 0.4 + (i % 3) * 0.8 + t * 2
        line3(cam, v(e.x + sin(a) * r * 0.5, y * 0.5, e.z + cos(a) * r * 0.5),
            v(e.x + sin(a) * r, y, e.z + cos(a) * r), color, 2.5f)
    }
}

private fun DrawScope.drawReticle(cam: Camera, f: TankView) {
    val c = Offset(cam.cx, cam.horizon)
    val g = 14.dp.toPx()
    val color = if (f.reload >= 1f) Vector else Vector.copy(alpha = 0.4f)
    drawLine(color, c + Offset(-2 * g, 0f), c + Offset(-g * 0.6f, 0f), 2f)
    drawLine(color, c + Offset(g * 0.6f, 0f), c + Offset(2 * g, 0f), 2f)
    drawLine(color, c + Offset(0f, -2 * g), c + Offset(0f, -g * 0.6f), 2f)
    drawLine(color, c + Offset(0f, g * 0.6f), c + Offset(0f, g * 1.2f), 2f)
    // Reload bar under the sight.
    val w = 4 * g
    drawLine(Vector.copy(alpha = 0.25f), c + Offset(-w / 2, 2.4f * g), c + Offset(w / 2, 2.4f * g), 4f)
    drawLine(Vector, c + Offset(-w / 2, 2.4f * g), c + Offset(-w / 2 + w * f.reload, 2.4f * g), 4f)
}

/** Top-centre radar, heading up, 80 m range. */
private fun DrawScope.drawRadar(f: TankView) {
    val r = 46.dp.toPx()
    val center = Offset(size.width / 2, r + 18.dp.toPx())
    drawCircle(Vector.copy(alpha = 0.7f), r, center, style = Stroke(2f))
    drawLine(Vector.copy(alpha = 0.3f), center - Offset(0f, r), center + Offset(0f, r), 1f)
    drawLine(Vector.copy(alpha = 0.3f), center - Offset(r, 0f), center + Offset(r, 0f), 1f)
    val range = 80.0
    val c = cos(f.me.h)
    val s = sin(f.me.h)
    for (t in f.tanks) {
        val dx = t.pose.x - f.me.x
        val dz = t.pose.z - f.me.z
        val rx = dx * c - dz * s
        val rz = dx * s + dz * c
        val d = hypot(rx, rz)
        val k = if (d > range) range / d else 1.0
        drawCircle(Enemy, if (d > range) 3f else 5f, center + Offset((rx * k / range * r).toFloat(), (-rz * k / range * r).toFloat()))
    }
    drawCircle(Vector, 3f, center)
}
