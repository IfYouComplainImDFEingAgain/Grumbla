package app.notmumla.nudge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NudgeTest {
    private var now = 1_000_000L
    private val limiter = NudgeLimiter(clock = { now }, perSenderMs = 10_000, globalMs = 3_000)

    @Test fun messageIsExact() {
        assertTrue(NudgeMessage.isValid(NudgeMessage.encode()))
        for (bad in listOf("", "nudge ", "Nudge", "nudgenudge", "nudg")) {
            assertFalse(bad, NudgeMessage.isValid(bad.toByteArray()))
        }
    }

    @Test fun perSenderCooldown() {
        assertTrue(limiter.allow(1))
        now += 5_000
        assertFalse("same sender within 10 s", limiter.allow(1))
        now += 5_000
        assertTrue(limiter.allow(1))
    }

    @Test fun globalCooldownAcrossSenders() {
        assertTrue(limiter.allow(1))
        now += 1_000
        assertFalse("anyone within 3 s", limiter.allow(2))
        now += 2_000
        assertTrue(limiter.allow(2))
    }

    @Test fun droppedNudgesDontExtendTheCooldown() {
        assertTrue(limiter.allow(1))
        repeat(9) { now += 1_000; limiter.allow(1) } // spam every second
        now += 1_000
        assertTrue("10 s after the last *accepted* nudge", limiter.allow(1))
    }

    @Test fun boingIsShortAndClipFree() {
        val pcm = NudgeEffects.boing()
        assertEquals(44_100 * 45 / 100, pcm.size)
        assertTrue(pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE * 0.65)
        assertTrue("starts silent (no click)", abs(pcm[0].toInt()) < 100)
        assertTrue("fades out", abs(pcm.last().toInt()) < Short.MAX_VALUE * 0.1)
    }
}
