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

    @Test fun planNoDays() {
        assertEquals(0, AlarmSettings(days = emptySet()).plan(at(2026, 11, 23, 7)).size)
        assertEquals(8, AlarmSettings().plan(at(2026, 11, 23, 7)).size)
    }

    @Test fun planJsonIsWhatTheSchedulerTakes() {
        val j = JSONArray(AlarmSettings().planJson(at(2026, 11, 23, 7)))
        assertEquals(8, j.length()); assertEquals(2, j.getJSONArray(0).length())
        assertEquals("[]", AlarmSettings(days = emptySet()).planJson(at(2026, 11, 23, 7)))
    }

    @Test fun nextAlarmWords() {
        assertEquals("Alarm tomorrow at 6:30 am · in 23h 30m", HomeText.next(AlarmSettings(), at(2026, 11, 23, 7), false))
        assertEquals("Alarm today at 06:30 · in 1h 30m", HomeText.next(AlarmSettings(), at(2026, 11, 23, 5), true))
        assertEquals("Alarm Mon at 6:30 am · in 57h 30m", HomeText.next(AlarmSettings(), at(2026, 11, 27, 21), false))
        assertEquals("Paused until Thu 26 Nov · Alarm Fri at 6:30 am · in 95h 30m",
            HomeText.next(AlarmSettings(pauseUntil = "2026-11-26"), at(2026, 11, 23, 7), false))
        assertEquals("Repeat is off: choose days below", HomeText.next(AlarmSettings(days = emptySet()), at(2026, 11, 23, 7), false))
        val now = at(2026, 11, 23, 19, 0); val nowMs = now.toInstant().toEpochMilli()
        assertEquals("One-off alarm today at 7:05 pm · in 5 min", HomeText.once(AlarmSettings().withOnce(nowMs + 5 * 60_000, nowMs), now, false))
        assertEquals("", HomeText.once(AlarmSettings(), now, false))
    }

    @Test fun summaries() {
        assertEquals("Chimes · fade 3 min · snooze 9 min", HomeText.sound(SoundSettings(), 9))
        assertEquals("📻 triple j · fade 3 min · starts 10 min early · snooze 5 min", HomeText.sound(SoundSettings(src = "radio", station = 1, earlyMin = 10), 5))
        assertEquals("Spotify · what you last played · fade 3 min · snooze 9 min", HomeText.sound(SoundSettings(src = "spotify"), 9))
        assertEquals("🎵 Podcast Addict · fade 1 min · snooze 9 min", HomeText.sound(SoundSettings(src = "apps", appName = "Podcast Addict", fade = 1), 9))
        assertEquals("Ocean dawn theme", HomeText.night(LookSettings(theme = "ocean")))
        assertEquals("Starts on the charger after 9 pm · reminder 8 h before · Ocean dawn theme",
            HomeText.night(LookSettings(theme = "ocean"), NightSettings(autoNight = 21, bedtime = 8)))
        assertEquals("20 min · own colours · up to 80% bright", HomeText.sunrise(LookSettings(sunCustom = true, sunMax = 80), 20))
        assertEquals("Weather for Sydney · Todoist on", HomeText.morning(MorningSettings(place = "Sydney", todoToken = "x")))
    }
}
