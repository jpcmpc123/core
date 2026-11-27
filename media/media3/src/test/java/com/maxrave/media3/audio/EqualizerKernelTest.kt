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

    @Test
    fun `treble boost and stereo width on sharp high-frequency transient stays below true-peak ceiling without clipping`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val config =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 2f, 4f, 4f),
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.AUTO_BALANCED,
                        trebleBoostDb = 6f,
                        stereoWidth = 1.35f,
                        limiterEnabled = true,
                        subsonicFilterEnabled = false,
                    ),
            )

        val frames = 2_400
        val buf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            // Sudden high-frequency transient step (9.5 kHz hi-hat + 1 kHz body) at -1 dBFS
            val env = if (i in 120..480) 0.89 else 0.15
            val l = env * (0.45 * sin(2.0 * PI * 1_000.0 * i / fs) + 0.55 * sin(2.0 * PI * 9_500.0 * i / fs))
            val r = env * (0.45 * sin(2.0 * PI * 1_000.0 * i / fs) - 0.35 * sin(2.0 * PI * 9_500.0 * i / fs))
            buf[i * 2] = l
            buf[i * 2 + 1] = r
        }

        kernel.processNormalizedFramesInPlace(buf, frames, config)

        var maxAbs = 0.0
        for (s in buf) {
            assertTrue("Expected finite sample under treble transient, got $s", s.isFinite())
            maxAbs = max(maxAbs, abs(s))
        }
        assertTrue(
            "Lookahead 4x true-peak limiter must hold sharp treble transients strictly <= 0.975 (got $maxAbs)",
            maxAbs <= 0.975,
        )
    }

    @Test
    fun `sweeping treble shelf across zero db keeps fixed slots and ramps coefficients without pops`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val negConfig =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, -2f, 0f),
                enhancements = EqualizerEnhancements(trebleBoostDb = -2f, limiterEnabled = true),
            )
        val zeroConfig =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                enhancements = EqualizerEnhancements(trebleBoostDb = 0.5f, limiterEnabled = true),
            )
        val boostConfig =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 3f, 2f),
                enhancements = EqualizerEnhancements(trebleBoostDb = 4f, limiterEnabled = true),
            )

        // Process continuous 1 kHz sine across 3 slider updates crossing 0 dB
        val chunkFrames = 480
        val full = DoubleArray(chunkFrames * 3 * 2)
        for (c in 0 until 3) {
            val cfg = when (c) {
                0 -> negConfig
                1 -> zeroConfig
                else -> boostConfig
            }
            val sub = DoubleArray(chunkFrames * 2)
            for (i in 0 until chunkFrames) {
                val sampleIdx = c * chunkFrames + i
                val v = 0.35 * sin(2.0 * PI * 1_000.0 * sampleIdx / fs)
                sub[i * 2] = v
                sub[i * 2 + 1] = v
            }
            kernel.processNormalizedFramesInPlace(sub, chunkFrames, cfg)
            sub.copyInto(full, c * chunkFrames * 2)
        }

        // Ignore the initial 2 ms lookahead lead-in and verify no boundary click/step exceeds expected 1 kHz step
        var maxStep = 0.0
        for (i in 120 until chunkFrames * 3) {
            val delta = abs(full[i * 2] - full[(i - 1) * 2])
            maxStep = max(maxStep, delta)
        }
        assertTrue("Expected click-free coefficient ramp across 0 dB boundary (maxStep=$maxStep)", maxStep < 0.09)
    }

    @Test
    fun `vicanek matched peaking avoids high frequency cramping and cancels boost and cut symmetrically`() {
        val fs = 44_100.0
        val boostBand =
            com.maxrave.domain.data.player.ParametricBand(
                kind = com.maxrave.domain.data.player.BiquadFilterKind.PEAKING,
                freqHz = 16_000f,
                gainDb = 6f,
                q = 1.41f,
            )
        val cutBand = boostBand.copy(gainDb = -6f)
        val cBoost = com.maxrave.domain.data.player.computeNormalizedBiquad(boostBand, fs)
        val cCut = com.maxrave.domain.data.player.computeNormalizedBiquad(cutBand, fs)
        assertTrue("Vicanek peaking boost must produce valid coefficients", cBoost != null)
        assertTrue("Vicanek peaking cut must produce valid coefficients", cCut != null)

        // Evaluate digital magnitude at 19 kHz (where RBJ cramps heavily to ~0 dB at Nyquist):
        // analog 16 kHz +6 dB Q=1.41 has ~+3.87 dB at 19 kHz; Vicanek stays within < 0.8 dB!
        fun magDb(c: DoubleArray, f: Double): Double {
            val w = 2.0 * PI * f / fs
            val cos1 = kotlin.math.cos(w)
            val sin1 = kotlin.math.sin(w)
            val cos2 = kotlin.math.cos(2.0 * w)
            val sin2 = kotlin.math.sin(2.0 * w)
            val numRe = c[0] + c[1] * cos1 + c[2] * cos2
            val numIm = -(c[1] * sin1 + c[2] * sin2)
            val denRe = 1.0 + c[3] * cos1 + c[4] * cos2
            val denIm = -(c[3] * sin1 + c[4] * sin2)
            val magSq = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
            return 10.0 * kotlin.math.log10(magSq)
        }

        val gainAtCenter = magDb(cBoost!!, 16_000.0)
        assertTrue("16 kHz center gain should match +6 dB closely (got $gainAtCenter)", abs(gainAtCenter - 6.0) < 0.25)

        val gainAt19k = magDb(cBoost, 19_000.0)
        assertTrue("19 kHz should stay uncramped above +3.0 dB (got $gainAt19k)", gainAt19k > 3.0)

        // Exact symmetric boost/cut cancellation across the spectrum
        for (freq in listOf(100.0, 4_000.0, 12_000.0, 16_000.0, 20_000.0)) {
            val sumDb = magDb(cBoost, freq) + magDb(cCut!!, freq)
            assertTrue("Boost + cut at $freq Hz must cancel symmetrically (sum=$sumDb dB)", abs(sumDb) < 0.01)
        }
    }

    @Test
    fun `four x true peak limiter catches inter sample peaks on fs over 4 shifted sine`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val config =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(0f, 0f, 0f, 0f, 0f, 0.1f, 0f, 0f, 0f, 0f),
                manualPreampDb = 0f,
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.MANUAL,
                        limiterEnabled = true,
                        subsonicFilterEnabled = false,
                    ),
            )

        // Fs/4 sine shifted by 45 degrees with amplitude 1.15:
        // Raw sample peaks are ONLY 1.15 * 0.7071 = 0.813 (< 0.891 sample-peak threshold!),
        // yet the reconstructed analog inter-sample True Peak is 1.15 (+1.21 dBTP)!
        val frames = 2_400
        val buf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val s = 1.15 * sin(2.0 * PI * (i / 4.0) + PI / 4.0)
            buf[i * 2] = s
            buf[i * 2 + 1] = s
        }

        kernel.processNormalizedFramesInPlace(buf, frames, config)

        // Steady-state sample peak must be pulled down so analog true peak (sample / 0.7071) <= 0.975!
        var maxSampleSteady = 0.0
        for (i in 1_000 until frames) {
            maxSampleSteady = max(maxSampleSteady, abs(buf[i * 2]))
        }
        val estimatedTruePeak = maxSampleSteady / kotlin.math.sqrt(0.5)
        assertTrue(
            "4x True-Peak limiter must catch inter-sample peak above 0 dBTP (sample=$maxSampleSteady, TP=$estimatedTruePeak)",
            estimatedTruePeak <= 0.98,
        )
    }

    @Test
    fun `pure linear replaygain runs independently of eq bypass with zero compression below threshold`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val bypassConfig = EqualizerConfig(enabled = false)

        // 1. Cut-only (-3 dB) with EQ OFF: exact pure linear scaling, zero coloration
        kernel.syncNormalizationGainDb(-3.0f)
        assertFalse("Active ReplayGain must disable direct byte bypass", kernel.canDirectBypass())
        val frames = 1_000
        val cutBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.5 * sin(2.0 * PI * 1_000.0 * i / fs)
            cutBuf[i * 2] = v
            cutBuf[i * 2 + 1] = v
        }
        kernel.processNormalizedFramesInPlace(cutBuf, frames, bypassConfig)
        val expectedLinearCut = Math.pow(10.0, -3.0 / 20.0)
        var maxErr = 0.0
        for (i in 0 until frames) {
            val expected = 0.5 * sin(2.0 * PI * 1_000.0 * i / fs) * expectedLinearCut
            maxErr = max(maxErr, abs(cutBuf[i * 2] - expected))
        }
        assertTrue("ReplayGain attenuation must be bit-exact linear gain (maxErr=$maxErr)", maxErr < 1e-6)

        // 2. Positive boost (+3.5 dB) on quiet signal (0.3 peak -> 0.449 peak): 0% compression!
        kernel.flush()
        kernel.syncNormalizationGainDb(3.5f)
        val boostBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.3 * sin(2.0 * PI * 500.0 * i / fs)
            boostBuf[i * 2] = v
            boostBuf[i * 2 + 1] = v
        }
        kernel.processNormalizedFramesInPlace(boostBuf, frames, bypassConfig)
        val expectedPeak = 0.3 * Math.pow(10.0, 3.5 / 20.0)
        var measuredPeak = 0.0
        for (i in 300 until frames) {
            measuredPeak = max(measuredPeak, abs(boostBuf[i * 2]))
        }
        assertEquals(
            "Positive ReplayGain below -1 dBFS must have 0% dynamic compression",
            expectedPeak,
            measuredPeak,
            0.005,
        )
    }

    @Test
    fun `bs2b crossfeed keeps mono unity gain and feeds low frequency stereo naturally`() {
        val kernel = EqualizerKernel()
        val fs = 48_000
        kernel.configure(sampleRate = fs, channelCount = 2)

        val config =
            EqualizerConfig(
                enabled = true,
                bandsDb = emptyList(),
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.MANUAL,
                        crossfeedEnabled = true,
                        limiterEnabled = false,
                        subsonicFilterEnabled = false,
                    ),
            )

        val frames = 4_800
        // 1. Mono signal (L == R): BS2B formula outL = L + k*(lpR - lpL) must have exact unity gain!
        val monoBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.4 * sin(2.0 * PI * 250.0 * i / fs)
            monoBuf[i * 2] = v
            monoBuf[i * 2 + 1] = v
        }
        kernel.processNormalizedFramesInPlace(monoBuf, frames, config)
        var maxMonoDiff = 0.0
        for (i in 2_400 until frames) {
            val v = 0.4 * sin(2.0 * PI * 250.0 * i / fs)
            maxMonoDiff = max(maxMonoDiff, abs(monoBuf[i * 2] - v))
        }
        assertTrue("BS2B crossfeed must be exact unity gain on mono (maxDiff=$maxMonoDiff)", maxMonoDiff < 1e-6)

        // 2. Hard-panned Left 100 Hz sine -> feeds R with ~4.5 dB separation while preserving L+R sum
        kernel.flush()
        val stereoBuf = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            stereoBuf[i * 2] = 0.4 * sin(2.0 * PI * 100.0 * i / fs)
            stereoBuf[i * 2 + 1] = 0.0
        }
        kernel.processNormalizedFramesInPlace(stereoBuf, frames, config)
        var peakL = 0.0
        var peakR = 0.0
        var maxSumErr = 0.0
        for (i in 2_400 until frames) {
            val dryL = 0.4 * sin(2.0 * PI * 100.0 * i / fs)
            peakL = max(peakL, abs(stereoBuf[i * 2]))
            peakR = max(peakR, abs(stereoBuf[i * 2 + 1]))
            maxSumErr = max(maxSumErr, abs((stereoBuf[i * 2] + stereoBuf[i * 2 + 1]) - dryL))
        }
        assertTrue("BS2B crossfeed must preserve exact L+R mono sum (err=$maxSumErr)", maxSumErr < 1e-6)
        assertTrue("BS2B crossfeed must feed significant low-frequency signal into R (peakR=$peakR)", peakR > 0.12)
        assertTrue("BS2B crossfeed must keep L louder than R (peakL=$peakL, peakR=$peakR)", peakL > peakR * 1.4)
    }

    @Test
    fun `echo kernel float32 path preserves full headroom and exact tap timing`() {
        val echo = EchoKernel()
        val fs = 1_000
        echo.configure(sampleRate = fs, channelCount = 2, useFloat = true)
        echo.sync(
            com.maxrave.domain.data.player.AudioEffects(
                delay = com.maxrave.domain.data.player.DelayEffect(timeMs = 100, feedback = 0.5f, mix = 0.5f),
                reverb = null,
            ),
        )
        assertFalse(echo.isBypassed)

        val outL = FloatArray(300)
        val outR = FloatArray(300)
        echo.beginBuffer()
        for (i in 0 until 300) {
            val inL = if (i == 0) 0.8f else 0f
            val inR = if (i == 0) -0.4f else 0f
            outL[i] = echo.processInterleavedFloat(inL)
            outR[i] = echo.processInterleavedFloat(inR)
        }
        assertTrue("Direct impulse at sample 0 must be non-zero", outL[0] > 0.1f)
        assertTrue("First echo tap at 100 ms (sample 100) must fire on L", outL[100] > 0.05f)
        assertTrue("First echo tap at 100 ms (sample 100) must preserve polarity on R", outR[100] < -0.02f)
        assertEquals("Silence between impulse and first tap", 0f, outL[50], 1e-6f)
    }
}

