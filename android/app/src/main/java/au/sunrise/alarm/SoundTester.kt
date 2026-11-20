package au.sunrise.alarm

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * "Test sound" on the native Wake-up sound screen: plays the chosen source for 15 seconds at your
 * maximum volume, then stops and puts the phone's media volume back. [onStatus] says what's happening.
 */
class SoundTester(context: Context, private val onStatus: (String?) -> Unit) {
    private val ctx = context.applicationContext
    private val audio = NativeAudio(ctx)
    private val media = MediaApps(ctx, audio)
    private val chimes = ChimePlayer()
    private val main = Handler(Looper.getMainLooper())
    private var spotify: SpotifyRemote? = null
    private var appPkg: String? = null
    private var usingService = false
    private var previousListener: PlaybackService.Listener? = null

    var playing = false
        private set

    fun start(s: SoundSettings, spotifyClientId: String) {
        stop()
        playing = true
        val v = s.vol / 100f
        when (s.src) {
            "radio" -> {
                val urls = s.stationUrls()
                val name = s.stationNames().getOrNull(s.station)?.removeSuffix("…") ?: "Radio"
                if (urls.isEmpty()) return fail("No stream link set")
                listen { ev ->
                    when (ev) {
                        "playing" -> onStatus("📻 $name")
                        "error", "ended" -> fail("$name isn't playing right now")
                    }
                }
                onStatus("📻 Tuning in to $name…")
                PlaybackService.play(ctx, urls[0], name, v)
            }
            "spotify" -> {
                val link = SoundSettings.parseSpotify(s.spLink)
                if (s.spAppOk && link != null && spotifyClientId.isNotEmpty()) {
                    onStatus("Starting Spotify…")
                    audio.musicFadeStart(); audio.setMusic(v)
                    spotify = SpotifyRemote { ev ->
                        main.post {
                            if (!playing) return@post
                            if (ev == "sr:playing") onStatus("🎵 ${s.spName.ifEmpty { "Spotify" }}")
                            else if (ev.startsWith("sr:error")) startApp("com.spotify.music", "Spotify", v)   // what you last played
                        }
                    }.also { it.connect(ctx, spotifyClientId, false, link.first, s.spShuffle) }
                } else startApp("com.spotify.music", "Spotify", v)
            }
            "apps" -> {
                if (s.appPkg.isEmpty()) return fail("Choose a music app first")
                startApp(s.appPkg, s.appName.ifEmpty { "the app" }, v)
            }
            else -> {
                chimes.start(v)
                onStatus("🔔 Chimes")
            }
        }
        main.postDelayed({ stop() }, 15_000)
    }

    private fun startApp(pkg: String, name: String, v: Float) {
        appPkg = pkg
        if (!media.start(pkg)) return fail("$name isn't installed")
        audio.setMusic(v)
        onStatus("Starting $name…")
        // Check it really started (some apps don't let other apps start them)
        main.postDelayed({
            if (!playing || appPkg != pkg) return@postDelayed
            val am = ctx.getSystemService(AudioManager::class.java)
            if (media.state(pkg) == "playing" || am.isMusicActive) onStatus("🎵 $name")
            else { media.retry(pkg); main.postDelayed({
                if (playing && appPkg == pkg && !(media.state(pkg) == "playing" || am.isMusicActive))
                    fail("$name didn't start (some apps don't let other apps start them)")
            }, 4_000) }
        }, 4_000)
    }

    private fun listen(handler: (String) -> Unit) {
        previousListener = PlaybackService.listener
        usingService = true
        PlaybackService.listener = PlaybackService.Listener { ev -> main.post { if (playing) handler(ev) } }
    }

    private fun fail(why: String) {
        stop()
        onStatus(why)
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        val was = playing
        playing = false
        chimes.stop()
        if (usingService) {
            PlaybackService.stop(ctx)
            PlaybackService.listener = previousListener
            usingService = false
        }
        spotify?.let { runCatching { it.pause() }; runCatching { it.disconnect() } }
        spotify = null
        appPkg?.let { media.pause(it) }
        appPkg = null
        audio.musicRestore()
        if (was) onStatus(null)
    }
}
