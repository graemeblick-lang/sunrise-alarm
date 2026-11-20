package au.sunrise.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import kotlin.math.max
import kotlin.math.min

/**
 * The wake-up sound (the page's sound engine, startSound/rampVolume/applyVol and friends, made native).
 *
 * Fades the chosen source in over the fade time, up to your maximum volume. Music started before the alarm
 * stops at its own lower volume until alarm time. If the radio, Spotify or a music app won't play, the chimes
 * play instead (on the morning screen, the music just stops). The phone's volume buttons take over from the fade.
 */
class WakeSound(context: Context, private val mode: () -> String, private val events: Events) {
    interface Events {
        /** What's playing, for the screen ("📻 triple j", "Spotify didn't start, so playing chimes"). */
        fun status(text: String)
        /** Stop was pressed in the notification, on the lock screen or on headphones. */
        fun userStopped()
    }

    private val ctx = context.applicationContext
    private val audio = NativeAudio(ctx)
    private val media = MediaApps(ctx, audio)
    private val chimes = ChimePlayer()
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private var s = SoundSettings()
    private var clientId = ""

    /** "chimes", "radio", "spotify", "app", or null when quiet. */
    var using: String? = null
        private set
    /** Music started before the alarm (only fades to the "volume before the alarm"). */
    var earlyPlay = false
    var curVol = 0f
        private set
    private var ringStart = 0L
    private var userVol = false

    /** Changes whenever a new sound starts, so late answers from an old attempt are ignored. */
    private var attempt = 0
    private var radioOn = false
    private var spNativeFade = false
    private var simplePkg = "com.spotify.music"
    private var spotify: SpotifyRemote? = null
    private var spotifyPlaying = false
    private var focusReq: AudioFocusRequest? = null
    private var chimesPausedForCall = false

    /** Radio, Spotify or a music app (the music card on the morning screen). */
    val musicOn: Boolean get() = using == "radio" || using == "spotify" || using == "app"

    // ---------- start and stop ----------

    fun start(settings: SoundSettings, spotifyClientId: String) {
        s = settings; clientId = spotifyClientId
        ringStart = System.currentTimeMillis(); userVol = false
        when (s.src) {
            "radio" -> startRadio()
            "spotify" -> startSpotify()
            "apps" -> if (s.appPkg.isNotEmpty()) startSimple("app", s.appPkg, s.appName.ifEmpty { "Music app" })
                      else { startChimes(); events.status("No music app chosen, so playing chimes") }
            else -> startChimes()
        }
        ramp()
    }

    fun stop() {
        attempt++
        main.removeCallbacksAndMessages(null)
        userVol = false
        chimes.stop(); abandonFocus(); chimesPausedForCall = false
        spotify?.let { runCatching { it.pause() }; runCatching { it.disconnect() } }
        spotify = null; spotifyPlaying = false
        if (spNativeFade) { media.pause(simplePkg); audio.musicRestore(); spNativeFade = false }
        if (radioOn || using == "radio") PlaybackService.stop(ctx)
        radioOn = false
        if (PlaybackService.listener === serviceListener) PlaybackService.listener = null
        using = null
    }

    // ---------- volume ----------

    /** Called every second: fade in over the fade time, towards the right maximum for now. */
    fun ramp() {
        if (using == null || userVol) return
        val frac = min(1.0, 0.06 + (System.currentTimeMillis() - ringStart) / (s.fade * 60_000.0))
        val top = if (earlyPlay && mode() == "night") s.earlyVol else s.vol
        applyVol((frac * top / 100).toFloat())
    }

    /** At alarm time, music already playing keeps rising from wherever it is now. */
    fun continueFromEarly() {
        ringStart = System.currentTimeMillis() - (max(0.0, curVol / (s.vol / 100.0) - 0.06) * s.fade * 60_000).toLong()
        earlyPlay = false; userVol = false
    }

    /** Phone volume buttons (or the morning screen's − and +): you take over from the fade. */
    fun nudge(up: Boolean, step: Float = 0.06f, floor: Float = 0.02f): Boolean {
        if (using == null) return false
        userVol = true
        applyVol((curVol + if (up) step else -step).coerceIn(floor, 1f))
        return true
    }

    private fun applyVol(v: Float) {
        curVol = v
        when {
            using == "chimes" -> if (!chimesPausedForCall) chimes.setVolume(v)
            (using == "spotify" || using == "app") && spNativeFade -> audio.setMusic(max(0.07f, v))
            using == "radio" && radioOn -> PlaybackService.setVolume(ctx, v)
        }
    }

    // ---------- chimes ----------

    private fun startChimes() {
        using = "chimes"
        requestFocus()
        chimes.start(curVol.coerceAtLeast(0.02f))
        events.status("")
    }

    /** Anything else failed: chimes instead (or, on the morning screen, just stop). */
    private fun fallBack(why: String) {
        if (mode() == "morning") { stop(); events.status(""); return }
        if (using == "chimes") return
        val keep = curVol
        stop()
        curVol = keep
        startChimes()
        ramp()
        events.status(why)
    }

