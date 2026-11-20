package au.sunrise.alarm

import kotlin.math.max
import kotlin.math.pow

/** Every colour a theme uses on the night, alarm and morning screens (see ThemeData.kt). Colours are 0xRRGGBB. */
data class FullTheme(
    val name: String,
    val vars: Map<String, Int>,
    val sky: List<Pair<Float, Int>>,
    val glow: List<Pair<Float, Int>>,
    val night: List<Int>,
    val clockMid: Int,
    val clockHi: Int,
    val clockEnd: Int,
) {
    /** A named colour, e.g. "r1" (alarm screen background) or "mInk" (morning screen text). */
    fun v(key: String): Int = vars[key] ?: 0x808080
}

/** The sunrise, as the page draws it (setSunrise, ramp, customSun and addPhoneTheme in index.html). */
object Sunrise {
    private const val K = 0x000000
    private const val W = 0xffffff
    private val NIGHT_BRIGHTNESS = listOf(0.004, 0.008, 0.015, 0.03, 0.06, 0.1)

    /** One moment of the sunrise. [sunY] and [sunR] are percentages, like the page's CSS. */
    data class Frame(val sky: Int, val glow: Int, val sunY: Double, val sunR: Double, val clock: Int, val brightness: Double)

    /** The colour at [p] (0..1) along colour stops, blending between neighbours (the page's ramp()). */
    fun ramp(stops: List<Pair<Float, Int>>, p: Double): Int {
        for (i in 1 until stops.size) {
            if (p <= stops[i].first) {
                val (p0, c0) = stops[i - 1]
                val (p1, c1) = stops[i]
                val span = (p1 - p0).toDouble().let { if (it == 0.0) 1.0 else it }
                return Themes.mix(c0, c1, (p - p0) / span)
            }
        }
        return stops.last().second
    }

    /** Sky and glow for your own three sunrise colours (customSun()). */
    fun customRamps(cols: List<Int>): Pair<List<Pair<Float, Int>>, List<Pair<Float, Int>>> {
        val (c0, c1, c2) = cols
        val sky = Themes.customSky(c0, c1, c2)
        val glow = listOf(
            0f to K, .15f to Themes.mix(K, c0, .7), .4f to Themes.mix(c0, c1, .55),
            .65f to c1, .85f to Themes.mix(c1, c2, .6), 1f to Themes.mix(c2, W, .5),
        )
        return sky to glow
    }

    /**
     * The sunrise at [pTime] (0 = night, 1 = alarm time), as in the Android app's version of setSunrise():
     * the screen's backlight does the dimming, so colours can go all the way. [nightLight] 0 = off, 1, 2 = brighter.
     */
    fun frame(theme: FullTheme, look: LookSettings, pTime: Double, nightLight: Int = 0): Frame {
        val (sky, glow) = if (look.sunCustom) customRamps(look.sunCols.map { Themes.parse(it) ?: 0 }) else theme.sky to theme.glow
        val cap = look.sunMax / 100.0
        val p = pTime.coerceIn(0.0, 1.0).pow(1.8)
        val base = theme.night[4]
        val clock = if (p < .55) Themes.mix(base, theme.clockMid, p / .55 * .6) else Themes.mix(theme.clockHi, theme.clockEnd, (p - .55) / .45)
        val nightB = NIGHT_BRIGHTNESS[(look.dim - 1).coerceIn(0, 5)]
        val brightness = when (nightLight) {
            1 -> 0.3
            2 -> 0.8
            else -> if (pTime <= 0) nightB else nightB + max(0.0, cap - nightB) * pTime.coerceAtMost(1.0).pow(2.6)
        }
        return Frame(ramp(sky, p), ramp(glow, p), 140 - p * 95, 20 + p * 160, clock, brightness)
    }

    /** The theme for a key, with "phone" built from the wallpaper colours when Android has them. */
    fun theme(key: String, phoneColours: Map<String, Int>?): FullTheme =
        if (key == Themes.PHONE && phoneColours != null) phoneTheme(phoneColours) else ThemeData.ALL[key] ?: ThemeData.ALL.getValue("sunrise")

    /** "Phone colours" from Android 12+'s wallpaper palette (addPhoneTheme in the page). Keys like "a1_500". */
    fun phoneTheme(c: Map<String, Int>): FullTheme {
        fun k(name: String) = c[name] ?: 0x808080
        val a100 = k("a1_100"); val a200 = k("a1_200"); val a300 = k("a1_300"); val a500 = k("a1_500")
        val a700 = k("a1_700"); val a800 = k("a1_800"); val a900 = k("a1_900")
        return FullTheme(
            name = "Phone colours",
            vars = mapOf(
                "amber" to a200, "acc2" to a300, "accInk" to a800, "setupGlow" to a900,
                "r1" to Themes.mix(a100, W, .6), "r2" to a100, "r3" to a200, "rInk" to a900, "rSub" to a700, "rSub2" to a700,
                "rBtn" to a700, "rBtnText" to W,
                "m1" to Themes.mix(a100, W, .7), "m2" to Themes.mix(a100, W, .4), "m3" to a100, "mInk" to k("n1_900"),
                "mGreet" to a700, "mSub" to a800, "mLabel" to a700, "mMuted" to k("a2_700"), "mBtn" to a700,
                "nl1" to a500, "nl2" to a800, "nl3" to Themes.mix(K, a900, .5), "nb1" to a300, "nb2" to a700, "nb3" to a900,
            ),
            sky = listOf(0f to K, .25f to Themes.mix(K, a900, .35), .5f to a900, .7f to a700, .85f to a300, 1f to Themes.mix(a100, W, .6)),
            glow = listOf(0f to K, .15f to a900, .4f to a700, .65f to a500, .85f to a200, 1f to W),
            night = listOf(.12, .2, .3, .42, .55, .7).map { Themes.mix(K, a500, it) },
            clockMid = a100, clockHi = a500, clockEnd = a900,
        )
    }
}
