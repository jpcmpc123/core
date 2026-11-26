package com.maxrave.media3.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Biquad IIR filter supporting low-pass (LPF) and high-pass (HPF) filter types.
 * Based on Robert Bristow-Johnson's Audio EQ Cookbook.
 *
 * Designed for real-time DJ-style crossfade transitions:
 * - Outgoing track: LPF sweeps cutoff from 20 kHz -> ~300 Hz (treble fades, becomes muffled)
 * - Incoming track: HPF sweeps cutoff from ~300 Hz -> 20 Hz (bass fills in gradually)
 *
 * Supports stereo with independent per-channel state.
 * Thread-safe coefficient updates via [updateCoefficients].
 */
class BiquadFilter {

    enum class FilterType {
        LOW_PASS,
        HIGH_PASS,
    }

    /**
     * Immutable normalized biquad coefficients for a single 2nd-order Butterworth stage.
     * Both cascaded stages share the same coefficients (4th order, 24 dB/octave), and
     * reading a single [@Volatile] reference guarantees the audio thread never observes
     * half-updated numerator/denominator coefficients.
     */
    private data class Coeffs(
        val b0: Double,
        val b1: Double,
        val b2: Double,
        val a1: Double,
        val a2: Double,
    )

    @Volatile
    private var coeffs: Coeffs = IDENTITY_COEFFS

    // Transposed Direct Form II state (2 state variables per stage per channel).
    // Significantly more numerically stable than Direct Form I when cutoff frequency
    // sweeps continuously during a DJ crossfade.
    private var s1_z1L = 0.0
    private var s1_z2L = 0.0
    private var s1_z1R = 0.0
    private var s1_z2R = 0.0

    private var s2_z1L = 0.0
    private var s2_z2L = 0.0
    private var s2_z1R = 0.0
    private var s2_z2R = 0.0

    /**
     * Recalculate filter coefficients for the given parameters.
     * Uses two cascaded Butterworth stages for 4th-order (24 dB/octave) rolloff,
     * matching professional DJ mixer filter steepness.
     *
     * @param cutoffHz Cutoff frequency in Hz.
     * @param sampleRate Sample rate in Hz (e.g. 44100, 48000).
     * @param q Quality factor per stage. 0.707 (Butterworth) gives maximally flat passband.
     * @param type LOW_PASS or HIGH_PASS.
     */
    fun updateCoefficients(
        cutoffHz: Float,
        sampleRate: Int,
        q: Float = BUTTERWORTH_Q,
        type: FilterType,
    ) {
        if (sampleRate <= 0 || !cutoffHz.isFinite() || !q.isFinite() || q <= 0f) {
            coeffs = IDENTITY_COEFFS
            return
        }
        val nyquistLimit = (sampleRate / 2f) - 1f
        if (nyquistLimit <= 20f) {
            coeffs = IDENTITY_COEFFS
            return
        }
        val clampedCutoff = cutoffHz.coerceIn(20f, nyquistLimit)
        val omega = 2.0 * PI * clampedCutoff / sampleRate
        val sinOmega = sin(omega)
        val cosOmega = cos(omega)
        val alpha = sinOmega / (2.0 * q)

        val rawB0: Double
        val rawB1: Double
        val rawB2: Double
        val rawA0 = 1.0 + alpha
        val rawA1 = -2.0 * cosOmega
        val rawA2 = 1.0 - alpha

        when (type) {
            FilterType.LOW_PASS -> {
                val v = (1.0 - cosOmega) / 2.0
                rawB0 = v
                rawB1 = 1.0 - cosOmega
                rawB2 = v
            }

            FilterType.HIGH_PASS -> {
                val v = (1.0 + cosOmega) / 2.0
                rawB0 = v
                rawB1 = -(1.0 + cosOmega)
                rawB2 = v
            }
        }

        coeffs =
            Coeffs(
                b0 = rawB0 / rawA0,
                b1 = rawB1 / rawA0,
                b2 = rawB2 / rawA0,
                a1 = rawA1 / rawA0,
                a2 = rawA2 / rawA0,
            )
    }

    /**
     * Process a single mono sample through both cascaded stages (Transposed Direct Form II).
     */
    fun processSampleMono(input: Double): Double {
        val c = coeffs
        val x = if (input.isFinite()) input else 0.0

        // Stage 1
        val mid = c.b0 * x + s1_z1L
        s1_z1L = c.b1 * x - c.a1 * mid + s1_z2L
        s1_z2L = c.b2 * x - c.a2 * mid

        // Stage 2
        val out = c.b0 * mid + s2_z1L
        s2_z1L = c.b1 * mid - c.a1 * out + s2_z2L
        s2_z2L = c.b2 * mid - c.a2 * out

        return if (out.isFinite()) out else 0.0
    }

    /**
     * Process left stereo sample through both cascaded stages without allocating.
     */
    fun processStereoLeft(inputLeft: Double): Double {
        val c = coeffs
        val x = if (inputLeft.isFinite()) inputLeft else 0.0

        val midL = c.b0 * x + s1_z1L
        s1_z1L = c.b1 * x - c.a1 * midL + s1_z2L
        s1_z2L = c.b2 * x - c.a2 * midL

        val outL = c.b0 * midL + s2_z1L
        s2_z1L = c.b1 * midL - c.a1 * outL + s2_z2L
        s2_z2L = c.b2 * midL - c.a2 * outL

        return if (outL.isFinite()) outL else 0.0
    }

    /**
     * Process right stereo sample through both cascaded stages without allocating.
     */
    fun processStereoRight(inputRight: Double): Double {
        val c = coeffs
        val x = if (inputRight.isFinite()) inputRight else 0.0

        val midR = c.b0 * x + s1_z1R
        s1_z1R = c.b1 * x - c.a1 * midR + s1_z2R
        s1_z2R = c.b2 * x - c.a2 * midR

        val outR = c.b0 * midR + s2_z1R
        s2_z1R = c.b1 * midR - c.a1 * outR + s2_z2R
        s2_z2R = c.b2 * midR - c.a2 * outR

        return if (outR.isFinite()) outR else 0.0
    }

    /**
     * Process a stereo sample pair through both cascaded stages.
     * Each channel maintains independent filter state.
     */
    fun processStereo(inputLeft: Double, inputRight: Double): Pair<Double, Double> =
        processStereoLeft(inputLeft) to processStereoRight(inputRight)

    /**
     * Reset all filter state (clears history for both stages).
     * Call when starting a new audio stream or disabling the filter.
     */
    fun reset() {
        s1_z1L = 0.0; s1_z2L = 0.0
        s1_z1R = 0.0; s1_z2R = 0.0
        s2_z1L = 0.0; s2_z2L = 0.0
        s2_z1R = 0.0; s2_z2R = 0.0
    }

    companion object {
        /** Butterworth Q factor: maximally flat passband, no resonance at cutoff. */
        const val BUTTERWORTH_Q = 0.707f

        private val IDENTITY_COEFFS = Coeffs(b0 = 1.0, b1 = 0.0, b2 = 0.0, a1 = 0.0, a2 = 0.0)
    }
}
