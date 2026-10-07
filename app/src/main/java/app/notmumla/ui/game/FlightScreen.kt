package app.notmumla.ui.game

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.notmumla.game.flight.FlightArena
import app.notmumla.game.flight.FlightInput
import app.notmumla.game.flight.FlightView
import app.notmumla.game.flight.FlightWorld
import app.notmumla.game.flight.Pose3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

private val Hud = Color(0xFFE6F0FF)
private val Danger = Color(0xFFFF5A4E)
private val ShieldColor = Color(0xFF4FA3FF)
private val BoostColor = Color(0xFFFFC94D)
private val MyLaser = Color(0xFF6BFF7A)
private val EnemyLaser = Color(0xFFFF7A3C)
private val SkyTop = Color(0xFF0B1D5C)
/** Sky bands toward the horizon: (up-ness of the view ray, colour). */
private val SkyBands = listOf(
    0.45 to Color(0xFF14307A), 0.25 to Color(0xFF1F4A9A), 0.12 to Color(0xFF3468B4),
    0.05 to Color(0xFF5A8CCB), 0.015 to Color(0xFF8DB4DC),
)
private val GroundHaze = Color(0xFF6F9478)
private val GroundBands = listOf(-0.03 to Color(0xFF4A7A56), -0.12 to Color(0xFF3A6845), -0.35 to Color(0xFF2E5A39))
private val GroundDot = Color(0xFF9CC9A4)
private val HullColor = Color(0xFFD9DDE6)
private val CanopyColor = Color(0xFF26305E)
private val MyAccent = Color(0xFF2F6BFF)
/** Everyone else's accent, by session id, so all players see the same colours for a ship. */
private val Accents = listOf(
    Color(0xFFE5383B), Color(0xFFFF8C1A), Color(0xFF9B5DE5), Color(0xFFF15BB5), Color(0xFF00BBF9), Color(0xFFFEE440),
)
private val TowerColors = listOf(Color(0xFFB9BECB), Color(0xFFCDB89A))
private val ArchColor = Color(0xFFE07A3C)
private val Light = norm(doubleArrayOf(0.35, 0.85, -0.4))

private const val NEAR = 0.5
private const val FAR = 1_400.0
/** Across the screen's short side, so the ship is the same size in portrait and landscape. */
private const val FOV = 74.0 * PI / 180
private const val CHASE_BACK = 16.0
private const val CHASE_UP = 3.2

/** Stick y grows downward; "flight" style pulls back (down) to climb, like the old console games. */
private var flightStyle = true
private var soundOn = true

/**
 * The dogfight, full screen: a flat-shaded chase view behind our own ship, with a flight stick,
 * FIRE/BOOST/BRAKE/ROLL and push-to-talk. [step] advances the shared simulation once per frame.
 */