    // Other apps pause for the chimes; a phone call pauses them until it ends
    private fun requestFocus() {
        if (focusReq != null) return
        runCatching {
            focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener({ change ->
                    if (using != "chimes") return@setOnAudioFocusChangeListener
                    if (change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) { chimesPausedForCall = true; chimes.setVolume(0f) }
                    else if (change == AudioManager.AUDIOFOCUS_GAIN) { chimesPausedForCall = false; chimes.setVolume(curVol) }
                }, main)
                .build()
            am.requestAudioFocus(focusReq!!)
        }
    }

    private fun abandonFocus() {
        focusReq?.let { runCatching { am.abandonAudioFocusRequest(it) } }
        focusReq = null
    }

    // ---------- radio: each of the station's links in turn ----------

    private var radioName = "Radio"
    private var radioUrls: List<String> = emptyList()
    private var radioLink = 0
    private var radioWaiting = false

    private val serviceListener = PlaybackService.Listener { ev -> main.post { onServiceEvent(ev) } }

    private fun startRadio() {
        radioUrls = s.stationUrls()
        radioName = s.stationNames().getOrNull(s.station)?.removeSuffix("…") ?: "Radio"
        if (radioUrls.isEmpty()) return fallBack("No stream link set, so playing chimes")
        using = "radio"
        PlaybackService.listener = serviceListener
        tryLink(0)
    }

    private fun tryLink(i: Int) {
        if (using != "radio") return
        if (i >= radioUrls.size) return fallBack("$radioName wouldn't play, so playing chimes")
        radioLink = i; radioWaiting = true; radioOn = false
        val my = ++attempt
        events.status("📻 Tuning in to $radioName…" + if (i > 0) " (link ${i + 1})" else "")
        PlaybackService.play(ctx, radioUrls[i], radioName, 0.05f)
        main.postDelayed({ if (attempt == my && radioWaiting) { PlaybackService.stop(ctx); tryLink(i + 1) } }, 15_000)
    }

    private fun onServiceEvent(ev: String) {
        if (using != "radio") return
        when (ev) {
            "playing" -> if (radioWaiting) {
                radioWaiting = false; radioOn = true
                events.status("📻 $radioName")
                applyVol(curVol)
            }
            "error", "ended" -> if (radioWaiting) { radioWaiting = false; tryLink(radioLink + 1) }
                                else fallBack("$radioName dropped out, so playing chimes")
            "stopped" -> { radioOn = false; events.userStopped() }
        }
    }

    // ---------- Spotify and other music apps ----------

    private fun startSpotify() {
        using = "spotify"; spNativeFade = false; spotifyPlaying = false
        val link = SoundSettings.parseSpotify(s.spLink)
        if (s.spAppOk && link != null && clientId.isNotEmpty()) {
            // 1) Your chosen music, through the Spotify app
            events.status("Starting Spotify…")
            abandonFocus()
            audio.musicFadeStart(); spNativeFade = true; simplePkg = "com.spotify.music"
            val my = ++attempt
            spotify = SpotifyRemote { ev ->
                main.post {
                    if (attempt != my || using != "spotify" || spotifyPlaying) return@post
                    if (ev == "sr:playing") { spotifyPlaying = true; events.status("🎵 " + s.spName.ifEmpty { "Spotify" }) }
                    else if (ev.startsWith("sr:error")) startSimple("spotify", "com.spotify.music", "Spotify")
                }
            }.also { it.connect(ctx, clientId, false, link.first, s.spShuffle) }
            main.postDelayed({ if (attempt == my && using == "spotify" && !spotifyPlaying) startSimple("spotify", "com.spotify.music", "Spotify") }, 15_000)
            return
        }
        // 2) Press play in Spotify (what you last played); 3) if nothing plays, chimes
        startSimple("spotify", "com.spotify.music", "Spotify")
    }

    /** Press play in one app and check it really started (MediaApps), as on the page. */
    private fun startSimple(kind: String, pkg: String, name: String) {
        using = kind; simplePkg = pkg
        val my = ++attempt
        events.status("Starting $name…")
        abandonFocus(); chimes.stop()
        if (!media.start(pkg)) return simpleFail(kind, "Couldn't press play in $name (is it installed?)")
        spNativeFade = true
        applyVol(curVol)
        fun check(i: Int) {
            if (attempt != my || using != kind) return
            if (media.state(pkg) == "playing" || am.isMusicActive) { events.status("🎵 $name"); return }
            if (i == 3) media.retry(pkg)          // some apps need a second ask once awake
            if (i >= 12) return simpleFail(kind, if (kind == "app") "$name didn't start (some apps don't let other apps start them)" else "$name didn't start playing")
            main.postDelayed({ check(i + 1) }, 1_000)
        }
        main.postDelayed({ check(1) }, 1_000)
    }

    private fun simpleFail(kind: String, why: String) {
        if (using != kind) return
        if (mode() == "morning") { stop(); events.status(""); return }
        if (spNativeFade) { media.pause(simplePkg); audio.musicRestore(); spNativeFade = false }
        spotify?.let { runCatching { it.disconnect() } }; spotify = null
        val keep = curVol
        attempt++
        using = null
        curVol = keep
        startChimes(); ramp()
        events.status("$why, so playing chimes")
    }
}
