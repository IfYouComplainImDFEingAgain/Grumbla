package app.notmumla.ui.game

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import app.notmumla.game.house.HouseSound
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The block house's sounds, synthesized once like [FlightSounds]: a whoosh for a swing and a sharp
 * smack for a slap that lands. Game usage, so it follows the media volume.
 */
class HouseSounds(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()
    private val ids = HashMap<HouseSound, Int>()
    private val loaded = HashSet<Int>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        val dir = File(context.cacheDir, "house_sfx").apply { mkdirs() }
        for (s in HouseSound.entries) {
            val file = File(dir, "${s.name.lowercase()}_v$VERSION.wav")
            if (!file.exists()) file.writeBytes(wav(synth(s)))
            ids[s] = pool.load(file.path, 1)
        }
    }

    fun play(sound: HouseSound, volume: Float, pan: Float) {
        val id = ids[sound] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        val v = volume.coerceIn(0f, 1f) * if (sound == HouseSound.SWISH) 0.5f else 1f
        val p = pan.coerceIn(-1f, 1f)
        pool.play(id, v * min(1f, 1 - p), v * min(1f, 1 + p), if (sound == HouseSound.SLAP) 1 else 0, 0, 1f)
    }

    fun release() = pool.release()

    companion object {
        private const val RATE = 22_050
        private const val VERSION = 1

        private fun synth(s: HouseSound): ShortArray = when (s) {
            HouseSound.SWISH -> swish()
            HouseSound.SLAP -> smack()
        }

        private fun render(seconds: Double, sample: (t: Double) -> Double): ShortArray {
            val n = (RATE * seconds).toInt()
            return ShortArray(n) { i ->
                val t = i.toDouble() / RATE
                val tail = min(1.0, (seconds - t) / 0.003)
                (sample(t) * tail * 0.85 * Short.MAX_VALUE).toInt().coerceIn(-32767, 32767).toShort()
            }
        }

        /** Air past an arm: noise through a band that sweeps up, swelling and fading. */
        private fun swish(): ShortArray {
            val rnd = Random(21)
            var lp = 0.0
            var hp = 0.0
            return render(0.22) { t ->
                val k = 0.08 + 0.5 * (t / 0.22)
                lp += (rnd.nextDouble(-1.0, 1.0) - lp) * k
                hp = lp - hp * 0.6
                val env = sin(PI * (t / 0.22)).let { it * it }
                hp * 1.4 * env
            }
        }

        /** Hand on cheek: a very short bright crack over a soft low thump. */
        private fun smack(): ShortArray {
            val rnd = Random(4)
            var prev = 0.0
            return render(0.18) { t ->
                val n = rnd.nextDouble(-1.0, 1.0)
                val crack = (n - prev) * exp(-t / 0.012)
                prev = n
                val thump = sin(2 * PI * 140 * t * (1 - t * 2)) * exp(-t / 0.04)
                crack * 1.1 + thump * 0.6
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
