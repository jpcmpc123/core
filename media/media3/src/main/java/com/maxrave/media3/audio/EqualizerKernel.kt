package com.maxrave.media3.audio

import com.maxrave.domain.data.player.BiquadFilterKind
import com.maxrave.domain.data.player.EQUALIZER_BASS_MONO_HZ
import com.maxrave.domain.data.player.EQUALIZER_BASS_SHELF_HZ
import com.maxrave.domain.data.player.EQUALIZER_ISO_BANDS_HZ
import com.maxrave.domain.data.player.EQUALIZER_ISO_Q
import com.maxrave.domain.data.player.EQUALIZER_LOUDNESS_HIGH_HZ
import com.maxrave.domain.data.player.EQUALIZER_LOUDNESS_LOW_HZ
import com.maxrave.domain.data.player.EQUALIZER_SUBSONIC_HZ
import com.maxrave.domain.data.player.EQUALIZER_TREBLE_SHELF_HZ
import com.maxrave.domain.data.player.EqualizerConfig
import com.maxrave.domain.data.player.ParametricBand
import com.maxrave.domain.data.player.computeNormalizedBiquad
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure-Kotlin studio DSP kernel behind [EqualizerAudioProcessor]:
 * 1. Smooth-ramped input preamp / A-B bypass crossfade (10 ms anti-click transition)
 * 2. Fixed-slot Transposed Direct Form II (TDF-II) RBJ biquad cascade with 10 ms per-coefficient
 *    interpolation so crossing 0 dB or dragging treble sliders never shifts stage state or produces
 *    zipper noise
 * 3. Mid/Side Stereo Width stage (> 100 Hz) with LR2 Bass Mono guard (< 100 Hz) and open
 *    early-reflection Spatial Stage
 * 4. 2 ms Lookahead True-Peak Limiter (-0.8 dBFS ceiling, O(1) monotonic deque, zero heap
 *    allocations) eliminating high-frequency hard clipping, aliasing, and inter-sample peaks
 * 5. TPDF dither on PCM 16-bit output (zero allocation in audio loop)
 *
 * Kept free of Android framework types so unit tests can run directly on JVM without `android.jar`
 * stubs, matching [EchoKernel].
 */
internal class EqualizerKernel {
    private var sampleRate: Int = 0
    private var channelCount: Int = 0

    private var appliedConfig: EqualizerConfig? = null

    /**
     * Fixed-slot biquad coefficients (`TOTAL_SLOTS = 31`), normalized `[b0, b1, b2, a1, a2]`:
     * - `[0]`: Subsonic 28 Hz HPF
     * - `[1..10]`: 10-band ISO EQ
     * - `[11]`: Bass low-shelf (70 Hz)
     * - `[12]`: Treble high-shelf (6 kHz)
     * - `[13]`: Loudness low-shelf (85 Hz)
     * - `[14]`: Loudness high-shelf (10 kHz)
     * - `[15..30]`: AutoEq layer (up to 16 bands)
     */
    private var currCoeffs = DoubleArray(TOTAL_SLOTS * COEFFS_PER_STAGE)
    private var targetCoeffs = DoubleArray(TOTAL_SLOTS * COEFFS_PER_STAGE)
    private var stepCoeffs = DoubleArray(TOTAL_SLOTS * COEFFS_PER_STAGE)
    private var activeMask: Int = 0
    private var rampingMask: Int = 0
    private var coeffRampRemaining: Int = 0

    /** TDF-II delay lines `[z1, z2]` laid out `channel * TOTAL_SLOTS * 2 + stage * 2`. */
    private var stageState = DoubleArray(0)

    /** Bass-mono LR2 TDF-II high-pass filter coefficients & state on Side (S) channel. */
    private var bassMonoHpCoeffs = DoubleArray(COEFFS_PER_STAGE)
    private var bassMonoZ1 = 0.0
    private var bassMonoZ2 = 0.0

    /** Preamp, stereo width, spatial stage & A-B wet/dry smoothing ramps (10 ms). */
    private var currentPreampGain = 1.0
    private var targetPreampGain = 1.0
    private var preampStep = 0.0

    private var currentWet = 0.0
    private var targetWet = 0.0
    private var wetStep = 0.0

    private var currentStereoWidth = 1.0
    private var targetStereoWidth = 1.0
    private var stereoWidthStep = 0.0

