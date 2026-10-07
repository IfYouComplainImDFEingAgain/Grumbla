package app.notmumla.ui.game

import app.notmumla.game.flight.FlightSound
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FlightSoundsTest {
    @Test fun everySoundIsAudibleAndMostlyUnclipped() {
        for (s in FlightSound.entries) {
            val pcm = FlightSounds.synth(s)
            assertTrue(s.name, pcm.size in 1_000..40_000)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("${s.name} peak $peak", peak > 8_000)
            val clipped = pcm.count { abs(it.toInt()) >= 32_000 }
            assertTrue("${s.name} clipped $clipped", clipped < pcm.size / 20)
            assertTrue("${s.name} ends silent", abs(pcm.last().toInt()) < 500)
        }
    }

    @Test fun engineLoopsWithoutAClick() {
        val pcm = FlightSounds.engineLoop()
        assertTrue(pcm.maxOf { abs(it.toInt()) } > 8_000)
        // Across the seam the step is no bigger than between neighbouring samples elsewhere.
        val steps = (1 until pcm.size).map { abs(pcm[it] - pcm[it - 1]) }
        assertTrue(abs(pcm.first() - pcm.last()) <= steps.max())
    }
}
