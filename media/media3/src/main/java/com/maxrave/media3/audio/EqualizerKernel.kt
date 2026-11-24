package com.maxrave.media3.audio

import com.maxrave.domain.data.player.EQUALIZER_BASS_MONO_HZ
import com.maxrave.domain.data.player.EqualizerConfig
import com.maxrave.domain.data.player.computeNormalizedBiquad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure-Kotlin studio DSP kernel behind [EqualizerAudioProcessor]:
 * 1. Smooth-ramped input preamp / A-B bypass crossfade (10 ms anti-click transition)
 * 2. Cascaded RBJ biquad stages: Subsonic 28 Hz HPF, AutoEq layer (parametric or fixed),
 *    10-band ISO EQ (31 Hz..16 kHz, high-shelf top end), Bass Low-Shelf (70 Hz),
 *    Air High-Shelf (9.5 kHz), and dynamic Fletcher-Munson Loudness shelves
 * 3. Mid/Side Stereo Width stage (> 140 Hz) with LR2 Bass Mono guard (< 140 Hz)
 * 4. Zero-latency stereo-linked fast-attack (0.25 ms) soft-knee True-Peak Limiter
 * 5. TPDF dither on PCM 16-bit output (zero allocation in audio loop)
 *
 * Kept free of Android framework types so unit tests can run directly on JVM without `android.jar`
 * stubs, matching [EchoKernel].
 */
internal class EqualizerKernel {
    private var sampleRate: Int = 0
    private var channelCount: Int = 0

    private var appliedConfig: EqualizerConfig? = null

    /** Current active biquad stages (`stageCount <= MAX_STAGES`), normalized [b0, b1, b2, a1, a2]. */
    private var stageCoeffs = DoubleArray(MAX_STAGES * COEFFS_PER_STAGE)
    private var stageCount = 0

    /** Biquad delay lines: `[x1, x2, y1, y2]` laid out `channel * MAX_STAGES * 4 + stage * 4`. */
    private var stageState = DoubleArray(0)

    /** Bass-mono LR2 inverted high-pass filter coefficients & state on Side (S) channel. */
    private var bassMonoHpCoeffs = DoubleArray(COEFFS_PER_STAGE)
    private var bassMonoX1 = 0.0
    private var bassMonoX2 = 0.0
    private var bassMonoY1 = 0.0
    private var bassMonoY2 = 0.0

    /** Preamp, stereo width & A-B wet/dry smoothing ramps (10 ms). */
    private var currentPreampGain = 1.0
    private var targetPreampGain = 1.0
    private var preampStep = 0.0

    private var currentWet = 0.0
    private var targetWet = 0.0
    private var wetStep = 0.0

    private var currentStereoWidth = 1.0
    private var targetStereoWidth = 1.0
    private var stereoWidthStep = 0.0

    private var bassMonoEnabled = true
    private var limiterEnabled = true
    private var bypassActive = true

    /** Zero-latency fast-attack (0.25 ms) stereo-linked soft-knee limiter state. */
    private var limiterGain = 1.0
    private var limiterAttackCoeff = 0.9
    private var limiterReleaseCoeff = 0.999

    /** Fast deterministic xorshift64 state for TPDF dither (zero allocation). */
    private var rngState: Long = 0x5EED_C0DE_CAFE_BABEL

    fun configure(
        sampleRate: Int,
        channelCount: Int,
    ) {
        if (sampleRate <= 0 || channelCount <= 0) {
            reset()
            return
        }
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        this.stageState = DoubleArray(channelCount * MAX_STAGES * STATE_PER_STAGE)
        this.limiterAttackCoeff = exp(-1.0 / (sampleRate * 0.00025))
        this.limiterReleaseCoeff = exp(-1.0 / (sampleRate * 0.075))
        computeBassMonoHpCoeffs(sampleRate.toDouble())
        flush()
        this.appliedConfig = null
        this.currentWet = 0.0
        this.targetWet = 0.0
        this.bypassActive = true
    }

    fun flush() {
        stageState.fill(0.0)
        bassMonoX1 = 0.0
        bassMonoX2 = 0.0
        bassMonoY1 = 0.0
        bassMonoY2 = 0.0
        limiterGain = 1.0
    }

