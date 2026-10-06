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

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val dir = File(context.cacheDir, "flight_sfx").apply { mkdirs() }
        for (s in FlightSound.entries) {
            val file = File(dir, "${s.name.lowercase()}_v$VERSION.wav")
            if (!file.exists()) file.writeBytes(wav(synth(s)))
            ids[s] = pool.load(file.path, 1)
        }
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

    fun release() = pool.release()

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
