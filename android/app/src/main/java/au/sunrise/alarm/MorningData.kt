package au.sunrise.alarm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** Sunrise and sunset for a place and day (the page's sunTimes()). Epoch ms, or null near the poles. */
object SunTimes {
    fun of(date: LocalDate, lat: Double, lon: Double): Pair<Long, Long>? {
        val rad = PI / 180
        val doy = date.dayOfYear
        val g = 2 * PI / 365 * (doy - 1)
        val eq = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val dec = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) + 0.000907 * sin(2 * g) -
            0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val ha = acos(cos(90.833 * rad) / (cos(lat * rad) * cos(dec)) - tan(lat * rad) * tan(dec)) / rad
        if (ha.isNaN()) return null
        val base = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val rise = base + ((720 - 4 * (lon + ha) - eq) * 60_000).toLong()
        val set = base + ((720 - 4 * (lon - ha) - eq) * 60_000).toLong()
        return rise to set
    }
}

/** Today's weather from Open-Meteo (free, no key), as on the page's morning screen. */
data class Weather(
    val emoji: String, val now: String, val temp: Int, val feels: Int,
    val todayText: String, val high: Int, val low: Int, val rainChance: Int, val rainMm: Double, val wind: Int, val uv: Int,
    /** For the night screen's "Tomorrow" line. */
    val dayEmoji: String,
)

object WeatherData {
    private val WX = mapOf(
        0 to ("☀️" to "Clear"), 1 to ("🌤️" to "Mostly clear"), 2 to ("⛅" to "Partly cloudy"), 3 to ("☁️" to "Cloudy"),
        45 to ("🌫️" to "Fog"), 48 to ("🌫️" to "Fog"),
        51 to ("🌦️" to "Light drizzle"), 53 to ("🌦️" to "Drizzle"), 55 to ("🌧️" to "Heavy drizzle"), 56 to ("🌧️" to "Freezing drizzle"), 57 to ("🌧️" to "Freezing drizzle"),
        61 to ("🌦️" to "Light rain"), 63 to ("🌧️" to "Rain"), 65 to ("🌧️" to "Heavy rain"), 66 to ("🌧️" to "Freezing rain"), 67 to ("🌧️" to "Freezing rain"),
        71 to ("🌨️" to "Light snow"), 73 to ("🌨️" to "Snow"), 75 to ("❄️" to "Heavy snow"), 77 to ("🌨️" to "Snow grains"),
        80 to ("🌦️" to "Showers"), 81 to ("🌧️" to "Showers"), 82 to ("⛈️" to "Heavy showers"), 85 to ("🌨️" to "Snow showers"), 86 to ("🌨️" to "Snow showers"),
        95 to ("⛈️" to "Thunderstorms"), 96 to ("⛈️" to "Storms, hail"), 99 to ("⛈️" to "Storms, hail"),
    )
    fun describe(code: Int): Pair<String, String> = WX[code] ?: ("🌡️" to "—")

    fun url(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
        "&current=temperature_2m,apparent_temperature,weather_code,wind_speed_10m" +
        "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,wind_speed_10m_max,uv_index_max" +
        "&timezone=auto&forecast_days=2"

    /** Turn Open-Meteo's answer into today's weather (or tomorrow's, after the day has rolled over in the data). */
    fun parse(json: String, today: LocalDate): Weather {
        val d = JSONObject(json)
        val c = d.getJSONObject("current")
        val daily = d.getJSONObject("daily")
        val days = daily.getJSONArray("time")
        var i = (0 until days.length()).firstOrNull { days.getString(it) == today.toString() } ?: 0
        if (i < 0) i = 0
        fun num(key: String) = daily.getJSONArray(key).optDouble(i, 0.0).let { if (it.isNaN()) 0.0 else it }
        val (em, txt) = describe(c.optInt("weather_code"))
        val (dem, dtxt) = describe(daily.getJSONArray("weather_code").optInt(i))
        return Weather(
            emoji = em, now = txt,
            temp = c.optDouble("temperature_2m").roundToInt(), feels = c.optDouble("apparent_temperature").roundToInt(),
            todayText = dtxt.lowercase(), high = num("temperature_2m_max").roundToInt(), low = num("temperature_2m_min").roundToInt(),
            rainChance = num("precipitation_probability_max").roundToInt(), rainMm = num("precipitation_sum"),
            wind = num("wind_speed_10m_max").roundToInt(), uv = num("uv_index_max").roundToInt(), dayEmoji = dem,
        )
    }

    private const val PREFS = "weather"

    /**
     * The weather for a place: from a 30-minute cache unless [force], otherwise fetched (blocking: call off the
     * main thread). Falls back to the last fetched copy when there's no internet. Null if there's never been one.
     */
    fun get(c: Context, lat: Double, lon: Double, force: Boolean): String? {
        val p = c.getSharedPreferences(PREFS, 0)
        val cached = p.getString("json", null)
        val sameSpot = p.getString("where", null) == "$lat,$lon"
        if (!force && cached != null && sameSpot && System.currentTimeMillis() - p.getLong("at", 0) < 30 * 60_000) return cached
        val fresh = runCatching { Http.get(url(lat, lon)) }.getOrNull()
        if (fresh != null) {
            p.edit().putString("json", fresh).putString("where", "$lat,$lon").putLong("at", System.currentTimeMillis()).apply()
            return fresh
        }
        return if (sameSpot) cached else null
    }
}

/** Today's and overdue Todoist tasks, most urgent first. */
data class TodoTask(val content: String, val priority: Int, val overdue: Boolean)

object Todoist {
    sealed class Result {
        data class Tasks(val list: List<TodoTask>) : Result()
        object BadToken : Result()
        object Unreachable : Result()
    }

    /** Blocking: call off the main thread. Tries Todoist's current API, then the older one (as the page does). */
    fun load(token: String, today: LocalDate): Result {
        val q = URLEncoder.encode("today | overdue", "UTF-8")
        val headers = mapOf("Authorization" to "Bearer $token")
        try {
            val (code, body) = Http.request("https://api.todoist.com/api/v1/tasks/filter?query=$q&limit=20", headers)
            if (code == 401 || code == 403) return Result.BadToken
            if (code == 200) return Result.Tasks(parse(body, today))
        } catch (_: Exception) { }
        try {
            val (code, body) = Http.request("https://api.todoist.com/rest/v2/tasks?filter=$q", headers)
            if (code == 200) return Result.Tasks(parse(body, today))
        } catch (_: Exception) { }
        return Result.Unreachable
    }

    /** Todoist's answer (a list, or {results: [...]}) as tasks, most urgent first (API priority 4 = p1). */
    fun parse(json: String, today: LocalDate): List<TodoTask> {
        val t = json.trim()
        val arr = if (t.startsWith("[")) JSONArray(t) else JSONObject(t).let { it.optJSONArray("results") ?: it.optJSONArray("items") ?: JSONArray() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map { o ->
            val due = o.optJSONObject("due")?.optString("date")?.take(10) ?: ""
            TodoTask(o.optString("content"), o.optInt("priority", 1), due.isNotEmpty() && due < today.toString())
        }.sortedByDescending { it.priority }
    }
}

/** Small HTTP helper with timeouts, so a bad connection can't leave a screen hanging. */
object Http {
    fun request(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 10_000): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = timeoutMs; conn.readTimeout = timeoutMs
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            return code to body
        } finally {
            conn.disconnect()
        }
    }

    fun get(url: String): String {
        val (code, body) = request(url)
        if (code != 200) throw java.io.IOException("HTTP $code")
        return body
    }
}
