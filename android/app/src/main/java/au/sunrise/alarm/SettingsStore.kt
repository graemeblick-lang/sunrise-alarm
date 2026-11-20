package au.sunrise.alarm

import android.content.Context
import org.json.JSONObject

/**
 * The app's settings, shared by the native settings screens and the web page (night, alarm and morning
 * screens). It's the same JSON the page has always used (its `S` object), now kept on the Android side:
 *
 *  - The page sends its whole settings object here every time it saves.
 *  - Native screens change only the keys they own, then the page reloads when it comes back to the front.
 *
 * So both sides always see the same settings, and nothing saved before is lost.
 */
object SettingsStore {
    private const val PREFS = "sunrise-settings"
    private const val KEY = "json"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun has(c: Context): Boolean = prefs(c).contains(KEY)

    @JvmStatic
    fun read(c: Context): JSONObject =
        runCatching { JSONObject(prefs(c).getString(KEY, null) ?: "{}") }.getOrElse { JSONObject() }

    /** The page's whole settings object. Ignored unless it's a valid JSON object. */
    @JvmStatic
    fun write(c: Context, json: String?) {
        if (json == null || json.length > 200_000) return
        val parsed = runCatching { JSONObject(json) }.getOrNull() ?: return
        prefs(c).edit().putString(KEY, parsed.toString()).apply()
    }

    /** Change some keys, keeping everything else as the page left it. */
    fun update(c: Context, change: (JSONObject) -> Unit) {
        val j = read(c)
        change(j)
        prefs(c).edit().putString(KEY, j.toString()).apply()
    }
}