    private var currentSpatialStage = 0.0
    private var targetSpatialStage = 0.0
    private var spatialStageStep = 0.0

    /** Pre-allocated early-reflection delay lines for Wavelet-style open spatial stage (L/R). */
    private var spatialDelayL = DoubleArray(0)
    private var spatialDelayR = DoubleArray(0)
    private var spatialWriteIdx = 0
    private var spatialTapL1 = 1
    private var spatialTapL2 = 1
    private var spatialTapL3 = 1
    private var spatialTapL4 = 1
    private var spatialTapR1 = 1
    private var spatialTapR2 = 1
    private var spatialTapR3 = 1
    private var spatialTapR4 = 1
    private var spatialHpfAlpha = 0.96
    private var spatialLpfAlpha = 0.55
    private var spatialSendPrevInL = 0.0
    private var spatialSendPrevInR = 0.0
    private var spatialSendHpfL = 0.0
    private var spatialSendHpfR = 0.0
    private var spatialSendLpfL = 0.0
    private var spatialSendLpfR = 0.0

    private var bassMonoEnabled = true
    private var limiterEnabled = true
    private var bypassActive = true

    /** 2 ms Lookahead True-Peak Limiter with O(1) amortized monotonic peak deque. */
    private var lookaheadSamples = 1
    private var lookDelayL = DoubleArray(0)
    private var lookDelayR = DoubleArray(0)
    private var lookWriteIdx = 0
    private var dequeSeq = LongArray(0)
    private var dequeVal = DoubleArray(0)
    private var dequeCap = 2
    private var dequeHead = 0
    private var dequeTail = 0
    private var dequeCount = 0
    private var lookSampleSeq = 0L

    private var limiterGain = 1.0
    private var limiterAttackCoeff = 0.9
    private var limiterReleaseCoeff = 0.999

    /** Fast deterministic xorshift64 state for TPDF dither (zero allocation). */
    private var rngState: Long = 0x5EED_C0DE_CAFE_BABEL

    init {
        resetCoeffArraysToIdentity()
    }

    private fun resetCoeffArraysToIdentity() {
        for (slot in 0 until TOTAL_SLOTS) {
            val base = slot * COEFFS_PER_STAGE
            currCoeffs[base] = 1.0
            currCoeffs[base + 1] = 0.0
            currCoeffs[base + 2] = 0.0
            currCoeffs[base + 3] = 0.0
            currCoeffs[base + 4] = 0.0

            targetCoeffs[base] = 1.0
            targetCoeffs[base + 1] = 0.0
            targetCoeffs[base + 2] = 0.0
            targetCoeffs[base + 3] = 0.0
            targetCoeffs[base + 4] = 0.0

            stepCoeffs[base] = 0.0
            stepCoeffs[base + 1] = 0.0
            stepCoeffs[base + 2] = 0.0
            stepCoeffs[base + 3] = 0.0
            stepCoeffs[base + 4] = 0.0
        }
        activeMask = 0
        rampingMask = 0
        coeffRampRemaining = 0
    }

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
        this.stageState = DoubleArray(channelCount * TOTAL_SLOTS * STATE_PER_STAGE)

        // 0.45 ms smooth attack inside the 2 ms lookahead reaches ~98.8% before the peak exits.
        this.limiterAttackCoeff = exp(-1.0 / (sampleRate * 0.00045))
        this.limiterReleaseCoeff = exp(-1.0 / (sampleRate * 0.065))

        val lookSamples = max(1, (sampleRate * LOOKAHEAD_SECONDS).roundToInt())
        this.lookaheadSamples = lookSamples
        this.dequeCap = lookSamples + 2
        if (channelCount == 2) {
            this.lookDelayL = DoubleArray(lookSamples)
            this.lookDelayR = DoubleArray(lookSamples)
            this.dequeSeq = LongArray(this.dequeCap)
            this.dequeVal = DoubleArray(this.dequeCap)
        } else {
            this.lookDelayL = DoubleArray(0)
            this.lookDelayR = DoubleArray(0)
            this.dequeSeq = LongArray(0)
            this.dequeVal = DoubleArray(0)
        }

