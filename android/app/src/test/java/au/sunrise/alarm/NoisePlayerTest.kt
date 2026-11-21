package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class NoisePlayerTest {
    @Test fun loopsAreTheRightLengthAndNotHarsh() {
        NoisePlayer.KINDS.forEach { kind ->
            val pcm = NoisePlayer.loop(kind, rate = 8_000)
            assertEquals(20 * 8_000, pcm.size)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$kind peak $peak", peak in 15_000..20_000)        // about 60 % of full scale
            // The join is smooth: the last sample and the first are close compared with the peak
            assertTrue("$kind join", abs(pcm.last() - pcm.first()) < peak)
        }
    }
}