    fun reset() {
        sampleRate = 0
        channelCount = 0
        appliedConfig = null
        stageCount = 0
        stageState = DoubleArray(0)
        flush()
        currentPreampGain = 1.0
        targetPreampGain = 1.0
        currentWet = 0.0
        targetWet = 0.0
        currentStereoWidth = 1.0
        targetStereoWidth = 1.0
        bypassActive = true
    }

    fun syncConfig(config: EqualizerConfig) {
        if (config === appliedConfig) return
        appliedConfig = config
        if (sampleRate <= 0 || channelCount <= 0) return

        val rampSamples = max(1, (sampleRate * RAMP_SECONDS).roundToInt())
        if (config.isBypass) {
            targetWet = 0.0
            wetStep = (targetWet - currentWet) / rampSamples
            targetPreampGain = 1.0
            preampStep = (targetPreampGain - currentPreampGain) / rampSamples
            targetStereoWidth = 1.0
            stereoWidthStep = (targetStereoWidth - currentStereoWidth) / rampSamples
            if (currentWet <= 1e-6) {
                bypassActive = true
            }
            return
        }

        val wasFullBypass = bypassActive && currentWet <= 1e-6
        bypassActive = false

        val preampDb = config.effectivePreampDb(sampleRate.toDouble())
        targetPreampGain = 10.0.pow(preampDb / 20.0).coerceIn(0.05, 4.0)
        if (wasFullBypass) {
            stageState.fill(0.0)
            bassMonoX1 = 0.0
            bassMonoX2 = 0.0
            bassMonoY1 = 0.0
            bassMonoY2 = 0.0
            limiterGain = 1.0
            currentPreampGain = targetPreampGain
            preampStep = 0.0
            currentStereoWidth = config.safeEnhancements.stereoWidth.toDouble()
            targetStereoWidth = currentStereoWidth
            stereoWidthStep = 0.0
        } else {
            preampStep = (targetPreampGain - currentPreampGain) / rampSamples
            targetStereoWidth = config.safeEnhancements.stereoWidth.toDouble()
            stereoWidthStep = (targetStereoWidth - currentStereoWidth) / rampSamples
        }

        targetWet = 1.0
        wetStep = (targetWet - currentWet) / rampSamples

        val enh = config.safeEnhancements
        bassMonoEnabled = enh.bassMonoEnabled
        limiterEnabled = enh.limiterEnabled

        val stages = config.tonalBiquadStages()
        var written = 0
        for (stage in stages) {
            if (written >= MAX_STAGES) break
            val c = computeNormalizedBiquad(stage, sampleRate.toDouble()) ?: continue
            val base = written * COEFFS_PER_STAGE
            stageCoeffs[base] = c[0]
            stageCoeffs[base + 1] = c[1]
            stageCoeffs[base + 2] = c[2]
            stageCoeffs[base + 3] = c[3]
            stageCoeffs[base + 4] = c[4]
            written++
        }
        // Clear delay lines of any newly freed trailing stages so they cannot leak stale energy later.
        if (written < stageCount) {
            for (ch in 0 until channelCount) {
                val chBase = ch * MAX_STAGES * STATE_PER_STAGE
                stageState.fill(0.0, chBase + written * STATE_PER_STAGE, chBase + stageCount * STATE_PER_STAGE)
            }
        }
        stageCount = written
    }

    /** True when the DSP is fully idle and caller may bulk-copy input bytes untouched. */
    fun canDirectBypass(): Boolean = bypassActive && currentWet <= 1e-6 && targetWet <= 1e-6

