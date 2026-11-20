package au.sunrise.alarm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class HolidaysTest {
    // The shape of date.nager.at's answer
    private val sample = """[
      {"date":"2026-01-26","localName":"Australia Day","name":"Australia Day","countryCode":"AU","fixed":false,"global":true,"counties":null,"launchYear":null,"types":["Public"]},
      {"date":"2026-03-09","localName":"Labour Day","name":"Labour Day","countryCode":"AU","fixed":false,"global":false,"counties":["AU-VIC"],"launchYear":null,"types":["Public"]},
      {"date":"2026-03-02","localName":"Labour Day","name":"Labour Day","countryCode":"AU","fixed":false,"global":false,"counties":["AU-WA"],"launchYear":null,"types":["Public"]},
      {"date":"2026-11-03","localName":"Melbourne Cup","name":"Melbourne Cup","countryCode":"AU","fixed":false,"global":false,"counties":["AU-VIC"],"launchYear":null,"types":["Public"]},
      {"date":"2026-04-05","localName":"Easter Sunday","name":"Easter Sunday","countryCode":"AU","fixed":false,"global":false,"counties":["AU-VIC"],"launchYear":null,"types":["Observance"]}
    ]"""

    @Test fun nationalAndStateHolidaysOnly() {
        assertEquals(mapOf("2026-01-26" to "Australia Day", "2026-03-09" to "Labour Day", "2026-11-03" to "Melbourne Cup"), Holidays.parse(sample, "VIC"))
        assertEquals(mapOf("2026-01-26" to "Australia Day", "2026-03-02" to "Labour Day"), Holidays.parse(sample, "WA"))
    }

    private val mel = ZoneId.of("Australia/Melbourne")
    private fun at(y: Int, mo: Int, d: Int, h: Int) = ZonedDateTime.of(y, mo, d, h, 0, 0, 0, mel)
    private val vic = AlarmSettings(holidayState = "VIC", holidays = mapOf("2026-11-03" to "Melbourne Cup"))

    @Test fun alarmSkipsTheHoliday() {
        // Mon 2 Nov evening: the next alarm is Wed 4 Nov, not Cup Day
        assertEquals(LocalDate.of(2026, 11, 4), vic.nextAlarm(at(2026, 11, 2, 20))!!.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 3), vic.copy(holidayState = "").nextAlarm(at(2026, 11, 2, 20))!!.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 3) to "Melbourne Cup", vic.skippedHoliday(at(2026, 11, 2, 20)))
        assertNull(vic.skippedHoliday(at(2026, 11, 4, 7)))
    }

    @Test fun homeTextSaysWhichHoliday() =
        assertEquals("Alarm Wed at 6:30 am · in 34h 30m · not Tue (Melbourne Cup)", HomeText.next(vic, at(2026, 11, 2, 20), false))

    @Test fun readsTheSavedHolidays() {
        val j = JSONObject("""{"holidayState":"VIC","holidayDates":{"2026-11-03":"Melbourne Cup"}}""")
        assertEquals(vic.holidays, AlarmSettings.from(j).holidays)
        assertEquals("", AlarmSettings.from(JSONObject("""{"holidayState":"XX"}""")).holidayState)
    }

    @Test fun upcomingList() =
        assertEquals(listOf(LocalDate.of(2026, 11, 3) to "Melbourne Cup"), Holidays.upcoming(vic, LocalDate.of(2026, 10, 1)))
}
