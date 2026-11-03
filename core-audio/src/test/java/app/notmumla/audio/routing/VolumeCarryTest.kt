package app.notmumla.audio.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class VolumeCarryTest {

    // Typical ranges: media 0..15, call 1..7 (call volume can't go to zero).
    private fun carry(index: Int, from: IntRange, to: IntRange) =
        carryVolume(index, from.first, from.last, to.first, to.last)

    @Test fun `ends of the range map to ends`() {
        assertEquals(7, carry(15, 0..15, 1..7))
        assertEquals(15, carry(7, 1..7, 0..15))
        assertEquals(1, carry(1, 1..7, 0..15)) // lowest call level is audible → lowest audible media level
        assertEquals(1, carry(0, 0..15, 1..7)) // muted media → call minimum (call can't mute)
    }

    @Test fun `middle maps proportionally`() {
        assertEquals(4, carry(8, 0..15, 1..7))   // 8/15 of 1..7 = 4.2
        assertEquals(8, carry(4, 1..7, 0..15))   // 3/6 of 0..15 = 7.5
    }

    @Test fun `a quiet but audible level doesn't round to silence`() {
        assertEquals(1, carry(1, 0..30, 0..7)) // 1/30 of 0..7 rounds to 0, kept audible
        assertEquals(4, carry(1, 0..7, 0..30)) // 1/7 of 0..30
    }

    @Test fun `muted stays muted`() {
        assertEquals(0, carry(0, 0..15, 0..15))
    }
}