    /**
     * Processes [frameCount] interleaved normalized frames in-place from [samples] (size >= frameCount * channelCount).
     * Intended for unit tests and float PCM paths.
     */
    fun processNormalizedFramesInPlace(
        samples: DoubleArray,
        frameCount: Int,
        config: EqualizerConfig,
    ) {
        syncConfig(config)
        if (canDirectBypass() || sampleRate <= 0 || channelCount <= 0) return

        val chans = channelCount
        var idx = 0
        for (frame in 0 until frameCount) {
            stepRamps()
            if (chans == 2) {
                val dryL = samples[idx]
                val dryR = samples[idx + 1]
                processStereoFrame(dryL, dryR)
                samples[idx] = outFrameL
                samples[idx + 1] = outFrameR
            } else {
                var framePeak = 0.0
                for (ch in 0 until chans) {
                    val dry = samples[idx + ch]
                    val filtered = runBiquads(dry * currentPreampGain, ch)
                    val blended = dry * (1.0 - currentWet) + filtered * currentWet
                    val clean = if (blended.isFinite()) blended else dry
                    samples[idx + ch] = clean
                    framePeak = max(framePeak, abs(clean))
                }
                if (limiterEnabled) {
                    val gain = stepLimiter(framePeak)
                    for (ch in 0 until chans) {
                        samples[idx + ch] = softCeilingGuard(samples[idx + ch] * gain)
                    }
                }
            }
            idx += chans
        }
        maybeEnterIdleBypass()
    }

    /** Processes an interleaved PCM 16-bit short buffer directly into [outShorts]. */
    fun processPcm16(
        inShorts: ShortArray,
        outShorts: ShortArray,
        frameCount: Int,
        config: EqualizerConfig,
    ) {
        syncConfig(config)
        val total = frameCount * channelCount
        if (canDirectBypass() || sampleRate <= 0 || channelCount <= 0) {
            inShorts.copyInto(outShorts, 0, 0, total)
            return
        }

        val chans = channelCount
        var idx = 0
        for (frame in 0 until frameCount) {
            stepRamps()
            if (chans == 2) {
                val dryL = inShorts[idx] / PCM16_SCALE
                val dryR = inShorts[idx + 1] / PCM16_SCALE
                processStereoFrame(dryL, dryR)
                outShorts[idx] = quantizePcm16WithTpdf(outFrameL)
                outShorts[idx + 1] = quantizePcm16WithTpdf(outFrameR)
            } else {
                var framePeak = 0.0
                var firstLimited = 0.0
                if (chans == 1) {
                    val dry = inShorts[idx] / PCM16_SCALE
                    val filtered = runBiquads(dry * currentPreampGain, 0)
                    val blended = dry * (1.0 - currentWet) + filtered * currentWet
                    val clean = if (blended.isFinite()) blended else dry
                    firstLimited =
                        if (limiterEnabled) {
                            softCeilingGuard(clean * stepLimiter(abs(clean)))
                        } else {
                            clean
                        }
                    outShorts[idx] = quantizePcm16WithTpdf(firstLimited)
                } else {
                    // >2 channels (rare surround PCM): compute linked frame gain without extra allocations.
                    for (ch in 0 until chans) {
                        val dry = inShorts[idx + ch] / PCM16_SCALE
                        val filtered = runBiquads(dry * currentPreampGain, ch)
                        val blended = dry * (1.0 - currentWet) + filtered * currentWet
                        val clean = if (blended.isFinite()) blended else dry
                        framePeak = max(framePeak, abs(clean))
                        outShorts[idx + ch] = quantizePcm16WithTpdf(clean)
                    }
                    if (limiterEnabled && framePeak > LIMITER_THRESHOLD) {
                        val gain = stepLimiter(framePeak)
                        for (ch in 0 until chans) {
                            val dry = inShorts[idx + ch] / PCM16_SCALE
                            val filtered = runBiquads(dry * currentPreampGain, ch)
                            val blended = dry * (1.0 - currentWet) + filtered * currentWet
                            outShorts[idx + ch] = quantizePcm16WithTpdf(softCeilingGuard(blended * gain))
                        }
                    } else if (limiterEnabled) {
                        stepLimiter(framePeak)
                    }
                }
            }
            idx += chans
        }
        maybeEnterIdleBypass()
    }

    /** Single stereo sample helper for frame-by-frame processing without heap allocation. */
    var outFrameL: Double = 0.0
        private set
    var outFrameR: Double = 0.0
        private set

    fun stepRampsAndProcessStereo(
        dryL: Double,
        dryR: Double,
    ) {
        stepRamps()
        processStereoFrame(dryL, dryR)
    }

    fun stepRampsAndProcessSingleChannel(
        dry: Double,
        channel: Int,
    ): Double {
        if (channel == 0) stepRamps()
        val safeDry = if (dry.isFinite()) dry else 0.0
        val filtered = runBiquads(safeDry * currentPreampGain, channel)
        val blended = safeDry * (1.0 - currentWet) + filtered * currentWet
        val clean = if (blended.isFinite()) blended else safeDry
        return if (limiterEnabled) {
            softCeilingGuard(clean * stepLimiter(abs(clean)))
        } else {
            clean
        }
    }

