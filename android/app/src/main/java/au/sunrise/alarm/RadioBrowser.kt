package au.sunrise.alarm

import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A station from the Radio Browser directory (radio-browser.info, free, no key). */
data class FoundStation(val name: String, val url: String, val favicon: String, val uuid: String, val info: String) {
    fun toMine() = MyStation(name, url, favicon, uuid)
}

/** Station search, as on the page's Find stations screen (rbSearch / runSearch in index.html). */
object RadioBrowser {
    private val HOSTS = listOf(
        "https://de1.api.radio-browser.info", "https://nl1.api.radio-browser.info",
        "https://at1.api.radio-browser.info", "https://all.api.radio-browser.info",
    )

    /** Quick picks: label, search, results title. */
    val QUICK: List<Triple<String, Map<String, String>, String>> = listOf(
        Triple("Victoria", mapOf("state" to "Victoria"), "Popular in Victoria"),
        Triple("Australia top", emptyMap(), "Popular in Australia"),
        Triple("News & talk", mapOf("tag" to "news"), "News & talk"),
        Triple("Classic hits", mapOf("tag" to "classic hits"), "Classic hits"),
        Triple("Country", mapOf("tag" to "country"), "Country"),
        Triple("Jazz", mapOf("tag" to "jazz"), "Jazz"),
        Triple("Chill", mapOf("tag" to "chillout"), "Chill"),
    )

    /** Search the directory. Blocking: call it off the main thread. Throws if no server answers. */
    fun search(params: Map<String, String>, australiaOnly: Boolean): List<FoundStation> {
        val all = linkedMapOf("hidebroken" to "true", "order" to "clickcount", "reverse" to "true", "limit" to "40")
        if (australiaOnly) all["countrycode"] = "AU"
        all.putAll(params)
        val query = all.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        for (host in HOSTS) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL("$host/json/stations/search?$query").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 7000
                    setRequestProperty("User-Agent", "SunriseAlarm")   // the directory asks apps to name themselves
                }
                if (conn.responseCode == 200) return parse(conn.inputStream.bufferedReader().use { it.readText() })
            } catch (_: Exception) {
                // try the next server
            } finally {
                conn?.disconnect()
            }
        }
        throw java.io.IOException("Couldn't reach the station directory")
    }

    /** Playable stations (the app plays http and https), no duplicate names, at most 25. */
    fun parse(json: String): List<FoundStation> {
        val arr = JSONArray(json)
        val seen = HashSet<String>()
        val out = ArrayList<FoundStation>()
        for (i in 0 until arr.length()) {
            val x = arr.optJSONObject(i) ?: continue
            val url = x.optString("url_resolved").ifEmpty { x.optString("url") }
            val name = x.optString("name").trim().ifEmpty { "Station" }
            if (!(url.startsWith("http://") || url.startsWith("https://"))) continue
            if (!seen.add(name.lowercase())) continue
            val codec = x.optString("codec"); val bitrate = x.optInt("bitrate", 0)
            val info = listOf(
                x.optString("state").ifEmpty { x.optString("country") },
                if (codec.isNotEmpty() && bitrate > 0) "$codec ${bitrate}k" else codec,
                x.optString("tags").split(",").map { it.trim() }.filter { it.isNotEmpty() }.take(2).joinToString(", "),
            ).filter { it.isNotEmpty() }.joinToString(" · ")
            out += FoundStation(name, url, x.optString("favicon"), x.optString("stationuuid"), info)
            if (out.size == 25) break
        }
        return out
    }
}
