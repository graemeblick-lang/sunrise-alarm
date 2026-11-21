package au.sunrise.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sleep sounds, made by the app (no internet, no music app): rain, ocean waves or soft noise. A 20-second
 * loop, faded into itself so the join can't be heard, on the media volume.
 */
class NoisePlayer {
    private var track: AudioTrack? = null
    var volume = 0f
        private set
    val playing get() = track != null

    fun start(kind: String, vol: Float) {
        stop()
        val pcm = loop(kind)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)
        volume = vol.coerceIn(0f, 1f)
        t.setVolume(volume)
        t.play()
        track = t
    }

    fun setVolume(v: Float) { volume = v.coerceIn(0f, 1f); runCatching { track?.setVolume(volume) } }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    companion object {
        const val RATE = 22_050
        const val SECONDS = 20
        val KINDS = listOf("rain", "ocean", "noise")
        val NAMES = mapOf("rain" to "Rain", "ocean" to "Ocean", "noise" to "Soft noise")

        /** The loop for [kind] as 16-bit samples, peaking at about 60 % so it's never harsh. */
        fun loop(kind: String, rate: Int = RATE, seed: Int = 7): ShortArray {
            val n = SECONDS * rate
            val fade = rate                     // 1 s crossfade at the join
            val raw = generate(kind, n + fade, rate, Random(seed))
            // Fade the extra second at the end into the start, so the loop is seamless
            for (i in 0 until fade) {
                val a = i.toDouble() / fade
                raw[i] = raw[i] * a + raw[n + i] * (1 - a)
            }
            val peak = (0 until n).maxOf { abs(raw[it]) }.coerceAtLeast(1e-9)
            val scale = 0.6 * Short.MAX_VALUE / peak
            return ShortArray(n) { (raw[it] * scale).toInt().toShort() }
        }

        private fun generate(kind: String, n: Int, rate: Int, r: Random): DoubleArray {
            val out = DoubleArray(n)
            // Pink noise (Paul Kellet's filter): soft, like a fan
            var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
            var brown = 0.0
            var drop = 0.0
            for (i in 0 until n) {
                val w = r.nextDouble() * 2 - 1
                b0 = 0.99886 * b0 + w * 0.0555179; b1 = 0.99332 * b1 + w * 0.0750759
                b2 = 0.96900 * b2 + w * 0.1538520; b3 = 0.86650 * b3 + w * 0.3104856
                b4 = 0.55000 * b4 + w * 0.5329522; b5 = -0.7616 * b5 - w * 0.0168980
                val pink = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362) * 0.11
                b6 = w * 0.115926
                brown = (brown + 0.02 * w) / 1.02            // deeper rumble
                out[i] = when (kind) {
                    "ocean" -> {
                        // Two waves every 10 s: swell up, break, wash back
                        val phase = (i.toDouble() / rate % 10.0) / 10.0
                        val swell = 0.25 + 0.75 * sin(PI * phase).let { it * it } * exp(-1.5 * phase)
                        (brown * 3.5 + pink * 0.6) * swell
                    }
                    "rain" -> {
                        // A steady hiss with drops: short bright ticks at random
                        if (r.nextDouble() < 40.0 / rate) drop = 0.35 + r.nextDouble() * 0.5
                        drop *= 0.992
                        pink * 0.8 + w * drop * 0.35
                    }
                    else -> pink
                }
            }
            return out
        }
    }
}
