package au.sunrise.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZonedDateTime

/**
 * Australian public holidays for your state, from Nager.Date (date.nager.at: free, no key), so repeating
 * alarms don't ring on them. Fetched at most once a week for this year and next, and kept in the settings
 * ("holidayDates"), so the alarm never waits on the internet.
 */
object Holidays {
    val STATES = listOf("ACT", "NSW", "NT", "QLD", "SA", "TAS", "VIC", "WA")
    private const val PREFS = "holidays"

    fun url(year: Int) = "https://date.nager.at/api/v3/PublicHolidays/$year/AU"

    /** The state's public holidays (national ones, and those for "AU-<state>"), ISO date → name. */
    fun parse(json: String, state: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val a = JSONArray(json)
        for (i in 0 until a.length()) {
            val h = a.optJSONObject(i) ?: continue
            val types = h.optJSONArray("types")
            if (types != null && (0 until types.length()).none { types.optString(it) == "Public" }) continue
            val counties = h.optJSONArray("counties")
            val forState = h.optBoolean("global", false) || counties == null ||
                (0 until counties.length()).any { counties.optString(it) == "AU-$state" }
            val date = h.optString("date")
            if (forState && runCatching { LocalDate.parse(date) }.isSuccess) out[date] = h.optString("localName", h.optString("name"))
        }
        return out
    }

    /** Fetch the holidays if they're missing or a week old, then reschedule. Runs in the background. */
    fun refresh(c: Context, force: Boolean = false) {
        val app = c.applicationContext
        Thread {
            runCatching {
                val state = SettingsStore.read(app).optString("holidayState", "")
                if (state !in STATES) return@Thread
                val p = app.getSharedPreferences(PREFS, 0)
                val fresh = p.getString("state", "") == state && System.currentTimeMillis() - p.getLong("at", 0) < 7 * 86_400_000L
                if (fresh && !force) return@Thread
                val year = LocalDate.now().year
                val all = parse(Http.get(url(year)), state) + runCatching { parse(Http.get(url(year + 1)), state) }.getOrDefault(emptyMap())
                SettingsStore.update(app) { j -> j.put("holidayDates", JSONObject(all)) }
                p.edit().putString("state", state).putLong("at", System.currentTimeMillis()).apply()
                Alarms.refresh(app)
            }.onFailure { /* no internet: keep what we have and try again next time */ }
        }.start()
    }

    /** The next few holidays, for the Schedule screen: "Mon 26 Jan · Australia Day". */
    fun upcoming(s: AlarmSettings, today: LocalDate, count: Int = 3): List<Pair<LocalDate, String>> =
        s.holidays.mapNotNull { (d, n) -> runCatching { LocalDate.parse(d) }.getOrNull()?.let { it to n } }
            .filter { !it.first.isBefore(today) }.sortedBy { it.first }.take(count)
}

/** Keeps Android's scheduled alarms in step with the settings. */
object Alarms {
    @JvmStatic
    fun refresh(c: Context) {
        runCatching {
            AlarmScheduler.setPlan(c, AlarmSettings.from(SettingsStore.read(c)).planJson(ZonedDateTime.now()))
        }.onFailure { App.save(c, "Scheduling alarms", it) }
    }
}
