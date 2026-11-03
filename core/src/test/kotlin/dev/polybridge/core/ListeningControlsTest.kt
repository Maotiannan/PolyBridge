package dev.polybridge.core

import org.junit.Assert.*
import org.junit.Test

class ListeningControlsTest {
    @Test fun aLouderInputRaisesTheMeterAndSilenceSettlesIt() {
        val meter = InputLevel()
        val silence = ShortArray(320)
        val quiet = ShortArray(320) { 200 }
        val loud = ShortArray(320) { 9000 }
        assertEquals(0f, meter.read(silence, 320), 0f)
        val a = meter.read(quiet, quiet.size)
        assertTrue(a > 0f && meter.read(loud, loud.size) > a)
        repeat(60) { meter.read(silence, 320) }
        assertEquals(0f, meter.read(silence, 320), 0f)
    }
    @Test fun aFingerMayWanderAndReturnFromTheCancelZoneBeforeRelease() {
        val drag = HoldSlide()
        assertFalse(drag.move(3f))
        assertFalse(drag.move(-5f))
        assertTrue(drag.move(-24f))
        assertTrue(drag.move(-2f))
        assertFalse(drag.move(6f))
    }
}