    fun quantizeSampleToPcm16(normSample: Double): Short = quantizePcm16WithTpdf(normSample)

    fun finishBufferPass() {
        maybeEnterIdleBypass()
    }

    private fun maybeEnterIdleBypass() {
        if (targetWet <= 1e-6 && currentWet <= 1e-6) {
            currentWet = 0.0
            bypassActive = true
            stageState.fill(0.0)
            bassMonoX1 = 0.0
            bassMonoX2 = 0.0
            bassMonoY1 = 0.0
            bassMonoY2 = 0.0
        }
    }

    private fun stepRamps() {
        if (preampStep != 0.0) {
            currentPreampGain += preampStep
            if ((preampStep > 0.0 && currentPreampGain >= targetPreampGain) ||
                (preampStep < 0.0 && currentPreampGain <= targetPreampGain)
            ) {
                currentPreampGain = targetPreampGain
                preampStep = 0.0
            }
        }
        if (wetStep != 0.0) {
            currentWet += wetStep
            if ((wetStep > 0.0 && currentWet >= targetWet) ||
                (wetStep < 0.0 && currentWet <= targetWet)
            ) {
                currentWet = targetWet
                wetStep = 0.0
            }
        }
        if (stereoWidthStep != 0.0) {
            currentStereoWidth += stereoWidthStep
            if ((stereoWidthStep > 0.0 && currentStereoWidth >= targetStereoWidth) ||
                (stereoWidthStep < 0.0 && currentStereoWidth <= targetStereoWidth)
            ) {
                currentStereoWidth = targetStereoWidth
                stereoWidthStep = 0.0
            }
        }
    }

    private fun processStereoFrame(
        dryL: Double,
        dryR: Double,
    ) {
        val safeL = if (dryL.isFinite()) dryL else 0.0
        val safeR = if (dryR.isFinite()) dryR else 0.0

        var eqL = runBiquads(safeL * currentPreampGain, 0)
        var eqR = runBiquads(safeR * currentPreampGain, 1)

        // Stereo width M/S + Bass Mono (< 140 Hz)
        if (abs(currentStereoWidth - 1.0) > 0.002) {
            val mid = 0.5 * (eqL + eqR)
            val side = 0.5 * (eqL - eqR)
            val shapedSide =
                if (bassMonoEnabled) {
                    val hpSide =
                        bassMonoHpCoeffs[0] * side +
                            bassMonoHpCoeffs[1] * bassMonoX1 +
                            bassMonoHpCoeffs[2] * bassMonoX2 -
                            bassMonoHpCoeffs[3] * bassMonoY1 -
                            bassMonoHpCoeffs[4] * bassMonoY2
                    bassMonoX2 = bassMonoX1
                    bassMonoX1 = side
                    bassMonoY2 = bassMonoY1
                    bassMonoY1 = hpSide
                    hpSide * currentStereoWidth
                } else {
                    side * currentStereoWidth
                }
            eqL = mid + shapedSide
            eqR = mid - shapedSide
        }

        val wet = currentWet
        val dryWeight = 1.0 - wet
        val preLimL = dryWeight * safeL + wet * eqL
        val preLimR = dryWeight * safeR + wet * eqR

        if (!limiterEnabled) {
            outFrameL = if (preLimL.isFinite()) preLimL else safeL
            outFrameR = if (preLimR.isFinite()) preLimR else safeR
            return
        }

        val framePeak = max(abs(preLimL), abs(preLimR))
        val gain = stepLimiter(framePeak)
        val limL = softCeilingGuard(preLimL * gain)
        val limR = softCeilingGuard(preLimR * gain)
        outFrameL = if (limL.isFinite()) limL else 0.0
        outFrameR = if (limR.isFinite()) limR else 0.0
    }

