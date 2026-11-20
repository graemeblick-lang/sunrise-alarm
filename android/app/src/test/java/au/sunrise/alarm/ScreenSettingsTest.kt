package au.sunrise.alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenSettingsTest {
    @Test fun lookReadsAndClamps() {
        val s = LookSettings.from(JSONObject("""{"theme":"ocean","sunCustom":true,"sunCols":["#111111","#222222","#333333"],"dim":9,"sunMax":5}"""))
        assertEquals("ocean", s.theme); assertEquals(true, s.sunCustom)
        assertEquals(listOf("#111111", "#222222", "#333333"), s.sunCols)
        assertEquals(6, s.dim); assertEquals(15, s.sunMax)
    }

    @Test fun lookIgnoresBadColours() =
        assertEquals(LookSettings().sunCols, LookSettings.from(JSONObject("""{"sunCols":["#111111","red"]}""")).sunCols)

    @Test fun lookWriteKeepsOtherKeys() {
        val j = JSONObject("""{"time":"06:30","src":"radio"}""")
        LookSettings(theme = "forest", sunMax = 80).into(j)
        assertEquals("06:30", j.getString("time")); assertEquals("radio", j.getString("src"))
        assertEquals("forest", j.getString("theme")); assertEquals(80, j.getInt("sunMax"))
    }

    @Test fun morningDefaultsAreMelbourne() {
        val s = MorningSettings.from(JSONObject("{}"))
        assertEquals("Melbourne", s.place); assertEquals(-37.814, s.lat, 0.0)
    }

    @Test fun morningRoundTrip() {
        val j = JSONObject("""{"theme":"phone"}""")
        MorningSettings(lat = -33.869, lon = 151.209, place = "Sydney", weatherLink = "https://x", todoToken = "abc").into(j)
        val s = MorningSettings.from(j)
        assertEquals("Sydney", s.place); assertEquals(151.209, s.lon, 0.0); assertEquals("abc", s.todoToken)
        assertEquals("phone", j.getString("theme"))
    }

    @Test fun oldWillyWeatherLinkIsKept() =
        assertEquals("https://www.willyweather.com.au/vic/x.html", MorningSettings.from(JSONObject("""{"willy":"https://www.willyweather.com.au/vic/x.html"}""")).weatherLink)

    @Test fun weatherLinks() {
        assertEquals(true, MorningSettings.linkOk("https://www.bom.gov.au/places/vic/melbourne/"))
        assertEquals(true, MorningSettings.linkOk("https://bom.gov.au"))
        assertEquals(true, MorningSettings.linkOk("https://www.willyweather.com.au/vic/x.html"))
        assertEquals(false, MorningSettings.linkOk("https://www.bom.gov.au.evil.com/"))
        assertEquals(false, MorningSettings.linkOk("http://www.bom.gov.au/"))
        assertEquals("Open BOM forecast ↗", MorningSettings.linkLabel(""))
        assertEquals("Open WillyWeather ↗", MorningSettings.linkLabel("https://www.willyweather.com.au/vic/x.html"))
    }

    @Test fun roundsToThreeDecimals() = assertEquals(-37.815, MorningSettings.round3(-37.81462), 0.0)
}
