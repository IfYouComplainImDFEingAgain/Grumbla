package app.notmumla.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.notmumla.game.flight.FlightSound
import app.notmumla.game.flight.SoundEvent
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * The dogfight's sound effects: synthesized once (no audio assets to ship or license), written to
 * the cache as WAVs and played through a [SoundPool] so lasers and explosions can overlap.
 *
 * Game usage, so it follows the media volume rather than the call volume.
 */
class FlightSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(MAX_STREAMS)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = HashMap<FlightSound, Int>()
    private val loaded = HashSet<Int>()
    private val engineId: Int
    private var engineStream = 0
    private var engineLevel = 0f

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val dir = File(context.cacheDir, "flight_sfx").apply { mkdirs() }
        for (s in FlightSound.entries) {
            val file = File(dir, "${s.name.lowercase()}_v$VERSION.wav")
            if (!file.exists()) file.writeBytes(wav(synth(s)))
            ids[s] = pool.load(file.path, 1)
        }
        val engine = File(dir, "engine_v$VERSION.wav")
        if (!engine.exists()) engine.writeBytes(wav(engineLoop()))
        engineId = pool.load(engine.path, 1)
    }

    /**
     * Call every frame: the afterburner loop swells in (pitch rising with it) while [on], and
     * fades out after; [dtMs] is the frame time.
     */
    fun engine(on: Boolean, dtMs: Long) {
        val dt = dtMs.coerceIn(0, 100) / 1000f
        engineLevel = if (on) min(1f, engineLevel + dt / 0.15f) else maxOf(0f, engineLevel - dt / 0.35f)
        if (engineLevel <= 0f) {
            if (engineStream != 0) { pool.stop(engineStream); engineStream = 0 }
            return
        }
        if (engineStream == 0) {
            if (synchronized(loaded) { engineId !in loaded }) return
            engineStream = pool.play(engineId, 0f, 0f, 2, -1, 1f)
            if (engineStream == 0) return
        }
        val v = 0.5f * engineLevel
        pool.setVolume(engineStream, v, v)
        pool.setRate(engineStream, 0.8f + 0.3f * engineLevel)
    }

    fun play(events: List<SoundEvent>) {
        for (e in events) {
            val id = ids[e.sound] ?: continue
            if (synchronized(loaded) { id !in loaded }) continue
            val v = e.volume.coerceIn(0f, 1f)
            val pan = e.pan.coerceIn(-1f, 1f)
            pool.play(id, v * min(1f, 1 - pan), v * min(1f, 1 + pan), PRIORITY[e.sound] ?: 0, 0, 1f)
        }
    }

    fun release() {
        engineStream = 0
        pool.release()
    }

    companion object {
        private const val RATE = 22_050
        private const val MAX_STREAMS = 10
        /** Bump when a sound changes, so stale cached WAVs aren't reused. */
        private const val VERSION = 1
        private val PRIORITY = mapOf(FlightSound.EXPLOSION to 3, FlightSound.HURT to 2, FlightSound.HIT to 1)

        internal fun synth(s: FlightSound): ShortArray = when (s) {
            FlightSound.MY_LASER -> laser(from = 1900.0, to = 320.0, seconds = 0.13)
            FlightSound.ENEMY_LASER -> laser(from = 1100.0, to = 180.0, seconds = 0.16)
            FlightSound.HIT -> tick()
            FlightSound.HURT -> crunch()
            FlightSound.EXPLOSION -> boom()
        }

        private fun render(seconds: Double, sample: (t: Double) -> Double): ShortArray {
            val n = (RATE * seconds).toInt()
            return ShortArray(n) { i ->
                val t = i.toDouble() / RATE
                // 3 ms fade-out so nothing ends on a click.
                val tail = min(1.0, (seconds - t) / 0.003)
                (sample(t) * tail * 0.85 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }

        /** "Pew": a buzzy tone sweeping down exponentially, under a quick decay. */
        private fun laser(from: Double, to: Double, seconds: Double): ShortArray {
            var phase = 0.0
            val k = ln(to / from) / seconds
            return render(seconds) { t ->
                phase += 2 * PI * from * exp(k * t) / RATE
                val env = min(1.0, t / 0.002) * exp(-t / (seconds * 0.45))
                // Soft-clipped sine: square-ish, the old sound-chip edge without the harsh aliasing.
                tanh(3 * sin(phase)) * 0.8 * env
            }
        }

        /** A bright, short tick for "your shot connected". */
        private fun tick() = render(0.06) { t -> sin(2 * PI * 2400 * t) * exp(-t / 0.012) * 0.7 }

        /** Taking a hit: a burst of grit over a low buzz. */
        private fun crunch(): ShortArray {
            val rnd = Random(3)
            var lp = 0.0
            return render(0.22) { t ->
                lp += (rnd.nextDouble(-1.0, 1.0) - lp) * 0.35
                val buzz = tanh(4 * sin(2 * PI * 110 * t))
                (lp * 1.6 + buzz * 0.5) * exp(-t / 0.06)
            }
        }

        /** Explosion: noise through a low-pass that closes as it fades, over a falling rumble. */
        private fun boom(): ShortArray {
            val rnd = Random(11)
            var lp = 0.0
            var lp2 = 0.0
            var phase = 0.0
            return render(1.3) { t ->
                val cutoff = 0.5 * exp(-t * 3) + 0.03
                lp += (rnd.nextDouble(-1.0, 1.0) - lp) * cutoff
                lp2 += (lp - lp2) * cutoff
                phase += 2 * PI * (70 * exp(-t * 1.5) + 30) / RATE
                val env = min(1.0, t / 0.004) * exp(-t / 0.35)
                (lp2 * 3.2 + sin(phase) * 0.45) * env
            }
        }

        /**
         * A jet roar that loops seamlessly: harmonics that fit a whole number of cycles in the
         * loop, plus rumbling noise whose end is crossfaded into its start so the seam is silent.
         */
        internal fun engineLoop(): ShortArray {
            val n = RATE // one second
            val fade = RATE / 10
            val rnd = Random(5)
            var lp = 0.0
            var lp2 = 0.0
            val raw = DoubleArray(n + fade) { i ->
                val t = i.toDouble() / RATE
                lp += (rnd.nextDouble(-1.0, 1.0) - lp) * 0.12
                lp2 += (lp - lp2) * 0.25
                // 60 Hz drone with a 7 Hz throb, plus a 240 Hz turbine whine.
                val drone = tanh(2.5 * sin(2 * PI * 60 * t)) * (0.75 + 0.25 * sin(2 * PI * 7 * t))
                val whine = sin(2 * PI * 240 * t) * 0.18
                lp2 * 2.6 + drone * 0.35 + whine
            }
            return ShortArray(n) { i ->
                val s = if (i < fade) raw[i] * (i.toDouble() / fade) + raw[i + n] * (1 - i.toDouble() / fade) else raw[i]
                (s * 0.6 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }

        private fun wav(pcm: ShortArray): ByteArray {
            val data = pcm.size * 2
            return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(data)
                pcm.forEach { putShort(it) }
            }.array()
        }
    }
}