    private fun stepLimiter(framePeak: Double): Double {
        val targetGain =
            if (framePeak <= LIMITER_THRESHOLD) {
                1.0
            } else {
                val headroom = LIMITER_CEILING - LIMITER_THRESHOLD
                val compressed = LIMITER_THRESHOLD + headroom * tanh((framePeak - LIMITER_THRESHOLD) / headroom)
                (compressed / framePeak).coerceIn(0.05, 1.0)
            }
        limiterGain =
            if (targetGain < limiterGain) {
                limiterAttackCoeff * limiterGain + (1.0 - limiterAttackCoeff) * targetGain
            } else {
                limiterReleaseCoeff * limiterGain + (1.0 - limiterReleaseCoeff) * targetGain
            }
        return limiterGain
    }

    private fun runBiquads(
        input: Double,
        channel: Int,
    ): Double {
        var sample = input
        var c = 0
        var s = channel * MAX_STAGES * STATE_PER_STAGE
        val limit = stageCount * COEFFS_PER_STAGE
        while (c < limit) {
            val x1 = stageState[s]
            val x2 = stageState[s + 1]
            val y1 = stageState[s + 2]
            val y2 = stageState[s + 3]

            val out =
                stageCoeffs[c] * sample +
                    stageCoeffs[c + 1] * x1 +
                    stageCoeffs[c + 2] * x2 -
                    stageCoeffs[c + 3] * y1 -
                    stageCoeffs[c + 4] * y2

            stageState[s] = sample
            stageState[s + 1] = x1
            stageState[s + 2] = out
            stageState[s + 3] = y1

            sample = out
            c += COEFFS_PER_STAGE
            s += STATE_PER_STAGE
        }
        return sample
    }

    private fun softCeilingGuard(sample: Double): Double {
        if (!sample.isFinite()) return 0.0
        val a = abs(sample)
        if (a <= LIMITER_SOFT_START) return sample
        val excess = a - LIMITER_SOFT_START
        val span = LIMITER_HARD_LIMIT - LIMITER_SOFT_START
        val guarded = LIMITER_SOFT_START + span * tanh(excess / span)
        return if (sample >= 0.0) guarded else -guarded
    }

    /** Generates triangular probability density function (TPDF) dither ±1 LSB around the rounding boundary. */
    private fun quantizePcm16WithTpdf(normSample: Double): Short {
        val u1 = nextUnitFloat()
        val u2 = nextUnitFloat()
        val dither = (u1 - u2) * PCM16_LSB
        val scaled = (normSample + dither) * PCM16_MAX
        return scaled.roundToInt().coerceIn(-32_768, 32_767).toShort()
    }

    private fun nextUnitFloat(): Double {
        var x = rngState
        x = x xor (x shl 13)
        x = x xor (x ushr 7)
        x = x xor (x shl 17)
        rngState = x
        return ((x ushr 40).toDouble()) / 16_777_216.0
    }

    private fun computeBassMonoHpCoeffs(fs: Double) {
        val w0 = 2.0 * PI * EQUALIZER_BASS_MONO_HZ / fs
        val cosW0 = cos(w0)
        val sinW0 = sin(w0)
        // Inverted LR2 high-pass (Q = 0.5) matches LPF with < 1e-12 magnitude sum error.
        val alpha = sinW0 / (2.0 * 0.5)
        val a0 = 1.0 + alpha
        val v = -(1.0 + cosW0) / 2.0
        bassMonoHpCoeffs[0] = v / a0
        bassMonoHpCoeffs[1] = (1.0 + cosW0) / a0
        bassMonoHpCoeffs[2] = v / a0
        bassMonoHpCoeffs[3] = (-2.0 * cosW0) / a0
        bassMonoHpCoeffs[4] = (1.0 - alpha) / a0
    }

    companion object {
        private const val MAX_STAGES = 30
        private const val COEFFS_PER_STAGE = 5
        private const val STATE_PER_STAGE = 4
        private const val RAMP_SECONDS = 0.010

        private const val LIMITER_THRESHOLD = 0.80 // -1.94 dBFS soft-knee start
        private const val LIMITER_CEILING = 0.96   // -0.35 dBFS compressor target ceiling
        private const val LIMITER_SOFT_START = 0.96
        private const val LIMITER_HARD_LIMIT = 0.995

        private const val PCM16_SCALE = 32_768.0
        private const val PCM16_MAX = 32_767.0
        private const val PCM16_LSB = 1.0 / 32_768.0
    }
}
