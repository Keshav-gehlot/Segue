package com.segue.app.player

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Test content for the reference player. Every sample is a pure function of its frame index, so two
 * phones playing "position p" produce identical audio and a handoff can be checked by ear or by mic:
 *
 *  - a quiet 220 Hz bed, phase-continuous across positions;
 *  - a 60 ms chirp at the start of every second whose pitch encodes the second (1000 + 200*(s % 10) Hz);
 *  - every 10th second the chirp is doubled.
 *
 * A skipped or repeated stretch around a handoff shows up as a wrong chirp pitch sequence.
 */
object Signal {
    private const val BED_HZ = 220.0
    private const val CHIRP_MS = 60

    fun chirpHz(second: Long): Double = 1000.0 + 200.0 * (second % 10)

    fun sample(frame: Long, rate: Int): Short {
        val t = frame.toDouble() / rate
        var v = 0.12 * sin(2 * PI * BED_HZ * t)
        val second = frame / rate
        val inSec = frame % rate                                   // frames into this second
        val chirpFrames = rate * CHIRP_MS / 1000
        val double = second % 10 == 0L
        val starts = if (double) longArrayOf(0, (chirpFrames * 2).toLong()) else longArrayOf(0)
        for (s in starts) {
            val k = inSec - s
            if (k in 0 until chirpFrames) {
                val env = 0.5 - 0.5 * cos(2 * PI * k / chirpFrames)  // Hann
                v += 0.55 * env * sin(2 * PI * chirpHz(second) * k / rate)
            }
        }
        return (v.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort()
    }

    fun fill(buf: ShortArray, startFrame: Long, rate: Int) {
        for (i in buf.indices) buf[i] = sample(startFrame + i, rate)
    }
}
