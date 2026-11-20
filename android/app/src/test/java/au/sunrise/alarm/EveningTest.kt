package au.sunrise.alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class EveningTest {
    private val mel = ZoneId.of("Australia/Melbourne")
    private fun at(d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, mel)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    private val alarm = ms(at(24, 6, 30))
    private val sunrise = alarm - 30 * 60_000

    @Test fun nothingWithoutAnAlarmOrSettings() {
        assertEquals(Evening.Plan(0, 0, 0), Evening.plan(at(23, 19), 0, 0, NightSettings(autoNight = 21, bedtime = 8), 0, 0))
        assertEquals(Evening.Plan(0, 0, 0), Evening.plan(at(23, 19), alarm, sunrise, NightSettings(), 0, 0))
    }

    @Test fun bedtimeReminderHoursBeforeTheAlarm() {
        val p = Evening.plan(at(23, 19), alarm, sunrise, NightSettings(bedtime = 8), 0, 0)
        assertEquals(ms(at(23, 22, 30)), p.bedtimeAt)
        assertEquals(0L, Evening.plan(at(23, 23), alarm, sunrise, NightSettings(bedtime = 8), 0, 0).bedtimeAt)   // already past
        assertEquals(0L, Evening.plan(at(23, 19), alarm, sunrise, NightSettings(bedtime = 8), alarm, 0).bedtimeAt) // already shown
    }

    @Test fun autoNightWindowFromTheHourToJustBeforeSunrise() {
        val p = Evening.plan(at(23, 19), alarm, sunrise, NightSettings(autoNight = 21), 0, 0)
        assertEquals(ms(at(23, 21)), p.checkFrom)
        assertEquals(sunrise - 5 * 60_000, p.windowEnd)
        // Later in the evening: check from now
        assertEquals(ms(at(23, 22, 15)), Evening.plan(at(23, 22, 15), alarm, sunrise, NightSettings(autoNight = 21), 0, 0).checkFrom)
        // After midnight, still the same night
        assertEquals(ms(at(24, 1)), Evening.plan(at(24, 1), alarm, sunrise, NightSettings(autoNight = 21), 0, 0).checkFrom)
        // Once a night
        assertEquals(0L, Evening.plan(at(23, 22), alarm, sunrise, NightSettings(autoNight = 21), 0, alarm).checkFrom)
    }

    @Test fun noAutoNightForAnEveningAlarm() {
        val evening = ms(at(24, 19))
        assertEquals(0L, Evening.plan(at(24, 8), evening, evening - 30 * 60_000, NightSettings(autoNight = 21), 0, 0).checkFrom)
    }

    @Test fun settingsReadAndCheck() {
        assertEquals(NightSettings(autoNight = 0, sleepTimer = 30, bedtime = 0), NightSettings.from(JSONObject("""{"autoNight":19,"sleepTimer":7,"bedtime":12}""")))
        val j = JSONObject(); NightSettings(22, 60, 8).into(j)
        assertEquals(NightSettings(22, 60, 8), NightSettings.from(j))
    }
}
