package com.maxrave.media3.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

class BiquadFilterTest {

    @Test
    fun `lpf sweep from 20kHz to 200Hz stays finite and attenuates high frequencies`() {
        val filter = BiquadFilter()
        val sampleRate = 48_000
        val totalSamples = sampleRate // 1 second rapid sweep
        val updateInterval = 64

        var maxAbs = 0.0
        var tailHighFreqEnergy = 0.0
        var tailCount = 0

        for (i in 0 until totalSamples) {
            if (i % updateInterval == 0) {
                val t = i.toFloat() / totalSamples
                val cutoff = exp(ln(20_000f) + (ln(200f) - ln(20_000f)) * t)
                filter.updateCoefficients(
                    cutoffHz = cutoff,
                    sampleRate = sampleRate,
                    type = BiquadFilter.FilterType.LOW_PASS,
                )
            }
            // High-frequency 6 kHz tone mixed with stereo phase offset
            val left = 0.8 * sin(2.0 * PI * 6_000.0 * i / sampleRate)
            val right = 0.8 * sin(2.0 * PI * 6_000.0 * i / sampleRate + 0.5)
            val outL = filter.processStereoLeft(left)
            val outR = filter.processStereoRight(right)
            assertTrue("Left sample must stay finite", outL.isFinite())
            assertTrue("Right sample must stay finite", outR.isFinite())
            maxAbs = maxOf(maxAbs, abs(outL), abs(outR))
            if (i >= totalSamples - 4096) {
                tailHighFreqEnergy += outL * outL
                tailCount++
            }
        }

        assertTrue("Sweep peak must stay bounded without blowup (maxAbs=$maxAbs)", maxAbs < 2.0)
        val tailRms = sqrt(tailHighFreqEnergy / tailCount)
        assertTrue("6 kHz signal should be heavily attenuated at 200 Hz LPF (rms=$tailRms)", tailRms < 0.01)
    }

    @Test
    fun `hpf sweep from 2kHz to 20Hz attenuates bass early and restores it at 20Hz`() {
        val filter = BiquadFilter()
        val sampleRate = 48_000

        // At 2 kHz HPF, a 100 Hz bass tone should be strongly attenuated
        filter.updateCoefficients(2_000f, sampleRate, type = BiquadFilter.FilterType.HIGH_PASS)
        var blockedEnergy = 0.0
        for (i in 0 until 4096) {
            val sample = 0.8 * sin(2.0 * PI * 100.0 * i / sampleRate)
            val out = filter.processSampleMono(sample)
            assertTrue(out.isFinite())
            if (i >= 2048) blockedEnergy += out * out
        }
        val blockedRms = sqrt(blockedEnergy / 2048)
        assertTrue("100 Hz bass must be attenuated at 2 kHz HPF (rms=$blockedRms)", blockedRms < 0.02)

        // Sweep smoothly down to 20 Hz
        val sweepSamples = 16_384
        for (i in 0 until sweepSamples) {
            if (i % 64 == 0) {
                val t = i.toFloat() / sweepSamples
                val cutoff = exp(ln(2_000f) + (ln(20f) - ln(2_000f)) * t)
                filter.updateCoefficients(cutoff, sampleRate, type = BiquadFilter.FilterType.HIGH_PASS)
            }
            val sample = 0.8 * sin(2.0 * PI * 100.0 * i / sampleRate)
            val out = filter.processSampleMono(sample)
            assertTrue(out.isFinite())
        }

        // At 20 Hz HPF, the 100 Hz bass tone should pass nearly intact
        filter.updateCoefficients(20f, sampleRate, type = BiquadFilter.FilterType.HIGH_PASS)
        var openEnergy = 0.0
        for (i in 0 until 4096) {
            val sample = 0.8 * sin(2.0 * PI * 100.0 * i / sampleRate)
            val out = filter.processSampleMono(sample)
            assertTrue(out.isFinite())
            if (i >= 2048) openEnergy += out * out
        }
        val openRms = sqrt(openEnergy / 2048)
        assertTrue("100 Hz bass must pass cleanly at 20 Hz HPF (rms=$openRms)", openRms > 0.5)
    }

    @Test
    fun `invalid parameters fall back to identity passthrough and reset clears history`() {
        val filter = BiquadFilter()
        filter.updateCoefficients(Float.NaN, 48_000, type = BiquadFilter.FilterType.LOW_PASS)
        assertEquals(0.42, filter.processSampleMono(0.42), 1e-6)

        filter.updateCoefficients(500f, 0, type = BiquadFilter.FilterType.HIGH_PASS)
        assertEquals(-0.25, filter.processSampleMono(-0.25), 1e-6)

        filter.updateCoefficients(500f, 48_000, type = BiquadFilter.FilterType.LOW_PASS)
        repeat(100) { filter.processSampleMono(1.0) }
        filter.reset()
        assertEquals(0.0, filter.processSampleMono(0.0), 1e-9)
    }
}
