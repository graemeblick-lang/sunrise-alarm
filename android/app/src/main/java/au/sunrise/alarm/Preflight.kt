package au.sunrise.alarm

import android.content.Context
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * The check when the night screen starts: will tomorrow's alarm sound work? Problems are found now,
 * not at alarm time: Spotify not connected, the station not answering, the music app gone, the phone's
 * settings, the volume at zero, a flat battery.
 */
object Preflight {
    /** What was found. Null = not checked (it doesn't apply). */
    data class Found(
        val src: String,
        val spotifyInstalled: Boolean = true,
        val spotifyConnected: Boolean = true,
        /** App Remote's answer: null = fine, else the error's name ("NotLoggedInException"). */
        val spotifyError: String? = null,
        val radioOk: Boolean? = null,
        val appInstalled: Boolean = true,
        val appName: String = "",
        val phoneProblems: List<String> = emptyList(),
        val alarmVolume: Int = 1,
        val mediaVolume: Int = 1,
        val battery: Int = 100,
        val charging: Boolean = true,
    )

    /** One problem per line, in plain words, worst first. Empty = all good. */
    fun messages(f: Found): List<String> {
        val out = ArrayList<String>()
        when (f.src) {
            "spotify" -> when {
                !f.spotifyInstalled -> out += "Spotify isn't installed, so you'd wake to chimes."
                f.spotifyError == "NotLoggedInException" -> out += "Spotify is logged out, so you'd wake to chimes. Open Spotify and log in."
                f.spotifyError == "OfflineModeException" -> out += "Spotify is in offline mode, so your music may not start."
                f.spotifyError != null -> out += "Spotify didn't answer (${f.spotifyError}), so you may wake to chimes."
                !f.spotifyConnected -> out += "Spotify isn't connected, so it will play what you last played (or chimes)."
            }
            "radio" -> if (f.radioOk == false) out += "The radio station isn't answering, so you may wake to chimes. Check the Wi-Fi."
            "apps" -> if (!f.appInstalled) out += "${f.appName.ifEmpty { "Your music app" }} isn't installed, so you'd wake to chimes."
        }
        f.phoneProblems.forEach { out += PhoneChecks.describe(it) }
        if (f.alarmVolume == 0) out += "The phone's alarm volume is at zero."
        if (f.src != "chimes" && f.src != "radio" && f.mediaVolume == 0) out += "The phone's media volume is at zero."
        if (!f.charging && f.battery < 30) out += "The battery is at ${f.battery}% and not charging. Plug the phone in."
        return out
    }

    /** Check everything (in the background; up to about 15 seconds) and report on the main thread. */
    fun run(c: Context, s: SoundSettings, spotifyClientId: String, done: (List<String>) -> Unit) {
        val app = c.applicationContext
        val main = Handler(Looper.getMainLooper())
        Thread {
            val f = runCatching { gather(app, s, spotifyClientId) }.getOrNull()
            main.post { done(f?.let { messages(it) } ?: emptyList()) }
        }.start()
    }

    private fun gather(c: Context, s: SoundSettings, clientId: String): Found {
        val pm = c.packageManager
        val am = c.getSystemService(AudioManager::class.java)
        val bm = c.getSystemService(BatteryManager::class.java)
        var f = Found(
            src = s.src,
            phoneProblems = runCatching { PhoneChecks.problems(c) }.getOrDefault(emptyList()),
            alarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM),
            mediaVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY),
            charging = Evening.charging(c),
        )
        when (s.src) {
            "spotify" -> {
                val installed = pm.getLaunchIntentForPackage("com.spotify.music") != null
                f = f.copy(spotifyInstalled = installed, spotifyConnected = s.spAppOk)
                if (installed && s.spAppOk && clientId.isNotEmpty()) f = f.copy(spotifyError = spotifyAnswer(c, clientId))
            }
            "radio" -> f = f.copy(radioOk = s.stationUrls().take(3).any { reachable(it) })
            "apps" -> f = f.copy(appInstalled = s.appPkg.isNotEmpty() && pm.getLaunchIntentForPackage(s.appPkg) != null, appName = s.appName)
        }
        return f
    }

    /** Connect to Spotify without playing anything: null if it answers, else the error's name. */
    private fun spotifyAnswer(c: Context, clientId: String): String? {
        val lock = Object()
        var answer: String? = "NoAnswer"
        var got = false
        val remote = SpotifyRemote { ev ->
            synchronized(lock) {
                if (!got) { got = true; answer = if (ev == "sr:connected") null else ev.removePrefix("sr:error:"); lock.notifyAll() }
            }
        }
        Handler(Looper.getMainLooper()).post { remote.connect(c, clientId, false, null, false) }
        synchronized(lock) { if (!got) lock.wait(12_000) }
        Handler(Looper.getMainLooper()).post { remote.disconnect() }
        return answer
    }

    /** Does the stream answer at all (a few bytes within 8 seconds)? */
    private fun reachable(url: String): Boolean = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000; conn.readTimeout = 8_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Icy-MetaData", "0")
            conn.responseCode in 200..399 && conn.inputStream.use { it.read() >= 0 }
        } finally { conn.disconnect() }
    }.getOrDefault(false)
}
