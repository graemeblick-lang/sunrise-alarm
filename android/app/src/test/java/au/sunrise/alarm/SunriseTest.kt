package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/** Values from the page's own setSunrise() (checked against index.html in development). */
class SunriseTest {
    private val sunrise = ThemeData.ALL.getValue("sunrise")

    @Test fun nightIsBlackAndDim() {
        val f = Sunrise.frame(sunrise, LookSettings(dim = 2, sunMax = 60), 0.0)
        assertEquals("#000000", Themes.hex(f.sky)); assertEquals(0.008, f.brightness, 1e-12)
        assertEquals(140.0, f.sunY, 1e-9); assertEquals(20.0, f.sunR, 1e-9)
    }

    @Test fun alarmTimeIsDaylightAtYourBrightness() {
        val f = Sunrise.frame(sunrise, LookSettings(dim = 2, sunMax = 60), 1.0)
        assertEquals("#fff1d6", Themes.hex(f.sky)); assertEquals(0.6, f.brightness, 1e-12)
    }

    @Test fun nightLightOverridesBrightness() {
        assertEquals(0.3, Sunrise.frame(sunrise, LookSettings(), 0.0, nightLight = 1).brightness, 0.0)
        assertEquals(0.8, Sunrise.frame(sunrise, LookSettings(), 0.5, nightLight = 2).brightness, 0.0)
    }

    @Test fun rampBlendsBetweenStops() {
        val stops = listOf(0f to 0x000000, 1f to 0xffffff)
        assertEquals("#808080", Themes.hex(Sunrise.ramp(stops, 0.5)))
        assertEquals("#ffffff", Themes.hex(Sunrise.ramp(stops, 2.0)))
    }

    @Test fun everyThemeIsComplete() = ThemeData.ALL.values.forEach { t ->
        assertEquals(6, t.night.size)
        listOf("r1", "r2", "r3", "rInk", "rBtn", "rBtnText", "m1", "m2", "m3", "mInk", "mGreet", "nl1", "nb1").forEach { k -> assert(t.vars.containsKey(k)) { "${t.name} has $k" } }
    }

    @Test fun phoneThemeFromWallpaperColours() {
        val t = Sunrise.phoneTheme(mapOf("a1_100" to 0xeeeeee, "a1_500" to 0x3366cc, "a1_900" to 0x001133, "n1_900" to 0x111111))
        assertEquals(0x3366cc, t.clockHi); assertEquals(0xffffff, t.v("rBtnText")); assertEquals(0x111111, t.v("mInk"))
    }
}
