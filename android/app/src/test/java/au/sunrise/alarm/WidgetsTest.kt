package au.sunrise.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WidgetsTest {
    private val mel = ZoneId.of("Australia/Melbourne")
    private fun at(d: Int, h: Int, mi: Int = 0) = ZonedDateTime.of(2026, 11, d, h, mi, 0, 0, mel)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()

    @Test fun nextWeekdayAlarm() =
        assertEquals(Widgets.Texts("6:30 am", "Tomorrow", "Skip next"), Widgets.texts(AlarmSettings(), at(23, 20), false))

    @Test fun skippedShowsUndo() {
        val s = AlarmSettings(skip = ms(at(24, 6, 30)))
        assertEquals(Widgets.Texts("06:30", "Wednesday · one skipped", "Undo skip"), Widgets.texts(s, at(23, 20), true))
    }

    @Test fun oneOffComesFirst() {
        val now = at(23, 20)
        val s = AlarmSettings().withOnce(ms(now) + 10 * 60_000, ms(now))
        assertEquals(Widgets.Texts("8:10 pm", "One-off · Today", "Skip next"), Widgets.texts(s, now, false))
    }

    @Test fun noAlarm() =
        assertEquals(Widgets.Texts("––:––", "No alarm set", ""), Widgets.texts(AlarmSettings(days = emptySet()), at(23, 20), false))
}
