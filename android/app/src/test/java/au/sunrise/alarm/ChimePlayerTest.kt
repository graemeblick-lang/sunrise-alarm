package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ChimePlayerTest {
    @Test fun envelopeRisesThenFades() {
        assertEquals(0.0001, ChimePlayer.envelope(0.0), 1e-9)
        assertEquals(1.0, ChimePlayer.envelope(0.015), 1e-6)
        assertTrue(ChimePlayer.envelope(1.0) < ChimePlayer.envelope(0.1))
        assertEquals(0.0, ChimePlayer.envelope(3.0), 0.0)
    }

    @Test fun phraseIsTheRightLengthAndNeverClips() {
        val p = ChimePlayer.phrase(8000)
        assertEquals((ChimePlayer.PHRASE_SECONDS * 8000).toInt(), p.size)
        val peak = p.maxOf { abs(it.toInt()) }
        assertTrue("loud enough: $peak", peak > 20_000)
        assertTrue("no clipping: $peak", peak < Short.MAX_VALUE)
    }

    @Test fun quietBeforeTheFirstBellAndAtTheEnd() {
        val p = ChimePlayer.phrase(8000)
        assertTrue(p.take(300).all { abs(it.toInt()) < 50 })          // first 37 ms: before the 50 ms start
        assertTrue(p.takeLast(200).all { abs(it.toInt()) < 2000 })    // the last bell has mostly faded
    }
}
