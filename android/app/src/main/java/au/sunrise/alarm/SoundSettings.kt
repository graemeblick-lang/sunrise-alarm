package au.sunrise.alarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * The built-in stations, in the page's order.
 * Each has links to try in order: the ABC's current HLS streams first, older MP3 links as backup.
 */
object Stations {
    private const val AK = "https://mediaserviceslive.akamaized.net/hls/live/"
    private const val MH = "https://live-radio01.mediahubaustralia.com/"

    val BUILT_IN: List<Pair<String, List<String>>> = listOf(
        "ABC Melbourne" to listOf(AK + "2038300/localmelbourne/index.m3u8", AK + "2038300/localmelbourne/masterhq.m3u8", MH + "3LRW/mp3/"),
        "triple j" to listOf(AK + "2038308/triplejnsw/index.m3u8", AK + "2038308/triplejnsw/masterhq.m3u8", MH + "2TJW/mp3/"),
        "Double J" to listOf(AK + "2038315/doublejnsw/index.m3u8", AK + "2038315/doublejnsw/masterhq.m3u8", MH + "DJDW/mp3/"),
        "ABC Classic" to listOf(AK + "2038316/classicfmnsw/index.m3u8", AK + "2038316/classicfmnsw/masterhq.m3u8", MH + "2FMW/mp3/"),
        "triple j Hottest" to listOf(AK + "2100183/triplejhottest/index.m3u8"),
        "Radio National" to listOf(AK + "2038327/rnvic/index.m3u8", MH + "3RNW/mp3/"),
    )
}

data class MyStation(val name: String, val url: String, val favicon: String = "", val uuid: String = "")

/** "Wake-up sound" settings (same keys as the page). */
data class SoundSettings(
    /** chimes | radio | spotify | apps */
    val src: String = "chimes",
    /** Index into built-in stations, then your stations, then "Custom stream". */
    val station: Int = 0,
    val customUrl: String = "",
    val myStations: List<MyStation> = emptyList(),
    val vol: Int = 70,
    val fade: Int = 3,
    val earlyMin: Int = 0,
    val earlyVol: Int = 25,
    val appPkg: String = "",
    val appName: String = "",
    val spLink: String = "",
    val spName: String = "",
    val spShuffle: Boolean = true,
    val spAppOk: Boolean = false,
    /** A Client ID saved by an older version (read only; the built-in one is preferred). */
    val spClient: String = "",
    /** A different source at weekends ("" = same as weekdays). */
    val weekendSrc: String = "",
) {
    /** Station names in the page's order, with "Custom stream…" last. */
    /** On Saturdays and Sundays, wake to this instead ("" = the same as weekdays). [day]: 0 = Sunday … 6. */
    fun forDay(day: Int): SoundSettings = if (weekendSrc in SOURCES && (day == 0 || day == 6)) copy(src = weekendSrc) else this

    fun stationNames(): List<String> = Stations.BUILT_IN.map { it.first } + myStations.map { it.name } + "Custom stream…"
    val customIndex: Int get() = Stations.BUILT_IN.size + myStations.size

    /** Links to try for the chosen station (empty if there's nothing to play). */
    fun stationUrls(): List<String> = when {
        station in Stations.BUILT_IN.indices -> Stations.BUILT_IN[station].second
        station - Stations.BUILT_IN.size in myStations.indices -> listOf(myStations[station - Stations.BUILT_IN.size].url)
        else -> listOfNotNull(customUrl.takeIf { it.startsWith("http://") || it.startsWith("https://") })
    }

    fun into(j: JSONObject) {
        j.put("src", src)
        j.put("station", station)
        j.put("customUrl", customUrl)
        j.put("myStations", JSONArray(myStations.map {
            JSONObject().put("name", it.name).put("url", it.url).put("favicon", it.favicon).put("uuid", it.uuid)
        }))
        j.remove("songs")   // "Your songs" was removed
        j.put("vol", vol)
        j.put("fade", fade)
        j.put("earlyMin", earlyMin)
        j.put("earlyVol", earlyVol)
        j.put("appPkg", appPkg)
        j.put("appName", appName)
        j.put("spLink", spLink)
        j.put("spName", spName)
        j.put("spShuffle", spShuffle)
        j.put("spAppOk", spAppOk)
        j.put("weekendSrc", weekendSrc)
    }

    companion object {
        val SOURCES = listOf("chimes", "radio", "spotify", "apps")
        val FADES = listOf(1, 3, 5)
        val EARLY = listOf(0, 5, 10, 15)

        fun from(j: JSONObject): SoundSettings {
            val d = SoundSettings()
            val mine = j.optJSONArray("myStations")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    a.optJSONObject(i)?.let { o ->
                        val url = o.optString("url")
                        if (url.isEmpty()) null else MyStation(o.optString("name", "Station"), url, o.optString("favicon"), o.optString("uuid"))
                    }
                }
            } ?: emptyList()
            val s = SoundSettings(
                src = j.optString("src", d.src).takeIf { it in SOURCES } ?: d.src,
                station = j.optInt("station", 0),
                customUrl = j.optString("customUrl", ""),
                myStations = mine,
                vol = j.optInt("vol", d.vol).coerceIn(10, 100),
                fade = j.optInt("fade", d.fade),
                earlyMin = j.optInt("earlyMin", d.earlyMin),
                earlyVol = j.optInt("earlyVol", d.earlyVol).coerceIn(5, 100),
                appPkg = j.optString("appPkg", ""),
                appName = j.optString("appName", ""),
                spLink = j.optString("spLink", ""),
                spName = j.optString("spName", ""),
                spShuffle = j.optBoolean("spShuffle", d.spShuffle),
                weekendSrc = j.optString("weekendSrc", "").takeIf { it in SOURCES } ?: "",
                spAppOk = j.optBoolean("spAppOk", d.spAppOk),
                spClient = j.optString("spClient", ""),
            )
            // A station that no longer exists (e.g. removed) goes back to the first, as the page does
            return if (s.station !in 0..s.customIndex) s.copy(station = 0) else s
        }

        /** A Spotify playlist/album/artist/song link or spotify: URI as (uri, kind), like the page's parseLink. */
        fun parseSpotify(text: String?): Pair<String, String>? {
            val m = Regex("(playlist|album|artist|track|show|episode)[/:]([A-Za-z0-9]{10,})").find(text ?: "") ?: return null
            val (type, id) = m.destructured
            return "spotify:$type:$id" to (if (type == "track") "song" else type)
        }
    }

    /** Remove one of your stations, keeping the chosen station pointing at the same one (as the page does). */
    fun removeStation(i: Int): SoundSettings {
        val idx = Stations.BUILT_IN.size + i
        val newStation = when {
            station == idx -> 0
            station > idx -> station - 1
            else -> station
        }
        return copy(myStations = myStations.filterIndexed { j, _ -> j != i }, station = newStation)
    }

    /** Add a station from search and choose it. */
    fun addStation(st: MyStation): SoundSettings {
        if (myStations.any { it.url == st.url }) return this
        val list = myStations + st
        return copy(myStations = list, station = Stations.BUILT_IN.size + list.size - 1, src = "radio")
    }
}
