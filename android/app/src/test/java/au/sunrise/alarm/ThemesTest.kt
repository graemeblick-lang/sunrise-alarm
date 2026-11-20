package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The native screens must use the same colours as the page (index.html mix() and applyTheme()). */
class ThemesTest {
    @Test fun mixMatchesThePage() {
        // Page: mix("#000000","#ffffff",.5) → Math.round(127.5) = 128 = #808080
        assertEquals("#808080", Themes.hex(Themes.mix(0x000000, 0xffffff, .5)))
        assertEquals("#000000", Themes.hex(Themes.mix(0x000000, 0xffffff, -1.0)))   // clamped
        assertEquals("#ffffff", Themes.hex(Themes.mix(0x000000, 0xffffff, 2.0)))
        // Page: mix("#0f0e0d","#ffb36b",.04) for the Sunrise theme's surface
        assertEquals("#191511", Themes.hex(Themes.mix(0x0f0e0d, 0xffb36b, .04)))
    }

    @Test fun parsesAndPrintsHex() {
        assertEquals(0xff7a26, Themes.parse("#ff7a26"))
        assertEquals(0xff7a26, Themes.parse("ff7a26"))
        assertNull(Themes.parse("#fff"))
        assertNull(Themes.parse("nope!!"))
        assertEquals("#03162e", Themes.hex(0x03162e))
    }

    @Test fun unknownThemeFallsBackToSunrise() = assertEquals("sunrise", Themes.byKey("nope").key)

    @Test fun tonesForSunrise() {
        val t = Themes.tones(Themes.byKey("sunrise"))
        assertEquals(0xffb36b, t.primary)
        assertEquals("#191511", Themes.hex(t.surface))
        assertEquals(0xe7e1de, t.onSurface)
    }

    @Test fun customSkyStartsBlackAndEndsAtDaylight() {
        val sky = Themes.customSky(0x3a0d02, 0xff7a26, 0xfff1d6)
        assertEquals(0x000000, sky.first().second)
        assertEquals(0x3a0d02, sky[2].second)
        assertEquals(0xfff1d6, sky.last().second)
        assertEquals(6, sky.size)
    }

    @Test fun presetsHaveThreeValidColours() =
        Themes.SUN_PRESETS.forEach { (_, cols) -> assertEquals(3, cols.mapNotNull { Themes.parse(it) }.size) }
}
