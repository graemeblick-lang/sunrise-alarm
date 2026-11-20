package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class MorningDataTest {
    private val mel = ZoneId.of("Australia/Melbourne")
    private fun local(ms: Long) = Instant.ofEpochMilli(ms).atZone(mel).toLocalTime().toString().take(5)

    @Test fun melbourneSunTimesAreSensible() {
        // Midsummer and midwinter in Melbourne (official: ~5:58/20:44 and ~7:34/17:09)
        val (r1, s1) = SunTimes.of(LocalDate.of(2026, 12, 21), -37.814, 144.963)!!
        assertEquals("05:5", local(r1).take(4)); assertEquals("20:4", local(s1).take(4))
        val (r2, s2) = SunTimes.of(LocalDate.of(2026, 6, 21), -37.814, 144.963)!!
        assertEquals("07:3", local(r2).take(4)); assertEquals("17:0", local(s2).take(4))
    }

    @Test fun noSunriseNearThePoleInWinter() = assertNull(SunTimes.of(LocalDate.of(2026, 6, 21), -80.0, 0.0))

    private val sample = """{"current":{"temperature_2m":17.6,"apparent_temperature":16.2,"weather_code":2,"wind_speed_10m":11},
      "daily":{"time":["2026-11-23","2026-11-24"],"weather_code":[61,0],"temperature_2m_max":[24.4,30.1],"temperature_2m_min":[12.5,15],
      "precipitation_probability_max":[70,null],"precipitation_sum":[3.2,0],"wind_speed_10m_max":[28.6,15],"uv_index_max":[9.1,10]}}"""

    @Test fun weatherForToday() {
        val w = WeatherData.parse(sample, LocalDate.of(2026, 11, 23))
        assertEquals("⛅", w.emoji); assertEquals("Partly cloudy", w.now); assertEquals(18, w.temp); assertEquals(16, w.feels)
        assertEquals("light rain", w.todayText); assertEquals(24, w.high); assertEquals(13, w.low)
        assertEquals(70, w.rainChance); assertEquals(3.2, w.rainMm, 0.0); assertEquals(29, w.wind); assertEquals(9, w.uv)
    }

    @Test fun weatherUsesTomorrowsRowWhenTheDateMatches() {
        val w = WeatherData.parse(sample, LocalDate.of(2026, 11, 24))
        assertEquals(30, w.high); assertEquals(0, w.rainChance); assertEquals("clear", w.todayText)
    }

    @Test fun unknownWeatherCode() = assertEquals("🌡️" to "—", WeatherData.describe(1234))

    @Test fun todoistBothShapes() {
        val today = LocalDate.of(2026, 11, 23)
        val v1 = """{"results":[{"content":"Bins","priority":1,"due":{"date":"2026-11-23"}},{"content":"Tax","priority":4,"due":{"date":"2026-11-20T09:00:00"}}]}"""
        val r = Todoist.parse(v1, today)
        assertEquals(listOf("Tax", "Bins"), r.map { it.content })
        assertEquals(true, r[0].overdue); assertEquals(false, r[1].overdue)
        assertEquals(1, Todoist.parse("""[{"content":"Walk","priority":2}]""", today).size)
    }
}
