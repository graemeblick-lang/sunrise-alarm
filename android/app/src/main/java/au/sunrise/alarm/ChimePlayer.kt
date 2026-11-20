package au.sunrise.alarm

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/**
 * The gentle chimes, made natively: the same six bell notes as the page (NOTES and bell() in index.html),
 * one phrase every 5.2 seconds, on the alarm volume. Used by the native Test sound.
 */
class ChimePlayer {
    private var track: AudioTrack? = null

    fun start(volume: Float) {
        stop()
        val pcm = phrase()
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
        t.write(pcm, 0, pcm.size)
        t.setLoopPoints(0, pcm.size, -1)     // repeat until stopped
        t.setVolume(volume.coerceIn(0f, 1f))
        t.play()
        track = t
    }

    fun setVolume(volume: Float) { runCatching { track?.setVolume(volume.coerceIn(0f, 1f)) } }

    fun stop() {
        val t = track ?: return
        track = null
        runCatching { t.stop() }
        runCatching { t.release() }
    }

    companion object {
        const val RATE = 22_050
        val NOTES = doubleArrayOf(523.25, 659.25, 783.99, 880.00, 1046.5, 783.99)
        const val NOTE_GAP = 0.42
        const val PHRASE_SECONDS = 5.2

        /**
         * One phrase of bells as 16-bit samples, PHRASE_SECONDS long. Each bell: a sine plus a quieter one
         * just over an octave up, rising to full in 15 ms and fading out over 2.6 s (as in the page).
         */
        fun phrase(rate: Int = RATE): ShortArray {
            val n = (PHRASE_SECONDS * rate).toInt()
            val mix = DoubleArray(n)
            NOTES.forEachIndexed { i, f ->
                val start = (0.05 + i * NOTE_GAP) * rate
                val len = (2.7 * rate).toInt()
                for (k in 0 until len) {
                    val idx = (start + k).toInt()
                    if (idx >= n) break
                    val t = k.toDouble() / rate
                    mix[idx] += 0.5 * envelope(t) * (sin(2 * PI * f * t) + 0.18 * sin(2 * PI * f * 2.01 * t))
                }
            }
            // Overlapping bells can add up: scale to fit 16 bits without clipping
            val peak = mix.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1e-9)
            val scale = 0.9 * Short.MAX_VALUE / maxOf(peak, 1.0)
            return ShortArray(n) { (mix[it] * scale).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
        }

        /** Exponential rise from 0.0001 to 1 over 15 ms, then exponential fall to 0.0001 by 2.6 s. */
        fun envelope(t: Double): Double = when {
            t < 0 -> 0.0
            t < 0.015 -> 0.0001 * (1 / 0.0001).pow(t / 0.015)
            t < 2.6 -> 0.0001.pow((t - 0.015) / (2.6 - 0.015))
            else -> 0.0
        }
    }
}
