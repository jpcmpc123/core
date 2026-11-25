package com.maxrave.data.mediaservice

import kotlin.math.pow
import kotlin.math.roundToInt

internal data class VolumeNormalizationResult(
    val playerVolume: Float,
    val enhancerGainMb: Int,
)

/**
 * Computes distortion-free ReplayGain / YouTube loudnessDb normalization parameters.
 *
 * Android's [android.media.audiofx.LoudnessEnhancer] only supports non-negative `targetGain >= 0`
 * (measured in millibels). Passing negative gain values when `loudnessDb > 0` leaves behavior
 * undefined/compressed in HAL implementations. Instead:
 * - For tracks louder than target (`loudnessDb > 0`): attenuate digitally via `player.volume`
 *   (`10^(-loudnessDb / 20)` clamped to `0.25f..1.0f`) and keep `LoudnessEnhancer` disabled.
 * - For quieter tracks (`loudnessDb < 0`): keep `player.volume = 1.0f` and apply a gentle positive
 *   boost (`<= 600 mB` / `+6 dB`) to avoid limiter pumping and clipping.
 */
internal fun computeVolumeNormalization(loudnessDb: Float?): VolumeNormalizationResult {
    if (loudnessDb == null || loudnessDb !in -20f..20f || loudnessDb == 0f) {
        return VolumeNormalizationResult(playerVolume = 1.0f, enhancerGainMb = 0)
    }
    return if (loudnessDb > 0f) {
        val linearGain = 10.0.pow(-loudnessDb.toDouble() / 20.0).toFloat().coerceIn(0.25f, 1.0f)
        VolumeNormalizationResult(playerVolume = linearGain, enhancerGainMb = 0)
    } else {
        val boostMb = (-loudnessDb * 100f).roundToInt().coerceIn(0, 600)
        VolumeNormalizationResult(playerVolume = 1.0f, enhancerGainMb = boostMb)
    }
}