@Composable
fun FlightScreen(
    step: (FlightInput) -> FlightView?,
    onLeave: () -> Unit,
    /** Push-to-talk, when the user talks that way (the voice bar is hidden behind the game). */
    onPttHeld: ((Boolean) -> Unit)?,
    /** Self-deafened: the game stays quiet too. */
    deafened: Boolean,
) {
    // Drawn in the activity, not a dialog window, for the real cutout and system-bar insets
    // (see TankScreen).
    androidx.activity.compose.BackHandler(onBack = onLeave)
    val view = LocalView.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val sfx = remember { FlightSounds(context.applicationContext) }
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false; onPttHeld?.invoke(false); sfx.release() }
    }
    var sound by remember { mutableStateOf(soundOn) }
    val quiet by androidx.compose.runtime.rememberUpdatedState(deafened || !sound)
    var stick by remember { mutableStateOf(Offset.Zero) }
    var firing by remember { mutableStateOf(false) }
    var boosting by remember { mutableStateOf(false) }
    var braking by remember { mutableStateOf(false) }
    var rolling by remember { mutableStateOf(false) }
    var flight by remember { mutableStateOf(flightStyle) }
    var frame by remember { mutableStateOf<FlightView?>(null) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameMillis { ms ->
                val f = step(
                    FlightInput(
                        yaw = stick.x,
                        pitch = if (flight) stick.y else -stick.y,
                        fire = firing, boost = boosting, brake = braking, roll = rolling,
                    ),
                )
                if (f != null && !quiet) sfx.play(f.sounds)
                sfx.loops(if (f == null || quiet) emptyList() else f.loops, dtMs = if (last == 0L) 0 else ms - last)
                last = ms
                frame = f
            }
        }
    }
    val scores by remember { derivedStateOf { frame?.scores.orEmpty() } }
    val feed by remember { derivedStateOf { frame?.feed.orEmpty() } }
    val shield by remember { derivedStateOf { frame?.shield ?: 1f } }
    val boost by remember { derivedStateOf { frame?.boost ?: 1f } }
    val banner by remember {
        derivedStateOf {
            val f = frame
            when {
                f == null -> null
                !f.alive -> (f.killedBy?.let { "SHOT DOWN by $it" } ?: "CRASHED") +
                    "\nback in ${(f.respawnInMs + 999) / 1000}"
                f.turningBack -> "TURNING BACK"
                else -> null
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(SkyTop).swallowTouches()) {
        val lift = controlLift(maxWidth, maxHeight)
        Canvas(Modifier.fillMaxSize()) { frame?.let { drawFlight(it) } }

        Column(Modifier.safeDrawingPadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("✕ Leave", Hud, onClick = onLeave)
                HudButton(if (flight) "Y: flight" else "Y: arcade", Hud) {
                    flight = !flight
                    flightStyle = flight
                }
                HudButton(if (sound) "SFX" else "SFX off", if (sound) Hud else Hud.copy(alpha = 0.5f)) {
                    sound = !sound
                    soundOn = sound
                }
            }
            Gauge("SHIELD", shield, if (shield < 0.3f) Danger else ShieldColor, Modifier.padding(top = 10.dp))
            Gauge("BOOST", boost, BoostColor, Modifier.padding(top = 4.dp))
            Column(Modifier.padding(top = 8.dp)) {
                feed.forEach { Text(it, color = Hud.copy(alpha = 0.9f), fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            }
        }
        Column(
            Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            scores.forEach { s ->
                Text(
                    "${s.name.take(12)}  ${s.kills}/${s.deaths}",
                    color = if (s.me) Hud else Danger,
                    fontSize = 13.sp, fontFamily = FontFamily.Monospace,
                    fontWeight = if (s.me) FontWeight.Bold else FontWeight.Normal,
                )
            }
            if (scores.size == 1) {
                Text("waiting for players…", color = Hud.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
        banner?.let {
            Text(
                it, color = if (frame?.alive == false) Danger else BoostColor, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp),
            )
        }

        Stick(
            Modifier.align(Alignment.BottomStart).safeDrawingPadding().padding(start = 20.dp, bottom = lift),
            value = stick, color = Hud, onChange = { stick = it },
        )
        Column(
            Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(end = 20.dp, bottom = lift),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HoldButton("ROLL", 58, Hud, onHeld = { rolling = it })
                HoldButton("BRAKE", 58, Hud, onHeld = { braking = it })
                HoldButton("BOOST", 58, Hud, onHeld = { boosting = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
                if (onPttHeld != null) HoldButton("TALK", 72, Hud, onHeld = onPttHeld)
                HoldButton("FIRE", 104, Hud, onHeld = { firing = it })
            }
        }
    }
}

@Composable
private fun Gauge(label: String, value: Float, color: Color, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Hud, fontSize = 10.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(52.dp))
        Box(Modifier.width(120.dp).height(9.dp).background(Color.Black.copy(alpha = 0.45f))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(value.coerceIn(0f, 1f)).background(color))
        }
    }
}

// ---- Rendering -------------------------------------------------------------------------------

private fun norm(a: DoubleArray): DoubleArray {
    val l = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
    return if (l == 0.0) a else doubleArrayOf(a[0] / l, a[1] / l, a[2] / l)
}

private fun dot3(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
private fun cross3(a: DoubleArray, b: DoubleArray) =
    doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
private fun v3(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

private fun Color.shade(k: Double): Color {
    val f = k.toFloat().coerceIn(0f, 1.2f)
    return Color((red * f).coerceAtMost(1f), (green * f).coerceAtMost(1f), (blue * f).coerceAtMost(1f), alpha)
}

/** Flat shading: a fixed sun plus ambient, so faces read as facets, the way the SNES drew them. */
private fun lit(n: DoubleArray) = 0.45 + 0.6 * max(0.0, dot3(n, Light))

/** Right, up and forward for a heading, pitch and roll (roll > 0 dips the right wing). */
private fun basis(h: Double, p: Double, roll: Double): Array<DoubleArray> {
    val sh = sin(h); val ch = cos(h); val sp = sin(p); val cp = cos(p)
    val f = v3(sh * cp, sp, ch * cp)
    val r0 = v3(ch, 0.0, -sh)
    val u0 = v3(-sp * sh, cp, -sp * ch)
    val cr = cos(roll); val sr = sin(roll)
    return arrayOf(DoubleArray(3) { r0[it] * cr - u0[it] * sr }, DoubleArray(3) { u0[it] * cr + r0[it] * sr }, f)
}

private class Cam3(val eye: DoubleArray, h: Double, p: Double, roll: Double, w: Float, hgt: Float) {
    val right: DoubleArray
    val up: DoubleArray
    val fwd: DoubleArray
    val cx = w / 2
    // Above centre, so the ship (below the line of sight) sits clear of the thumb controls.
    val cy = hgt * 0.4f
    val focal = (min(w, hgt) / 2 / tan(FOV / 2)).toFloat()

    init {
        val b = basis(h, p, roll)
        right = b[0]; up = b[1]; fwd = b[2]
    }

    /** World → camera space: x right, y up, z forward. */
    fun toCam(x: Double, y: Double, z: Double): DoubleArray {
        val d = v3(x - eye[0], y - eye[1], z - eye[2])
        return v3(dot3(d, right), dot3(d, up), dot3(d, fwd))
    }

    fun toCam(p: DoubleArray) = toCam(p[0], p[1], p[2])

    fun screen(c: DoubleArray) = Offset(cx + (c[0] / c[2] * focal).toFloat(), cy - (c[1] / c[2] * focal).toFloat())

    /** How much the view ray through a screen point climbs: 0 on the horizon, < 0 toward the ground. */
    fun rayY(sx: Float, sy: Float): Double {
        val u = ((sx - cx) / focal).toDouble()
        val v = ((cy - sy) / focal).toDouble()
        return u * right[1] + v * up[1] + fwd[1]
    }

    fun dist(p: DoubleArray) = sqrt((p[0] - eye[0]).let { it * it } + (p[1] - eye[1]).let { it * it } + (p[2] - eye[2]).let { it * it })
}

private fun clipZ(inside: DoubleArray, outside: DoubleArray): DoubleArray {
    val t = (inside[2] - NEAR) / (inside[2] - outside[2])
    return DoubleArray(3) { inside[it] + (outside[it] - inside[it]) * t }
}

/** Sutherland–Hodgman against the near plane only (x/y overflow is clipped by the canvas). */
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

/** Fill a camera-space polygon; the same-colour hairline hides seams between neighbouring facets. */
private fun DrawScope.fillPoly(cam: Cam3, pts: List<DoubleArray>, color: Color) {
    if (pts.all { it[2] < NEAR } || pts.all { it[2] > FAR }) return
    val clipped = clipNear(pts)
    if (clipped.size < 3) return
    val path = Path()
    clipped.forEachIndexed { i, q -> cam.screen(q).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
    path.close()
    drawPath(path, color)
    drawPath(path, color, style = Stroke(width = 1f))
}

private fun DrawScope.line3(cam: Cam3, a: DoubleArray, b: DoubleArray, color: Color, width: Float) {
    var p = cam.toCam(a)
    var q = cam.toCam(b)
    if (p[2] < NEAR && q[2] < NEAR) return
    if (p[2] < NEAR) p = clipZ(q, p) else if (q[2] < NEAR) q = clipZ(p, q)
    drawLine(color, cam.screen(p), cam.screen(q), strokeWidth = width, cap = StrokeCap.Round)
}

/** The screen region whose view rays climb less than [k], filled. k = 0 is the ground. */
private fun DrawScope.fillBelow(cam: Cam3, k: Double, color: Color) {
    val corners = listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(size.width, size.height), Offset(0f, size.height))
    val g = corners.map { cam.rayY(it.x, it.y) - k }
    val out = ArrayList<Offset>(6)
    for (i in 0..3) {
        val j = (i + 1) % 4
        if (g[i] < 0) out += corners[i]
        if ((g[i] < 0) != (g[j] < 0)) {
            val t = (g[i] / (g[i] - g[j])).toFloat()
            out += corners[i] + (corners[j] - corners[i]) * t
        }
    }
    if (out.size < 3) return
    val path = Path()
    out.forEachIndexed { i, o -> if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
    path.close()
    drawPath(path, color)
}

// Buildings: five faces each (the floor hides the bottom), lit once up front.
private class Face(val pts: List<DoubleArray>, val n: DoubleArray, val color: Color)

private fun boxFaces(b: FlightWorld.Box, base: Color): List<Face> {
    val (x0, x1) = b.x0 to b.x1
    val (y0, y1) = b.y0 to b.y1
    val (z0, z1) = b.z0 to b.z1
    fun face(n: DoubleArray, vararg p: DoubleArray) = Face(p.toList(), n, base.shade(lit(n)))
    return listOf(
        face(v3(0.0, 1.0, 0.0), v3(x0, y1, z0), v3(x1, y1, z0), v3(x1, y1, z1), v3(x0, y1, z1)),
        face(v3(-1.0, 0.0, 0.0), v3(x0, y0, z0), v3(x0, y0, z1), v3(x0, y1, z1), v3(x0, y1, z0)),
        face(v3(1.0, 0.0, 0.0), v3(x1, y0, z0), v3(x1, y0, z1), v3(x1, y1, z1), v3(x1, y1, z0)),
        face(v3(0.0, 0.0, -1.0), v3(x0, y0, z0), v3(x1, y0, z0), v3(x1, y1, z0), v3(x0, y1, z0)),
        face(v3(0.0, 0.0, 1.0), v3(x0, y0, z1), v3(x1, y0, z1), v3(x1, y1, z1), v3(x0, y1, z1)),
    ).let { faces ->
        // The underside of an arch's beam shows when flying through it.
        if (y0 > 0) faces + face(v3(0.0, -1.0, 0.0), v3(x0, y0, z0), v3(x1, y0, z0), v3(x1, y0, z1), v3(x0, y0, z1))
        else faces
    }
}

private val BoxFaces: List<List<Face>> = FlightWorld.boxes.mapIndexed { i, b ->
    boxFaces(b, if (b.kind == FlightWorld.Kind.ARCH) ArchColor else TowerColors[i % TowerColors.size])
}

private fun DrawScope.drawBox(cam: Cam3, faces: List<Face>) {
    for (f in faces) {
        val a = f.pts[0]
        if (dot3(f.n, v3(cam.eye[0] - a[0], cam.eye[1] - a[1], cam.eye[2] - a[2])) <= 0) continue
        fillPoly(cam, f.pts.map(cam::toCam), f.color)
    }
}

/** Nearest point of a box to the eye: big boxes sort by their near side, not their middle. */
private fun boxDist(cam: Cam3, b: FlightWorld.Box): Double {
    val e = cam.eye
    return cam.dist(v3(e[0].coerceIn(b.x0, b.x1), e[1].coerceIn(b.y0, b.y1), e[2].coerceIn(b.z0, b.z1)))
}

// The fighter, in local space (x right, y up, z forward). Open surfaces, so drawn two-sided.
private enum class Part { HULL, CANOPY, WING, ACCENT, ENGINE }
private class Tri(val a: DoubleArray, val b: DoubleArray, val c: DoubleArray, val part: Part)

private val ShipTris: List<Tri> = run {
    val nose = v3(0.0, 0.0, 5.0)
    val canopyFront = v3(0.0, 0.55, 1.6)
    val spine = v3(0.0, 0.9, -0.5)
    val belly = v3(0.0, -0.6, -0.3)
    val tail = v3(0.0, 0.2, -3.0)
    val tris = ArrayList<Tri>()
    for (s in listOf(-1.0, 1.0)) {
        val side = v3(0.9 * s, 0.0, -0.5)
        val rootFront = v3(0.9 * s, 0.0, 0.4)
        val rootBack = v3(0.9 * s, 0.0, -2.4)
        val tip = v3(4.6 * s, -0.5, -2.7)
        tris += Tri(nose, canopyFront, side, Part.HULL)
        tris += Tri(canopyFront, spine, side, Part.CANOPY)
        tris += Tri(spine, tail, side, Part.HULL)
        tris += Tri(nose, side, belly, Part.HULL)
        tris += Tri(belly, side, tail, Part.HULL)
        tris += Tri(rootFront, rootBack, tip, Part.WING)
        // Wingtip fin, canted outward.
        tris += Tri(v3(2.7 * s, -0.25, -1.3), v3(2.9 * s, -0.3, -2.7), v3(3.4 * s, 1.5, -3.0), Part.ACCENT)
        // Stripe along the wing's leading edge.
        tris += Tri(rootFront, v3(1.4 * s, 0.02, -0.4), v3(4.4 * s, -0.46, -2.5), Part.ACCENT)
    }
    tris += Tri(v3(-0.6, 0.35, -2.75), v3(0.6, 0.35, -2.75), v3(0.0, -0.3, -2.6), Part.ENGINE)
    tris
}

private fun DrawScope.drawShip(cam: Cam3, pose: Pose3, bank: Double, accent: Color, boosting: Boolean) {
    val (r, u, f) = basis(pose.h, pose.p, bank)
    fun world(l: DoubleArray) = DoubleArray(3) { i ->
        (if (i == 0) pose.x else if (i == 1) pose.y else pose.z) + l[0] * r[i] + l[1] * u[i] + l[2] * f[i]
    }
    class Facet(val pts: List<DoubleArray>, val depth: Double, val color: Color)
    val facets = ShipTris.map { t ->
        val wa = world(t.a)
        val wb = world(t.b)
        val wc = world(t.c)
        var n = norm(cross3(v3(wb[0] - wa[0], wb[1] - wa[1], wb[2] - wa[2]), v3(wc[0] - wa[0], wc[1] - wa[1], wc[2] - wa[2])))
        // Two-sided: light the side we're looking at.
        if (dot3(n, v3(cam.eye[0] - wa[0], cam.eye[1] - wa[1], cam.eye[2] - wa[2])) < 0) n = v3(-n[0], -n[1], -n[2])
        val color = when (t.part) {
            Part.HULL, Part.WING -> HullColor.shade(lit(n))
            Part.CANOPY -> CanopyColor.shade(lit(n))
            Part.ACCENT -> accent.shade(lit(n))
            Part.ENGINE -> if (boosting) BoostColor else Color(0xFF9FDBFF)
        }
        val pts = listOf(cam.toCam(wa), cam.toCam(wb), cam.toCam(wc))
        Facet(pts, pts.sumOf { it[2] } / 3, color)
    }.sortedByDescending { it.depth }
    for (fc in facets) fillPoly(cam, fc.pts, fc.color)
}

/** A flat shadow straight below, fading with height: the only altitude cue near the ground. */
private fun DrawScope.drawShadow(cam: Cam3, pose: Pose3) {
    if (pose.y > 120) return
    val (r, _, f) = basis(pose.h, 0.0, 0.0)
    val outline = listOf(v3(0.0, 0.0, 5.0), v3(4.6, 0.0, -2.7), v3(0.0, 0.0, -3.0), v3(-4.6, 0.0, -2.7))
    val pts = outline.map { l -> cam.toCam(pose.x + l[0] * r[0] + l[2] * f[0], 0.05, pose.z + l[0] * r[2] + l[2] * f[2]) }
    fillPoly(cam, pts, Color.Black.copy(alpha = (0.4 * (1 - pose.y / 120)).toFloat()))
}

private fun DrawScope.drawBolt(cam: Cam3, b: FlightView.BoltView) {
    val c = cam.toCam(b.x, b.y, b.z)
    if (c[2] < NEAR || c[2] > FAR) return
    // Twin lasers side by side, as a streak along the line of fire.
    var sx = b.dz
    var sz = -b.dx
    val l = sqrt(sx * sx + sz * sz)
    if (l < 1e-6) { sx = 1.0; sz = 0.0 } else { sx /= l; sz /= l }
    val color = if (b.mine) MyLaser else EnemyLaser
    val width = (cam.focal * 0.25 / c[2]).toFloat().coerceIn(1.5f, 9f)
    for (s in listOf(-0.9, 0.9)) {
        val head = v3(b.x + sx * s, b.y, b.z + sz * s)
        val tail = v3(head[0] - b.dx * 8, head[1] - b.dy * 8, head[2] - b.dz * 8)
        line3(cam, head, tail, color.copy(alpha = 0.35f), width * 2.4f)
        line3(cam, head, tail, color, width)
    }
}

/** Fixed outward directions for explosion shards (a spiral over the sphere). */
private val ShardDirs: List<DoubleArray> = (0 until 12).map { i ->
    val y = 1 - (i + 0.5) / 12 * 2
    val r = sqrt(1 - y * y)
    val a = i * 2.39996
    v3(cos(a) * r, y, sin(a) * r)
}

private fun DrawScope.drawExplosion(cam: Cam3, e: FlightView.Explosion) {
    if (e.small) {
        val t = (e.ageMs / FlightArena.SPARK_MS.toDouble()).coerceIn(0.0, 1.0)
        val c = cam.toCam(e.x, e.y, e.z)
        if (c[2] < NEAR) return
        drawCircle(Color.White.copy(alpha = (1 - t).toFloat()), (cam.focal * 1.4 * (1 + t) / c[2]).toFloat(), cam.screen(c))
        return
    }
    val t = (e.ageMs / FlightArena.EXPLOSION_MS.toDouble()).coerceIn(0.0, 1.0)
    val center = cam.toCam(e.x, e.y, e.z)
    if (t < 0.35 && center[2] > NEAR) {
        val k = (1 - t / 0.35).toFloat()
        drawCircle(Color(0xFFFFF3B0).copy(alpha = k), (cam.focal * (3 + 18 * t) / center[2]).toFloat(), cam.screen(center))
    }
    val fade = (1 - t).toFloat()
    ShardDirs.forEachIndexed { i, d ->
        val c = cam.toCam(e.x + d[0] * t * 28, e.y + d[1] * t * 28 - t * t * 12, e.z + d[2] * t * 28)
        if (c[2] < NEAR) return@forEachIndexed
        val s = (cam.focal * 2.2 * (1 - t * 0.5) / c[2]).toFloat()
        val o = cam.screen(c)
        val a = i * 1.7 + t * 9
        val path = Path()
        for (k in 0..2) {
            val ang = a + k * 2 * PI / 3
            val p = o + Offset((cos(ang) * s).toFloat(), (sin(ang) * s).toFloat())
            if (k == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        path.close()
        val color = if (i % 3 == 0) Color(0xFFFFD23F) else if (i % 3 == 1) Color(0xFFFF7A1A) else Color(0xFFE5383B)
        drawPath(path, color.copy(alpha = fade))
    }
}

private fun accentFor(session: Int) = Accents[Math.floorMod(session, Accents.size)]

private fun DrawScope.drawFlight(f: FlightView) {
    val me = f.me
    // Chase camera, lagging the ship's turns a little so the ship swings into them on screen.
    val camYaw = me.h - me.w * 0.12
    val camPitch = me.p - me.q * 0.08
    val (_, u0, f0) = basis(camYaw, camPitch, 0.0)
    val eye = v3(
        me.x - f0[0] * CHASE_BACK + u0[0] * CHASE_UP,
        max(1.0, me.y - f0[1] * CHASE_BACK + u0[1] * CHASE_UP),
        me.z - f0[2] * CHASE_BACK + u0[2] * CHASE_UP,
    )
    val cam = Cam3(eye, camYaw, camPitch, me.w / FlightWorld.MAX_YAW * 0.3, size.width, size.height)

    // Sky in bands toward the horizon, then the ground as everything below it.
    drawRect(SkyTop)
    for ((k, c) in SkyBands) fillBelow(cam, k, c)
    fillBelow(cam, 0.0, GroundHaze)
    for ((k, c) in GroundBands) fillBelow(cam, k, c)
    drawGroundDots(cam)

    if (f.alive) drawShadow(cam, me)
    for (s in f.ships) drawShadow(cam, s.pose)

    // Everything solid, plus bolts and explosions, painted far to near.
    val items = ArrayList<Pair<Double, DrawScope.() -> Unit>>()
    FlightWorld.boxes.forEachIndexed { i, b -> items += boxDist(cam, b) to { drawBox(cam, BoxFaces[i]) } }
    for (s in f.ships) {
        items += cam.dist(v3(s.pose.x, s.pose.y, s.pose.z)) to {
            drawShip(cam, s.pose, s.bank, accentFor(s.session), s.boosting)
        }
    }
    for (b in f.bolts) items += cam.dist(v3(b.x, b.y, b.z)) to { drawBolt(cam, b) }
    for (e in f.explosions) items += cam.dist(v3(e.x, e.y, e.z)) to { drawExplosion(cam, e) }
    items.sortByDescending { it.first }
    for ((_, draw) in items) draw()
    // Our own ship goes on top: the chase camera sits just behind it, so only something we're
    // about to crash into could be in between, while a big box sorted by its nearest corner
    // would often wrongly cover it.
    if (f.alive) drawShip(cam, me, f.bank, MyAccent, false)

    drawTargets(cam, f)
    if (f.alive) drawReticle(cam, me)
    if (f.hurtAgoMs in 0..299) drawRect(Danger.copy(alpha = 0.3f * (1 - f.hurtAgoMs / 300f)))
    if (!f.alive) drawRect(Color.Black.copy(alpha = 0.25f))
}

private fun DrawScope.drawGroundDots(cam: Cam3) {
    if (cam.eye[1] > 400) return
    val spacing = 30.0
    val gx = floor(cam.eye[0] / spacing)
    val gz = floor(cam.eye[2] / spacing)
    val near = ArrayList<Offset>()
    val far = ArrayList<Offset>()
    for (i in -16..16) for (j in -16..16) {
        val c = cam.toCam((gx + i) * spacing, 0.0, (gz + j) * spacing)
        if (c[2] < 2 || c[2] > 600) continue
        val o = cam.screen(c)
        if (o.x < 0 || o.y < 0 || o.x > size.width || o.y > size.height) continue
        if (c[2] < 150) near += o else far += o
    }
    drawPoints(near, PointMode.Points, GroundDot, strokeWidth = 5f, cap = StrokeCap.Round)
    drawPoints(far, PointMode.Points, GroundDot.copy(alpha = 0.7f), strokeWidth = 2.5f, cap = StrokeCap.Round)
}

/** Brackets on every enemy in view, and an arrow at the screen edge toward each one that isn't. */
private fun DrawScope.drawTargets(cam: Cam3, f: FlightView) {
    val margin = 36.dp.toPx()
    for (s in f.ships) {
        val color = accentFor(s.session)
        val c = cam.toCam(s.pose.x, s.pose.y, s.pose.z)
        val o = if (c[2] > NEAR) cam.screen(c) else null
        if (o != null && o.x in margin..size.width - margin && o.y in margin..size.height - margin) {
            val half = max(12.dp.toPx(), (cam.focal * 5.0 / c[2]).toFloat())
            val k = half * 0.45f
            for ((sx, sy) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                val corner = o + Offset(sx * half, sy * half)
                drawLine(color, corner, corner - Offset(sx * k, 0f), 2.5f)
                drawLine(color, corner, corner - Offset(0f, sy * k), 2.5f)
            }
            continue
        }
        // Off screen or behind: point from the centre toward where it is.
        var dx = c[0].toFloat()
        var dy = -c[1].toFloat()
        val len = sqrt(dx * dx + dy * dy)
        if (len < 1e-3f) { dx = 0f; dy = 1f } else { dx /= len; dy /= len }
        val hw = size.width / 2 - margin
        val hh = size.height / 2 - margin
        val t = min(if (abs(dx) > 1e-4f) hw / abs(dx) else Float.MAX_VALUE, if (abs(dy) > 1e-4f) hh / abs(dy) else Float.MAX_VALUE)
        val tipPt = Offset(cam.cx + dx * t, cam.cy + dy * t)
        val back = tipPt - Offset(dx, dy) * 22f
        val side = Offset(-dy, dx) * 11f
        val path = Path().apply {
            moveTo(tipPt.x, tipPt.y); lineTo(back.x + side.x, back.y + side.y); lineTo(back.x - side.x, back.y - side.y); close()
        }
        drawPath(path, color)
    }
}

/** Two gunsight squares down the line of fire, near and far. */
private fun DrawScope.drawReticle(cam: Cam3, me: Pose3) {
    val f = basis(me.h, me.p, 0.0)[2]
    for ((d, half) in listOf(45.0 to 16.dp.toPx(), 110.0 to 9.dp.toPx())) {
        val c = cam.toCam(me.x + f[0] * d, me.y + f[1] * d, me.z + f[2] * d)
        if (c[2] < NEAR) continue
        val o = cam.screen(c)
        drawRect(MyLaser, topLeft = o - Offset(half, half), size = androidx.compose.ui.geometry.Size(2 * half, 2 * half), style = Stroke(2f))
    }
}
