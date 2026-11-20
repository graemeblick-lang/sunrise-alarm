package au.sunrise.alarm

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class HomeTextTest {
    private val mel = ZoneId.of("Australia/Melbourne")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, mel)

    @Test fun planMatchesThePage() {
        // tests/schedule.test.js: from Mon 23 Nov 07:00, dur 20 → Tue..Fri then next Mon, sunrise 20 min before
        val p = AlarmSettings(dur = 20).plan(at(2026, 11, 23, 7), 5)
        assertEquals(listOf(24, 25, 26, 27, 30), p.map { ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.second), mel).dayOfMonth })
        p.forEach { (s, a) -> assertEquals(20 * 60_000L, a - s) }
    }

    @Test fun planOneOffAndOff() {
        assertEquals(1, AlarmSettings(days = emptySet()).plan(at(2026, 11, 23, 7)).size)
        assertEquals(0, AlarmSettings(auto = false).plan(at(2026, 11, 23, 7)).size)
        assertEquals(8, AlarmSettings().plan(at(2026, 11, 23, 7)).size)
    }

    @Test fun planJsonIsWhatTheSchedulerTakes() {
        val j = JSONArray(AlarmSettings().planJson(at(2026, 11, 23, 7)))
        assertEquals(8, j.length()); assertEquals(2, j.getJSONArray(0).length())
        assertEquals("[]", AlarmSettings(auto = false).planJson(at(2026, 11, 23, 7)))
    }

    @Test fun nextAlarmWords() {
        assertEquals("Alarm tomorrow at 6:30 am · in 23h 30m", HomeText.next(AlarmSettings(), at(2026, 11, 23, 7), false))
        assertEquals("Alarm today at 06:30 · in 1h 30m", HomeText.next(AlarmSettings(), at(2026, 11, 23, 5), true))
        assertEquals("Alarm Mon at 6:30 am · in 57h 30m", HomeText.next(AlarmSettings(), at(2026, 11, 27, 21), false))
        assertEquals("Paused until Thu 26 Nov · Alarm Fri at 6:30 am · in 95h 30m",
            HomeText.next(AlarmSettings(pauseUntil = "2026-11-26"), at(2026, 11, 23, 7), false))
        assertEquals("One-off · Alarm tomorrow at 6:30 am · in 23h 30m", HomeText.next(AlarmSettings(days = emptySet()), at(2026, 11, 23, 7), false))
    }

    @Test fun summaries() {
        assertEquals("Weekdays · sunrise 30 min · snooze 9 min", HomeText.alarm(AlarmSettings(), at(2026, 11, 23).toLocalDate()))
        assertEquals("Paused · Mon, Wed (own times) · sunrise 20 min · snooze 5 min",
            HomeText.alarm(AlarmSettings(days = setOf(3, 1), perDay = true, dur = 20, snooze = 5, pauseUntil = "2026-12-01"), at(2026, 11, 23).toLocalDate()))
        assertEquals("Chimes · fade 3 min", HomeText.sound(SoundSettings()))
        assertEquals("📻 triple j · fade 3 min · starts 10 min early", HomeText.sound(SoundSettings(src = "radio", station = 1, earlyMin = 10)))
        assertEquals("Spotify · what you last played · fade 3 min", HomeText.sound(SoundSettings(src = "spotify")))
        assertEquals("🎵 Podcast Addict · fade 1 min", HomeText.sound(SoundSettings(src = "apps", appName = "Podcast Addict", fade = 1)))
        assertEquals("Ocean dawn theme · own sunrise colours · 80%", HomeText.look(LookSettings(theme = "ocean", sunCustom = true, sunMax = 80)))
        assertEquals("Weather for Sydney · Todoist on", HomeText.morning(MorningSettings(place = "Sydney", todoToken = "x")))
    }
}
