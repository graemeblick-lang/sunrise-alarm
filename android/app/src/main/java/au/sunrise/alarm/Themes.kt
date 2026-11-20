package au.sunrise.alarm

/**
 * The app's colour themes and colour maths, matching index.html (THEMES, mix(), customSun(), applyTheme()),
 * so the native settings screens look like the rest of the app. Colours are 0xRRGGBB ints.
 * Plain Kotlin (no Android classes) so it can be unit-tested.
 */
object Themes {
    data class Theme(
        val key: String,
        val name: String,
        /** The theme's accent ("amber" in the page) and the ink used on it. */
        val primary: Int,
        val onPrimary: Int,
        /** For the little preview circle: glow, swatch and sky colours. */
        val glow: Int,
        val swatch: Int,
        val sky: Int,
    )

    const val PHONE = "phone"

    val ALL = listOf(
        Theme("sunrise", "Sunrise", 0xffb36b, 0x2a1205, 0xffc979, 0xff9a4a, 0x3a0d02),
        Theme("ocean", "Ocean dawn", 0x7cc8e0, 0x04212d, 0xb8e6f0, 0x3aa2c2, 0x04202e),
        Theme("lavender", "Lavender", 0xc9a6ff, 0x1f0f38, 0xffc3dc, 0xb77ae6, 0x22093a),
        Theme("forest", "Forest", 0xa8d672, 0x12240a, 0xe4f2a0, 0x8fc040, 0x0f2a0a),
        Theme("minimal", "Minimal", 0xe8e4dc, 0x1a1816, 0xf0eeea, 0xd8d3ca, 0x2a2826),
    )

    fun byKey(key: String?): Theme = ALL.firstOrNull { it.key == key } ?: ALL[0]

    /** Sunrise colour presets: dawn, glow, daylight (same as the page). */
    val SUN_PRESETS = listOf(
        "Classic" to listOf("#3a0d02", "#ff7a26", "#fff1d6"),
        "Pink dawn" to listOf("#2a0622", "#ff6f91", "#fff0f3"),
        "Golden" to listOf("#2e1500", "#ffb000", "#fff6d8"),
        "Cool blue" to listOf("#03162e", "#4aa3df", "#eef8ff"),
        "Candlelight" to listOf("#1f0700", "#e0661c", "#ffd9a8"),
        "Forest dawn" to listOf("#0b1a06", "#9acd32", "#fbffe8"),
    )

    /** Blend a towards b by t (0..1), channel by channel, rounded like the page's mix(). */
    fun mix(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val x = (a shr shift) and 0xff
            val y = (b shr shift) and 0xff
            val v = Math.round(x + (y - x) * k).toInt().coerceIn(0, 255)
            out = out or (v shl shift)
        }
        return out
    }

    fun parse(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        return h.toIntOrNull(16)
    }

    fun hex(c: Int): String = "#%06x".format(c and 0xffffff)

    /** The page's Material 3 tones for a theme accent (applyTheme in index.html). */
    data class Tones(
        val primary: Int, val onPrimary: Int,
        val surface: Int, val container: Int, val containerHigh: Int, val containerHighest: Int,
        val primaryContainer: Int, val onPrimaryContainer: Int,
        val secondaryContainer: Int, val onSecondaryContainer: Int,
        val onSurface: Int, val onSurfaceVariant: Int, val outline: Int, val outlineVariant: Int,
    )

    fun tones(t: Theme): Tones {
        val p = t.primary
        return Tones(
            primary = p, onPrimary = t.onPrimary,
            surface = mix(0x0f0e0d, p, .04),
            container = mix(0x1d1b1a, p, .07),
            containerHigh = mix(0x272524, p, .09),
            containerHighest = mix(0x322f2e, p, .1),
            primaryContainer = mix(0x000000, p, .32),
            onPrimaryContainer = mix(p, 0xffffff, .65),
            secondaryContainer = mix(0x2a2826, p, .28),
            onSecondaryContainer = mix(p, 0xffffff, .75),
            onSurface = 0xe7e1de,
            onSurfaceVariant = mix(0xcac4c0, p, .08),
            outline = mix(0x938f8b, p, .12),
            outlineVariant = mix(0x49454f, p, .1),
        )
    }

    /** The sky the sunrise goes through with your own three colours (customSun().sky in the page). */
    fun customSky(dawn: Int, glow: Int, daylight: Int): List<Pair<Float, Int>> = listOf(
        0f to 0x000000,
        .25f to mix(0x000000, dawn, .35),
        .5f to dawn,
        .7f to mix(dawn, glow, .6),
        .85f to mix(glow, daylight, .5),
        1f to daylight,
    )
}