        val delayLen = max(64, (sampleRate * 0.032).roundToInt())
        if (channelCount == 2) {
            this.spatialDelayL = DoubleArray(delayLen)
            this.spatialDelayR = DoubleArray(delayLen)
            this.spatialTapL1 = max(1, (sampleRate * 0.0073).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapL2 = max(1, (sampleRate * 0.0137).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapL3 = max(1, (sampleRate * 0.0191).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapL4 = max(1, (sampleRate * 0.0247).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapR1 = max(1, (sampleRate * 0.0089).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapR2 = max(1, (sampleRate * 0.0153).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapR3 = max(1, (sampleRate * 0.0217).roundToInt()).coerceAtMost(delayLen - 1)
            this.spatialTapR4 = max(1, (sampleRate * 0.0271).roundToInt()).coerceAtMost(delayLen - 1)
        } else {
            this.spatialDelayL = DoubleArray(0)
            this.spatialDelayR = DoubleArray(0)
        }
        val dt = 1.0 / sampleRate
        val hpfRc = 1.0 / (2.0 * PI * 240.0)
        val lpfRc = 1.0 / (2.0 * PI * 6_500.0)
        this.spatialHpfAlpha = hpfRc / (hpfRc + dt)
        this.spatialLpfAlpha = dt / (lpfRc + dt)
        computeBassMonoHpCoeffs(sampleRate.toDouble())
        resetCoeffArraysToIdentity()
        flush()
        this.appliedConfig = null
        this.currentWet = 0.0
        this.targetWet = 0.0
        this.bypassActive = true
    }

    fun flush() {
        stageState.fill(0.0)
        bassMonoZ1 = 0.0
        bassMonoZ2 = 0.0
        spatialDelayL.fill(0.0)
        spatialDelayR.fill(0.0)
        spatialWriteIdx = 0
        spatialSendPrevInL = 0.0
        spatialSendPrevInR = 0.0
        spatialSendHpfL = 0.0
        spatialSendHpfR = 0.0
        spatialSendLpfL = 0.0
        spatialSendLpfR = 0.0
        lookDelayL.fill(0.0)
        lookDelayR.fill(0.0)
        lookWriteIdx = 0
        dequeHead = 0
        dequeTail = 0
        dequeCount = 0
        lookSampleSeq = 0L
        limiterGain = 1.0
    }

    fun reset() {
        sampleRate = 0
        channelCount = 0
        appliedConfig = null
        stageState = DoubleArray(0)
        spatialDelayL = DoubleArray(0)
        spatialDelayR = DoubleArray(0)
        lookDelayL = DoubleArray(0)
        lookDelayR = DoubleArray(0)
        dequeSeq = LongArray(0)
        dequeVal = DoubleArray(0)
        resetCoeffArraysToIdentity()
        flush()
        currentPreampGain = 1.0
        targetPreampGain = 1.0
        currentWet = 0.0
        targetWet = 0.0
        currentStereoWidth = 1.0
        targetStereoWidth = 1.0
        currentSpatialStage = 0.0
        targetSpatialStage = 0.0
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
            targetSpatialStage = 0.0
            spatialStageStep = (targetSpatialStage - currentSpatialStage) / rampSamples
            if (currentWet <= 1e-6) {
                bypassActive = true
            }
            return
        }

        val wasFullBypass = bypassActive && currentWet <= 1e-6
        bypassActive = false

        val fs = sampleRate.toDouble()
        val preampDb = config.effectivePreampDb(fs)
        targetPreampGain = 10.0.pow(preampDb / 20.0).coerceIn(0.05, 4.0)
        if (wasFullBypass) {
            flush()
            resetCoeffArraysToIdentity()
            currentPreampGain = targetPreampGain
            preampStep = 0.0
            currentStereoWidth = config.safeEnhancements.stereoWidth.toDouble()
            targetStereoWidth = currentStereoWidth
            stereoWidthStep = 0.0
            currentSpatialStage = config.safeEnhancements.spatialStage.toDouble()
            targetSpatialStage = currentSpatialStage
            spatialStageStep = 0.0
        } else {
            preampStep = (targetPreampGain - currentPreampGain) / rampSamples
            targetStereoWidth = config.safeEnhancements.stereoWidth.toDouble()
            stereoWidthStep = (targetStereoWidth - currentStereoWidth) / rampSamples
            targetSpatialStage = config.safeEnhancements.spatialStage.toDouble()
            spatialStageStep = (targetSpatialStage - currentSpatialStage) / rampSamples
        }

        targetWet = 1.0
        wetStep = (targetWet - currentWet) / rampSamples

        val enh = config.safeEnhancements
        bassMonoEnabled = enh.bassMonoEnabled
        limiterEnabled = enh.limiterEnabled

        populateTargetCoeffs(config, fs)
        if (wasFullBypass) {
            applyTargetCoeffsImmediately()
        } else {
            scheduleCoeffRamp(rampSamples)
        }
    }

    private fun setSlotIdentityTarget(slot: Int) {
        val base = slot * COEFFS_PER_STAGE
        targetCoeffs[base] = 1.0
        targetCoeffs[base + 1] = 0.0
        targetCoeffs[base + 2] = 0.0
        targetCoeffs[base + 3] = 0.0
        targetCoeffs[base + 4] = 0.0
    }

    private fun setSlotBandTarget(
        slot: Int,
        band: ParametricBand?,
        fs: Double,
    ) {
        if (band == null) {
            setSlotIdentityTarget(slot)
            return
        }
        val c = computeNormalizedBiquad(band, fs)
        if (c == null) {
            setSlotIdentityTarget(slot)
            return
        }
        val base = slot * COEFFS_PER_STAGE
        targetCoeffs[base] = c[0]
        targetCoeffs[base + 1] = c[1]
        targetCoeffs[base + 2] = c[2]
        targetCoeffs[base + 3] = c[3]
        targetCoeffs[base + 4] = c[4]
    }

    private fun populateTargetCoeffs(
        config: EqualizerConfig,
        fs: Double,
    ) {
        val bands = config.normalizedBandsDb
        val enh = config.safeEnhancements
        val autoLayer = config.safeAutoEqLayer?.takeIf { it.enabled }

        // Slot 0: Subsonic HPF
        val hasBassGain =
            enh.bassBoostDb > 0.1f ||
                enh.loudnessAmount > 0.05f ||
                bands.take(3).any { it > 0.5f }
        if (enh.subsonicFilterEnabled && hasBassGain) {
            setSlotBandTarget(
                SLOT_SUBSONIC,
                ParametricBand(BiquadFilterKind.HIGH_PASS, EQUALIZER_SUBSONIC_HZ.toFloat(), 0f, 0.7071f),
                fs,
            )
        } else {
            setSlotIdentityTarget(SLOT_SUBSONIC)
        }

        // Slots 1..10: 10-band ISO EQ at fixed slot indices
        for (i in EQUALIZER_ISO_BANDS_HZ.indices) {
            val gain = bands[i]
            if (abs(gain) > 0.01f) {
                val hz = EQUALIZER_ISO_BANDS_HZ[i].toFloat()
                val kind = if (i == EQUALIZER_ISO_BANDS_HZ.lastIndex) BiquadFilterKind.HIGH_SHELF else BiquadFilterKind.PEAKING
                val q = if (kind == BiquadFilterKind.HIGH_SHELF) 0.7071f else EQUALIZER_ISO_Q.toFloat()
                setSlotBandTarget(SLOT_ISO_BASE + i, ParametricBand(kind, hz, gain, q), fs)
            } else {
                setSlotIdentityTarget(SLOT_ISO_BASE + i)
            }
        }

        // Slot 11: Bass low-shelf
        if (enh.bassBoostDb > 0.01f) {
            setSlotBandTarget(
                SLOT_BASS_SHELF,
                ParametricBand(BiquadFilterKind.LOW_SHELF, EQUALIZER_BASS_SHELF_HZ.toFloat(), enh.bassBoostDb, 0.7071f),
                fs,
            )
        } else {
            setSlotIdentityTarget(SLOT_BASS_SHELF)
        }

        // Slot 12: Treble high-shelf
        if (abs(enh.trebleBoostDb) > 0.01f) {
            setSlotBandTarget(
                SLOT_TREBLE_SHELF,
                ParametricBand(BiquadFilterKind.HIGH_SHELF, EQUALIZER_TREBLE_SHELF_HZ.toFloat(), enh.trebleBoostDb, 0.7071f),
                fs,
            )
        } else {
            setSlotIdentityTarget(SLOT_TREBLE_SHELF)
        }

        // Slots 13..14: Fletcher-Munson dynamic loudness shelves
        if (enh.loudnessAmount > 0.01f) {
            val amt = enh.loudnessAmount
            setSlotBandTarget(
                SLOT_LOUDNESS_LOW,
                ParametricBand(BiquadFilterKind.LOW_SHELF, EQUALIZER_LOUDNESS_LOW_HZ.toFloat(), 4.5f * amt, 0.65f),
                fs,
            )
            setSlotBandTarget(
                SLOT_LOUDNESS_HIGH,
                ParametricBand(BiquadFilterKind.HIGH_SHELF, EQUALIZER_LOUDNESS_HIGH_HZ.toFloat(), 2.5f * amt, 0.65f),
                fs,
            )
        } else {
            setSlotIdentityTarget(SLOT_LOUDNESS_LOW)
            setSlotIdentityTarget(SLOT_LOUDNESS_HIGH)
        }

        // Slots 15..30: AutoEq correction layer
        val autoBands = autoLayer?.bands.orEmpty()
        for (i in 0 until AUTOEQ_SLOTS) {
            val b = autoBands.getOrNull(i)?.takeIf { abs(it.gainDb) > 0.01f }
            setSlotBandTarget(SLOT_AUTOEQ_BASE + i, b, fs)
        }
    }

    private fun isSlotIdentity(coeffs: DoubleArray, base: Int): Boolean =
        abs(coeffs[base] - 1.0) <= 1e-12 &&
            abs(coeffs[base + 1]) <= 1e-12 &&
            abs(coeffs[base + 2]) <= 1e-12 &&
            abs(coeffs[base + 3]) <= 1e-12 &&
            abs(coeffs[base + 4]) <= 1e-12

    private fun applyTargetCoeffsImmediately() {
        var mask = 0
        for (slot in 0 until TOTAL_SLOTS) {
            val base = slot * COEFFS_PER_STAGE
            for (k in 0 until COEFFS_PER_STAGE) {
                currCoeffs[base + k] = targetCoeffs[base + k]
                stepCoeffs[base + k] = 0.0
            }
            if (!isSlotIdentity(targetCoeffs, base)) {
                mask = mask or (1 shl slot)
            }
        }
        activeMask = mask
        rampingMask = 0
        coeffRampRemaining = 0
    }

    private fun scheduleCoeffRamp(rampSamples: Int) {
        val invRamp = 1.0 / rampSamples
        var nextRampMask = 0
        var nextActiveMask = activeMask
        for (slot in 0 until TOTAL_SLOTS) {
            val base = slot * COEFFS_PER_STAGE
            var changed = false
            for (k in 0 until COEFFS_PER_STAGE) {
                val diff = targetCoeffs[base + k] - currCoeffs[base + k]
                stepCoeffs[base + k] = diff * invRamp
                if (abs(diff) > 1e-11) {
                    changed = true
                }
            }
            val bit = 1 shl slot
            if (changed) {
                nextRampMask = nextRampMask or bit
                nextActiveMask = nextActiveMask or bit
            } else if (!isSlotIdentity(targetCoeffs, base)) {
                nextActiveMask = nextActiveMask or bit
            }
        }
        rampingMask = nextRampMask
        activeMask = nextActiveMask
        coeffRampRemaining = if (nextRampMask != 0) rampSamples else 0
    }

    private fun stepCoeffRamps() {
        val rem = coeffRampRemaining
        if (rem <= 0) return
        val nextRem = rem - 1
        coeffRampRemaining = nextRem

        if (nextRem > 0) {
            var mask = rampingMask
            while (mask != 0) {
                val slot = mask.countTrailingZeroBits()
                val base = slot * COEFFS_PER_STAGE
                currCoeffs[base] += stepCoeffs[base]
                currCoeffs[base + 1] += stepCoeffs[base + 1]
                currCoeffs[base + 2] += stepCoeffs[base + 2]
                currCoeffs[base + 3] += stepCoeffs[base + 3]
                currCoeffs[base + 4] += stepCoeffs[base + 4]
                mask = mask and (mask - 1)
            }
        } else {
            // Ramp finished: snap to exact target coefficients and clear any slot that returned to identity.
            var mask = rampingMask
            var nextActive = activeMask
            while (mask != 0) {
                val slot = mask.countTrailingZeroBits()
                val base = slot * COEFFS_PER_STAGE
                for (k in 0 until COEFFS_PER_STAGE) {
                    currCoeffs[base + k] = targetCoeffs[base + k]
                    stepCoeffs[base + k] = 0.0
                }
                if (isSlotIdentity(targetCoeffs, base)) {
                    nextActive = nextActive and (1 shl slot).inv()
                    for (ch in 0 until channelCount) {
                        val s = ch * TOTAL_SLOTS * STATE_PER_STAGE + slot * STATE_PER_STAGE
                        stageState[s] = 0.0
                        stageState[s + 1] = 0.0
                    }
                }
                mask = mask and (mask - 1)
            }
            activeMask = nextActive
            rampingMask = 0
        }
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
                    val gain = stepSingleChannelLimiter(framePeak)
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
                if (chans == 1) {
                    val dry = inShorts[idx] / PCM16_SCALE
                    val filtered = runBiquads(dry * currentPreampGain, 0)
                    val blended = dry * (1.0 - currentWet) + filtered * currentWet
                    val clean = if (blended.isFinite()) blended else dry
                    val limited =
                        if (limiterEnabled) {
                            softCeilingGuard(clean * stepSingleChannelLimiter(abs(clean)))
                        } else {
                            clean
                        }
                    outShorts[idx] = quantizePcm16WithTpdf(limited)
                } else {
                    for (ch in 0 until chans) {
                        val dry = inShorts[idx + ch] / PCM16_SCALE
                        val filtered = runBiquads(dry * currentPreampGain, ch)
                        val blended = dry * (1.0 - currentWet) + filtered * currentWet
                        val clean = if (blended.isFinite()) blended else dry
                        framePeak = max(framePeak, abs(clean))
                        outShorts[idx + ch] = quantizePcm16WithTpdf(clean)
                    }
                    if (limiterEnabled) {
                        val gain = stepSingleChannelLimiter(framePeak)
                        if (framePeak > LIMITER_THRESHOLD) {
                            for (ch in 0 until chans) {
                                val norm = outShorts[idx + ch] / PCM16_SCALE
                                outShorts[idx + ch] = quantizePcm16WithTpdf(softCeilingGuard(norm * gain))
                            }
                        }
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
            softCeilingGuard(clean * stepSingleChannelLimiter(abs(clean)))
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
            flush()
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
        if (spatialStageStep != 0.0) {
            currentSpatialStage += spatialStageStep
            if ((spatialStageStep > 0.0 && currentSpatialStage >= targetSpatialStage) ||
                (spatialStageStep < 0.0 && currentSpatialStage <= targetSpatialStage)
            ) {
                currentSpatialStage = targetSpatialStage
                spatialStageStep = 0.0
            }
        }
        if (coeffRampRemaining > 0) {
            stepCoeffRamps()
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

        // Stereo width M/S + Bass Mono (< 100 Hz Butterworth TDF-II, unity phase polarity)
        if (abs(currentStereoWidth - 1.0) > 0.002) {
            val mid = 0.5 * (eqL + eqR)
            val side = 0.5 * (eqL - eqR)
            val shapedSide =
                if (bassMonoEnabled) {
                    val hpSide = bassMonoHpCoeffs[0] * side + bassMonoZ1
                    bassMonoZ1 = bassMonoHpCoeffs[1] * side - bassMonoHpCoeffs[3] * hpSide + bassMonoZ2
                    bassMonoZ2 = bassMonoHpCoeffs[2] * side - bassMonoHpCoeffs[4] * hpSide
                    hpSide * currentStereoWidth
                } else {
                    side * currentStereoWidth
                }
            eqL = mid + shapedSide
            eqR = mid - shapedSide
        }

        // Spatial Stage: Wavelet-style open early reflections (band-limited 240 Hz..6.5 kHz, zero echo)
        val delayLen = spatialDelayL.size
        if (delayLen > 0 && (currentSpatialStage > 0.002 || targetSpatialStage > 0.002)) {
            val hpfL = spatialHpfAlpha * (spatialSendHpfL + eqL - spatialSendPrevInL)
            spatialSendPrevInL = eqL
            spatialSendHpfL = hpfL
            val sendL = spatialSendLpfL + spatialLpfAlpha * (hpfL - spatialSendLpfL)
            spatialSendLpfL = sendL

            val hpfR = spatialHpfAlpha * (spatialSendHpfR + eqR - spatialSendPrevInR)
            spatialSendPrevInR = eqR
            spatialSendHpfR = hpfR
            val sendR = spatialSendLpfR + spatialLpfAlpha * (hpfR - spatialSendLpfR)
            spatialSendLpfR = sendR

            val erL =
                0.38 * readSpatialDelay(spatialDelayR, spatialTapL1) -
                    0.28 * readSpatialDelay(spatialDelayL, spatialTapL2) +
                    0.20 * readSpatialDelay(spatialDelayR, spatialTapL3) -
                    0.14 * readSpatialDelay(spatialDelayL, spatialTapL4)
            val erR =
                0.38 * readSpatialDelay(spatialDelayL, spatialTapR1) -
                    0.28 * readSpatialDelay(spatialDelayR, spatialTapR2) +
                    0.20 * readSpatialDelay(spatialDelayL, spatialTapR3) -
                    0.14 * readSpatialDelay(spatialDelayR, spatialTapR4)

            spatialDelayL[spatialWriteIdx] = sendL
            spatialDelayR[spatialWriteIdx] = sendR
            var nextIdx = spatialWriteIdx + 1
            if (nextIdx >= delayLen) nextIdx = 0
            spatialWriteIdx = nextIdx

            val erGain = currentSpatialStage * SPATIAL_WET_MAX
            eqL += erL * erGain
            eqR += erR * erGain
        }

        val wet = currentWet
        val dryWeight = 1.0 - wet
        val preLimL = dryWeight * safeL + wet * eqL
        val preLimR = dryWeight * safeR + wet * eqR

        if (!limiterEnabled || lookDelayL.isEmpty()) {
            outFrameL = if (preLimL.isFinite()) preLimL else safeL
            outFrameR = if (preLimR.isFinite()) preLimR else safeR
            return
        }

        stepLookaheadStereoLimiter(
            if (preLimL.isFinite()) preLimL else 0.0,
            if (preLimR.isFinite()) preLimR else 0.0,
        )
    }

    private fun stepLookaheadStereoLimiter(
        inL: Double,
        inR: Double,
    ) {
        val peak = max(abs(inL), abs(inR))
        val cap = dequeCap

        // Maintain monotonic decreasing deque over the 2 ms lookahead window in O(1) amortized time.
        while (dequeCount > 0) {
            val lastIdx = if (dequeTail == 0) cap - 1 else dequeTail - 1
            if (dequeVal[lastIdx] <= peak) {
                dequeTail = lastIdx
                dequeCount--
            } else {
                break
            }
        }
        dequeSeq[dequeTail] = lookSampleSeq
        dequeVal[dequeTail] = peak
        dequeTail = if (dequeTail + 1 >= cap) 0 else dequeTail + 1
        dequeCount++

        val expireBefore = lookSampleSeq - lookaheadSamples
        while (dequeCount > 0 && dequeSeq[dequeHead] <= expireBefore) {
            dequeHead = if (dequeHead + 1 >= cap) 0 else dequeHead + 1
            dequeCount--
        }
        lookSampleSeq++

        val delayedL = lookDelayL[lookWriteIdx]
        val delayedR = lookDelayR[lookWriteIdx]
        lookDelayL[lookWriteIdx] = inL
        lookDelayR[lookWriteIdx] = inR
        val nextWrite = lookWriteIdx + 1
        lookWriteIdx = if (nextWrite >= lookaheadSamples) 0 else nextWrite

        val delayedPeak = max(abs(delayedL), abs(delayedR))
        val futurePeak = if (dequeCount > 0) dequeVal[dequeHead] else peak
        val windowPeak = max(delayedPeak, futurePeak)

        val targetGain =
            if (windowPeak <= LIMITER_THRESHOLD) {
                1.0
            } else {
                val headroom = LIMITER_CEILING - LIMITER_THRESHOLD
                val compressed = LIMITER_THRESHOLD + headroom * tanh((windowPeak - LIMITER_THRESHOLD) / headroom)
                min(compressed / windowPeak, LIMITER_CEILING / windowPeak).coerceIn(0.05, 1.0)
            }

        limiterGain =
            if (targetGain < limiterGain) {
                limiterAttackCoeff * limiterGain + (1.0 - limiterAttackCoeff) * targetGain
            } else {
                limiterReleaseCoeff * limiterGain + (1.0 - limiterReleaseCoeff) * targetGain
            }

        if (delayedPeak > LIMITER_CEILING && delayedPeak * limiterGain > LIMITER_CEILING) {
            limiterGain = (LIMITER_CEILING / delayedPeak).coerceIn(0.05, 1.0)
        }

        val limL = softCeilingGuard(delayedL * limiterGain)
        val limR = softCeilingGuard(delayedR * limiterGain)
        outFrameL = if (limL.isFinite()) limL else 0.0
        outFrameR = if (limR.isFinite()) limR else 0.0
    }

    private fun readSpatialDelay(
        buf: DoubleArray,
        tap: Int,
    ): Double {
        var idx = spatialWriteIdx - tap
        if (idx < 0) idx += buf.size
        return buf[idx]
    }

    private fun stepSingleChannelLimiter(framePeak: Double): Double {
        val targetGain =
            if (framePeak <= LIMITER_THRESHOLD) {
                1.0
            } else {
                val headroom = LIMITER_CEILING - LIMITER_THRESHOLD
                val compressed = LIMITER_THRESHOLD + headroom * tanh((framePeak - LIMITER_THRESHOLD) / headroom)
                min(compressed / framePeak, LIMITER_CEILING / framePeak).coerceIn(0.05, 1.0)
            }
        limiterGain =
            if (targetGain < limiterGain) {
                limiterAttackCoeff * limiterGain + (1.0 - limiterAttackCoeff) * targetGain
            } else {
                limiterReleaseCoeff * limiterGain + (1.0 - limiterReleaseCoeff) * targetGain
            }
        return limiterGain
    }

    /** Fixed-slot Transposed Direct Form II (TDF-II) biquad cascade skipping inactive slots via bitmask. */
    private fun runBiquads(
        input: Double,
        channel: Int,
    ): Double {
        var mask = activeMask
        if (mask == 0) return input
        var sample = input
        val chBase = channel * TOTAL_SLOTS * STATE_PER_STAGE
        while (mask != 0) {
            val slot = mask.countTrailingZeroBits()
            val c = slot * COEFFS_PER_STAGE
            val s = chBase + slot * STATE_PER_STAGE

            val z1 = stageState[s]
            val z2 = stageState[s + 1]
            val b0 = currCoeffs[c]
            val out = b0 * sample + z1
            stageState[s] = currCoeffs[c + 1] * sample - currCoeffs[c + 3] * out + z2
            stageState[s + 1] = currCoeffs[c + 2] * sample - currCoeffs[c + 4] * out

            sample = out
            mask = mask and (mask - 1)
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
        // Butterworth 2nd-order HPF (Q = 0.7071) with positive polarity keeps bass centered
        // without inverting L/R Side phase or narrowing stereo midrange.
        val alpha = sinW0 / (2.0 * 0.7071067811865476)
        val a0 = 1.0 + alpha
        val v = (1.0 + cosW0) / 2.0
        bassMonoHpCoeffs[0] = v / a0
        bassMonoHpCoeffs[1] = -(1.0 + cosW0) / a0
        bassMonoHpCoeffs[2] = v / a0
        bassMonoHpCoeffs[3] = (-2.0 * cosW0) / a0
        bassMonoHpCoeffs[4] = (1.0 - alpha) / a0
    }

    companion object {
        private const val SLOT_SUBSONIC = 0
        private const val SLOT_ISO_BASE = 1
        private const val SLOT_BASS_SHELF = 11
        private const val SLOT_TREBLE_SHELF = 12
        private const val SLOT_LOUDNESS_LOW = 13
        private const val SLOT_LOUDNESS_HIGH = 14
        private const val SLOT_AUTOEQ_BASE = 15
        private const val AUTOEQ_SLOTS = 16
        private const val TOTAL_SLOTS = 31

        private const val COEFFS_PER_STAGE = 5
        private const val STATE_PER_STAGE = 2
        private const val RAMP_SECONDS = 0.010
        private const val LOOKAHEAD_SECONDS = 0.002

        private const val SPATIAL_WET_MAX = 0.24

        // True-Peak ceiling at -0.8 dBFS with -1.0 dBFS transparent threshold protects against
        // high-frequency hard clipping, aliasing, and Android AudioFlinger / Bluetooth ISP pops.
        private const val LIMITER_THRESHOLD = 0.891
        private const val LIMITER_CEILING = 0.912
        private const val LIMITER_SOFT_START = 0.915
        private const val LIMITER_HARD_LIMIT = 0.960

        private const val PCM16_SCALE = 32_768.0
        private const val PCM16_MAX = 32_767.0
        private const val PCM16_LSB = 1.0 / 32_768.0
    }
}
