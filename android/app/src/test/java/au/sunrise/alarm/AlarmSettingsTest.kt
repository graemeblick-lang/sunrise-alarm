package au.sunrise.alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The native "next alarm" agrees with the page's (tests/schedule.test.js), except that no days now means no repeating alarm. */
class AlarmSettingsTest {
    private val mel = ZoneId.of("Australia/Melbourne")
    private fun at(y: Int, mo: Int, d: Int, h: Int = 0, mi: Int = 0) = ZonedDateTime.of(y, mo, d, h, mi, 0, 0, mel)
    private fun fmt(t: ZonedDateTime?) = t?.let { "%04d-%02d-%02d %02d:%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute) }
    private val base = AlarmSettings()
    private val everyDay = base.copy(days = (0..6).toSet())

    @Test fun weekdayLaterToday() = assertEquals("2026-11-23 06:30", fmt(base.nextAlarm(at(2026, 11, 23, 5))))
    @Test fun afterTodaysTimeGoesToNextWeekday() = assertEquals("2026-11-24 06:30", fmt(base.nextAlarm(at(2026, 11, 23, 7))))
    @Test fun fridayEveningSkipsWeekend() = assertEquals("2026-11-30 06:30", fmt(base.nextAlarm(at(2026, 11, 27, 21))))
    @Test fun exactlyAtAlarmTimeCountsAsPassed() = assertEquals("2026-11-24 06:30", fmt(base.nextAlarm(at(2026, 11, 23, 6, 30))))
    @Test fun noDaysIsNoRepeatingAlarm() = assertNull(base.copy(days = emptySet()).nextAlarm(at(2026, 11, 28, 22)))

    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun quickAlarmComesFirstAndItsSunriseStartsNow() {
        val now = at(2026, 11, 23, 19, 0)
        val s = base.withOnce(ms(now) + 5 * 60_000, ms(now))
        assertEquals("2026-11-23 19:05", fmt(s.upcoming(now)))
        assertEquals(ms(now), s.sunriseFor(s.once))                        // 5 min sunrise, not 30
        val p = s.plan(now)
        assertEquals(8, p.size)
        assertEquals(ms(now) to s.once, p[0])
        assertEquals("2026-11-24 06:30", fmt(java.time.Instant.ofEpochMilli(p[1].second).atZone(mel)))
    }

    @Test fun laterOneOffGetsTheFullSunrise() {
        val now = at(2026, 11, 23, 19, 0)
        val once = ms(at(2026, 11, 28, 5, 0))                             // a Saturday
        val s = base.withOnce(once, ms(now))
        assertEquals(once - 30 * 60_000, s.sunriseFor(once))
        assertEquals("2026-11-24 06:30", fmt(s.upcoming(now)))
        assertEquals(true, s.plan(now).any { it.second == once })
    }

    @Test fun oneOffOnlyWhenNoDays() {
        val now = at(2026, 11, 23, 19, 0)
        val s = base.copy(days = emptySet()).withOnce(ms(now) + 10 * 60_000, ms(now))
        assertEquals(listOf(s.once), s.plan(now).map { it.second })
        assertEquals(0, s.plan(at(2026, 11, 23, 20, 0)).size)            // once it's passed, nothing is left
    }

    @Test fun skippingTheOneOffDropsIt() {
        val now = at(2026, 11, 23, 19, 0)
        val s = base.withOnce(ms(now) + 10 * 60_000, ms(now))
        assertNull(s.copy(skip = s.once).onceAt(now))
    }

    @Test fun skipMovesPastOneAlarm() {
        val skip = at(2026, 11, 24, 6, 30).toInstant().toEpochMilli()
        assertEquals("2026-11-25 06:30", fmt(base.copy(skip = skip).nextAlarm(at(2026, 11, 23, 7))))
    }

    @Test fun pauseIncludesEndDate() =
        assertEquals("2026-11-27 06:30", fmt(base.copy(pauseUntil = "2026-11-26").nextAlarm(at(2026, 11, 23, 7))))

    @Test fun perDayTimes() {
        val s = base.copy(perDay = true, dayTimes = mapOf(2 to "05:45", 3 to "07:15"))
        assertEquals("2026-11-24 05:45", fmt(s.nextAlarm(at(2026, 11, 23, 7))))
        assertEquals("2026-11-25 07:15", fmt(s.nextAlarm(at(2026, 11, 24, 6))))
    }

    @Test fun daylightSavingStartKeepsWallClock() {
        assertEquals("2026-10-04 06:30", fmt(everyDay.nextAlarm(at(2026, 10, 3, 22))))
        assertEquals("2026-10-05 06:30", fmt(everyDay.nextAlarm(at(2026, 10, 4, 7))))
    }

    @Test fun daylightSavingEndKeepsWallClock() {
        assertEquals("2026-04-05 06:30", fmt(everyDay.nextAlarm(at(2026, 4, 4, 22))))
        assertEquals("2026-04-06 06:30", fmt(everyDay.nextAlarm(at(2026, 4, 5, 7))))
    }

    @Test fun badPauseDateIsIgnored() =
        assertEquals("2026-11-24 06:30", fmt(base.copy(pauseUntil = "soon").nextAlarm(at(2026, 11, 23, 7))))

    @Test fun readsThePagesJson() {
        val j = JSONObject("""{"time":"07:05","days":[0,6],"perDay":true,"dayTimes":{"0":"08:00","9":"01:00","6":"bad"},
            "dur":20,"snooze":5,"pauseUntil":"2026-12-01","auto":false,"skip":123,"once":456,"onceStart":400,"theme":"phone"}""")
        val s = AlarmSettings.from(j)
        assertEquals("07:05", s.time); assertEquals(setOf(0, 6), s.days); assertEquals(mapOf(0 to "08:00"), s.dayTimes)
        assertEquals(20, s.dur); assertEquals(5, s.snooze); assertEquals("2026-12-01", s.pauseUntil)
        assertEquals(123L, s.skip); assertEquals(456L, s.once); assertEquals(400L, s.onceStart)
    }

    @Test fun writingKeepsOtherKeys() {
        val j = JSONObject("""{"theme":"phone","src":"radio","time":"06:30"}""")
        AlarmSettings.from(j).copy(time = "05:50", days = setOf(1, 3)).into(j)
        assertEquals("phone", j.getString("theme")); assertEquals("radio", j.getString("src"))
        assertEquals("05:50", j.getString("time")); assertEquals("[1,3]", j.getJSONArray("days").toString())
        assertEquals(false, j.has("auto"))
    }

    @Test fun badTimeFallsBackToDefault() = assertEquals("06:30", AlarmSettings.from(JSONObject("""{"time":"25:99"}""")).time)

    @Test fun nothingWhenTheTimeIsUnreadable() = assertNull(base.copy(time = "xx").nextAlarm(at(2026, 11, 23, 7)))
}
