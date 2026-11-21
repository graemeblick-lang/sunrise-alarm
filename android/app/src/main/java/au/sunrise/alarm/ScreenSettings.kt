package au.sunrise.alarm

import org.json.JSONArray
import org.json.JSONObject

/** "Look & brightness" settings (keys and ranges as in the page). */
data class LookSettings(
    val theme: String = "sunrise",
    val sunCustom: Boolean = false,
    val sunCols: List<String> = listOf("#3a0d02", "#ff7a26", "#fff1d6"),
    /** Overnight clock brightness, 1..6. */
    val dim: Int = 2,
    /** How bright the screen gets by alarm time, 15..100 %. */
    val sunMax: Int = 60,
    /** The night clock's size: 0 normal, 1 large, 2 huge. */
    val clockSize: Int = 0,
    /** A bolder clock (easier to read without glasses). */
    val clockBold: Boolean = false,
    /** Show the date under the night clock. */
    val nightDate: Boolean = false,
) {
    fun into(j: JSONObject) {
        j.put("theme", theme)
        j.put("sunCustom", sunCustom)
        j.put("sunCols", JSONArray(sunCols))
        j.put("dim", dim)
        j.put("sunMax", sunMax)
        j.put("clockSize", clockSize)
        j.put("clockBold", clockBold)
        j.put("nightDate", nightDate)
    }

    companion object {
        fun from(j: JSONObject): LookSettings {
            val d = LookSettings()
            val cols = j.optJSONArray("sunCols")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                ?.takeIf { c -> c.size == 3 && c.all { Themes.parse(it) != null } } ?: d.sunCols
            return LookSettings(
                theme = j.optString("theme", d.theme).ifEmpty { d.theme },
                sunCustom = j.optBoolean("sunCustom", d.sunCustom),
                sunCols = cols,
                dim = j.optInt("dim", d.dim).coerceIn(1, 6),
                sunMax = j.optInt("sunMax", d.sunMax).coerceIn(15, 100),
                clockSize = j.optInt("clockSize", 0).coerceIn(0, 2),
                clockBold = j.optBoolean("clockBold", false),
                nightDate = j.optBoolean("nightDate", false),
            )
        }
    }
}

/** Morning settings: where the weather is for, the optional Todoist token and the calendar. */
data class MorningSettings(
    val lat: Double = -37.814,
    val lon: Double = 144.963,
    val place: String = "Melbourne",
    val todoToken: String = "",
    /** Show today's events from the phone's calendar. */
    val calendar: Boolean = false,
) {
    fun into(j: JSONObject) {
        j.put("lat", lat)
        j.put("lon", lon)
        j.put("place", place)
        j.remove("weatherLink"); j.remove("willy")   // no longer used
        j.put("todoToken", todoToken)
        j.put("calendar", calendar)
    }

    companion object {
        fun from(j: JSONObject): MorningSettings {
            val d = MorningSettings()
            return MorningSettings(
                lat = j.optDouble("lat", d.lat).takeIf { !it.isNaN() } ?: d.lat,
                lon = j.optDouble("lon", d.lon).takeIf { !it.isNaN() } ?: d.lon,
                place = j.optString("place", d.place).ifEmpty { d.place },
                todoToken = j.optString("todoToken", ""),
                calendar = j.optBoolean("calendar", false),
            )
        }

        /** The BOM Weather app, opened from the morning screen (bom.gov.au if it isn't installed). */
        const val BOM_APP = "au.gov.bom.metview"
        const val BOM_SITE = "https://www.bom.gov.au/"

        /** Rounded to 3 decimals (about 100 m), as the page does: no more precise than the weather needs. */
        fun round3(v: Double): Double = Math.round(v * 1000.0) / 1000.0
    }
}

/** Night settings beyond the look: starting the night screen by itself, the sleep timer and the bedtime reminder. */
data class NightSettings(
    /** On the charger after this hour (20..23), the night screen starts by itself. 0 = off. */
    val autoNight: Int = 0,
    /** Music started from the night screen fades out after this many minutes. 0 = keeps playing. */
    val sleepTimer: Int = 30,
    /** A reminder this many hours before the next alarm. 0 = off. */
    val bedtime: Int = 0,
    /** The night screen's sleep sound: "rain", "ocean" or "noise". */
    val sleepSound: String = "rain",
) {
    fun into(j: JSONObject) {
        j.put("autoNight", autoNight)
        j.put("sleepTimer", sleepTimer)
        j.put("bedtime", bedtime)
        j.put("sleepSound", sleepSound)
    }

    companion object {
        val AUTO_NIGHT_HOURS = listOf(0, 20, 21, 22, 23)
        val SLEEP_TIMERS = listOf(0, 15, 30, 60)
        val BEDTIME_HOURS = listOf(0, 7, 8, 9)

        fun from(j: JSONObject): NightSettings {
            val d = NightSettings()
            return NightSettings(
                autoNight = j.optInt("autoNight", d.autoNight).takeIf { it in AUTO_NIGHT_HOURS } ?: 0,
                sleepTimer = j.optInt("sleepTimer", d.sleepTimer).takeIf { it in SLEEP_TIMERS } ?: d.sleepTimer,
                bedtime = j.optInt("bedtime", d.bedtime).takeIf { it in BEDTIME_HOURS } ?: 0,
                sleepSound = j.optString("sleepSound", d.sleepSound).takeIf { it in NoisePlayer.KINDS } ?: d.sleepSound,
            )
        }
    }
}
