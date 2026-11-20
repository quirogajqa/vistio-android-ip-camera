package com.quirogaj.vistio.data

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Filters the Falcon's 8 kHz microphone stream before playback. */
internal class FalconAudioFilter {
    private val rumbleLow = Biquad(200.0, highPass = true, q = 0.5412)
    private val rumbleHigh = Biquad(200.0, highPass = true, q = 1.3066)
    private val hiss = Biquad(2_950.0, highPass = false)
    private val envelopeAttack = smoothing(0.008)
    private val envelopeRelease = smoothing(0.10)
    private val noiseRise = smoothing(4.0)
    private val noiseFall = smoothing(0.15)
    private var envelope = 0.0
    private var noiseFloor = 12.0
    private var gain = 1.0

    fun process(pcm: ByteArray) {
        for (offset in pcm.indices step 2) {
            val sample = (pcm[offset].toInt() and 255) or (pcm[offset + 1].toInt() shl 8)
            val filtered = hiss.process(rumbleHigh.process(rumbleLow.process(sample.toDouble())))
            val level = abs(filtered)
            envelope += (level - envelope) *
                if (level > envelope) envelopeAttack else envelopeRelease
            val background = envelope.coerceIn(8.0, 260.0)
            noiseFloor += (background - noiseFloor) *
                if (background > noiseFloor) noiseRise else noiseFall

            // Attenuate quiet background smoothly and open quickly when voice rises above it.
            val aboveNoise = ((envelope / noiseFloor - 1.15) / 1.20).coerceIn(0.0, 1.0)
            val targetGain = 0.32 + 0.68 * aboveNoise
            gain += (targetGain - gain) *
                if (targetGain > gain) envelopeAttack else envelopeRelease

            val output = (filtered * gain).roundToInt().coerceIn(-32_768, 32_767)
            pcm[offset] = output.toByte()
            pcm[offset + 1] = (output shr 8).toByte()
        }
    }

    private class Biquad(cutoff: Double, highPass: Boolean, q: Double = 1 / sqrt(2.0)) {
        private val angle = 2 * Math.PI * cutoff / FalconAudio.SAMPLE_RATE
        private val cosine = cos(angle)
        private val alpha = sin(angle) / (2 * q)
        private val denominator = 1 + alpha
        private val b0 = (if (highPass) 1 + cosine else 1 - cosine) / (2 * denominator)
        private val b1 = if (highPass) -2 * b0 else 2 * b0
        private val a1 = -2 * cosine / denominator
        private val a2 = (1 - alpha) / denominator
        private var z1 = 0.0
        private var z2 = 0.0

        fun process(input: Double): Double {
            val output = b0 * input + z1
            z1 = b1 * input - a1 * output + z2
            z2 = b0 * input - a2 * output
            return output
        }
    }

    private fun smoothing(seconds: Double): Double =
        1 - exp(-1 / (seconds * FalconAudio.SAMPLE_RATE))
}
