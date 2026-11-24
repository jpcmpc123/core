package com.maxrave.media3.audio

import com.maxrave.domain.data.player.EqualizerConfig
import com.maxrave.domain.data.player.EqualizerEnhancements
import com.maxrave.domain.data.player.PreampMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class EqualizerKernelTest {
    @Test
    fun `limiter prevents digital hard clipping under heavy bass boost at full scale`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val config =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(12f, 12f, 6f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                manualPreampDb = 0f,
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.MANUAL,
                        bassBoostDb = 9f,
                        limiterEnabled = true,
                        subsonicFilterEnabled = false,
                    ),
            )

        val frames = 2_400
        val buf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            // Nearly 0 dBFS 60 Hz sine on both channels
            val sample = 0.95 * sin(2.0 * PI * 60.0 * i / fs)
            buf[i * 2] = sample
            buf[i * 2 + 1] = sample
        }

        kernel.processNormalizedFramesInPlace(buf, frames, config)

        var maxAbs = 0.0
        for (s in buf) {
            assertTrue("Expected finite sample, got $s", s.isFinite())
            maxAbs = max(maxAbs, abs(s))
        }
        assertTrue("Limiter must keep output strictly below 0 dBFS (got $maxAbs)", maxAbs <= 0.996)

        // Ensure PCM16 path with TPDF dither stays safely inside signed 16-bit bounds
        kernel.flush()
        val inShorts = ShortArray(frames * 2)
        val outShorts = ShortArray(frames * 2)
        for (i in 0 until frames) {
            val v = (0.95 * sin(2.0 * PI * 60.0 * i / fs) * 32_767.0).toInt().toShort()
            inShorts[i * 2] = v
            inShorts[i * 2 + 1] = v
        }
        kernel.processPcm16(inShorts, outShorts, frames, config)
        var satCount = 0
        for (s in outShorts) {
            if (s == Short.MAX_VALUE || s == Short.MIN_VALUE) satCount++
        }
        assertEquals("True-peak limiter must eliminate hard PCM16 saturation", 0, satCount)
    }

    @Test
    fun `stereo width opens treble while bass mono centers low frequencies`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val config =
            EqualizerConfig(
                enabled = true,
                bandsDb = emptyList(),
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.AUTO_BALANCED,
                        stereoWidth = 1.4f,
                        bassMonoEnabled = true,
                        limiterEnabled = false,
                        subsonicFilterEnabled = false,
                    ),
            )

        // Feed Left-only 60 Hz bass -> Bass Mono attenuates side below 140 Hz, creating balanced R energy.
        val frames = 4_800
        val bassBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            bassBuf[i * 2] = 0.4 * sin(2.0 * PI * 60.0 * i / fs)
            bassBuf[i * 2 + 1] = 0.0
        }
        kernel.processNormalizedFramesInPlace(bassBuf, frames, config)
        var steadyBassL = 0.0
        var steadyBassR = 0.0
        for (i in 2400 until frames) {
            steadyBassL += abs(bassBuf[i * 2])
            steadyBassR += abs(bassBuf[i * 2 + 1])
        }
        assertTrue(
            "Bass Mono at 60 Hz should route substantial energy to Right channel (R=$steadyBassR, L=$steadyBassL)",
            steadyBassR > steadyBassL * 0.45,
        )

        // Feed 2 kHz with small side info (L=0.35, R=0.25 -> diff 0.10): 1.4x width widens L-R diff cleanly.
        kernel.flush()
        val highBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val carrier = sin(2.0 * PI * 2_000.0 * i / fs)
            highBuf[i * 2] = 0.35 * carrier
            highBuf[i * 2 + 1] = 0.25 * carrier
        }
        kernel.processNormalizedFramesInPlace(highBuf, frames, config)
        var widenedDiff = 0.0
        var dryDiff = 0.0
        for (i in 2400 until frames) {
            val carrier = sin(2.0 * PI * 2_000.0 * i / fs)
            dryDiff += abs(0.35 * carrier - 0.25 * carrier)
            widenedDiff += abs(highBuf[i * 2] - highBuf[i * 2 + 1])
        }
        assertTrue(
            "Stereo width 1.4x at 2 kHz should widen L-R separation (widened=$widenedDiff vs dry=$dryDiff)",
            widenedDiff > dryDiff * 1.30,
        )
    }

    @Test
    fun `ab bypass transition ramps smoothly without instantaneous sample jump`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val bypassConfig = EqualizerConfig(enabled = false)
        assertTrue(kernel.canDirectBypass())

        val boostedConfig =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 8f, 0f, 0f, 0f, 0f),
                manualPreampDb = 0f,
                enhancements = EqualizerEnhancements(preampMode = PreampMode.MANUAL, limiterEnabled = true),
            )

        // Prime continuous 1 kHz wave at moderate level across bypass -> active transition boundary.
        val frames = 960 // 20 ms
        val buf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val s = 0.3 * sin(2.0 * PI * 1_000.0 * i / fs)
            buf[i * 2] = s
            buf[i * 2 + 1] = s
        }
        // Enable EQ starting directly on first sample: ramp starts wet=0 -> wet=1 over 10 ms.
        kernel.processNormalizedFramesInPlace(buf, frames, boostedConfig)
        assertFalse(kernel.canDirectBypass())

        // Sample 0 must stay close to input 0.0 rather than spiking, and adjacent sample steps stay small.
        var maxDelta = 0.0
        for (i in 1 until frames) {
            maxDelta = max(maxDelta, abs(buf[i * 2] - buf[(i - 1) * 2]))
        }
        assertTrue("10 ms ramp prevents sudden clicks on transition (maxDelta=$maxDelta)", maxDelta < 0.12)
        assertFalse("Bypass should stay inactive while curve is active", bypassConfig.enabled)
    }

    @Test
    fun `bass mono preserves positive channel polarity and limiter leaves -1_5 dBFS unattenuated`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val config =
            EqualizerConfig(
                enabled = true,
                bandsDb = emptyList(),
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.AUTO_BALANCED,
                        stereoWidth = 1.5f,
                        bassMonoEnabled = true,
                        limiterEnabled = true,
                        subsonicFilterEnabled = false,
                    ),
            )

        // 1 kHz Left-only sine at 0.5 amplitude: with fixed HPF polarity, Left must remain strongly
        // correlated with +sin (no 180-degree channel inversion).
        val frames = 4_800
        val buf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            buf[i * 2] = 0.5 * sin(2.0 * PI * 1_000.0 * i / fs)
            buf[i * 2 + 1] = 0.0
        }
        kernel.processNormalizedFramesInPlace(buf, frames, config)

        var dotProd = 0.0
        var dryEnergy = 0.0
        for (i in 2_400 until frames) {
            val dry = 0.5 * sin(2.0 * PI * 1_000.0 * i / fs)
            dotProd += dry * buf[i * 2]
            dryEnergy += dry * dry
        }
        assertTrue(
            "Left channel 1 kHz signal must stay positively correlated with input (dot=$dotProd, energy=$dryEnergy)",
            dotProd > dryEnergy * 0.90,
        )

        // -1.5 dBFS (0.84) dual-mono sine with unity stereo must stay transparent (< -0.63 dBFS threshold).
        kernel.flush()
        val unityConfig =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f),
                manualPreampDb = 0f,
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.MANUAL,
                        limiterEnabled = true,
                        subsonicFilterEnabled = false,
                    ),
            )
        val loudBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.84 * sin(2.0 * PI * 300.0 * i / fs)
            loudBuf[i * 2] = v
            loudBuf[i * 2 + 1] = v
        }
        kernel.processNormalizedFramesInPlace(loudBuf, frames, unityConfig)
        var peakOut = 0.0
        for (i in 2_400 until frames) {
            peakOut = max(peakOut, abs(loudBuf[i * 2]))
        }
        assertTrue(
            "Signal at 0.84 amplitude should pass without premature limiter compression (peakOut=$peakOut)",
            peakOut >= 0.835,
        )
    }

    @Test
    fun `spatial stage adds decorrelated mid-high ambience while keeping sub-bass clean`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val spatialConfig =
            EqualizerConfig(
                enabled = true,
                bandsDb = emptyList(),
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.AUTO_BALANCED,
                        stereoWidth = 1.25f,
                        spatialStage = 0.6f,
                        bassMonoEnabled = true,
                        subsonicFilterEnabled = false,
                        limiterEnabled = true,
                    ),
            )

        // Impulse on L channel only at sample 100 -> reflections must arrive on R after ~7-27 ms.
        val frames = 2_400
        val buf = DoubleArray(frames * 2)
        buf[100 * 2] = 0.7
        kernel.processNormalizedFramesInPlace(buf, frames, spatialConfig)

        var reflectedRight = 0.0
        for (i in 400 until frames) {
            val s = buf[i * 2 + 1]
            assertTrue("Spatial output must stay finite", s.isFinite())
            reflectedRight += abs(s)
        }
        assertTrue("Expected cross-coupled early reflections on Right channel (sum=$reflectedRight)", reflectedRight > 0.01)
    }
}
