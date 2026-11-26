package com.maxrave.data.mediaservice

import kotlin.math.pow
import kotlin.math.roundToInt

internal data class VolumeNormalizationResult(
    val playerVolume: Float,
    val enhancerGainMb: Int,
)

/**
 * Offset above YouTube's `-14 LUFS` baseline for a mobile/Bluetooth `-10 LUFS` target.
 * Keeps tracks in `0..+4 dB` at full `1.0f` volume instead of cutting normal songs down to
 * `-8..-12 dB`, while capping attenuation on hot tracks at `-5 dB` (`0.56f`).
 */
internal const val NORMALIZATION_REFERENCE_OFFSET_DB = 4.0f
internal const val NORMALIZATION_MIN_PLAYER_VOLUME = 0.56f
internal const val NORMALIZATION_MAX_ENHANCER_MB = 500

/**
 * Computes distortion-free ReplayGain / YouTube loudnessDb normalization parameters.
 *
 * Android's [android.media.audiofx.LoudnessEnhancer] only supports non-negative `targetGain >= 0`
 * (measured in millibels). Passing negative gain values leaves behavior undefined/compressed in HAL
 * implementations. Instead:
 * - For hot tracks louder than the `-10 LUFS` mobile target (`loudnessDb > +4 dB`): gently
 *   attenuate via `player.volume` down to a `0.56f` (`-5 dB`) floor and keep `LoudnessEnhancer` off.
 * - For normal commercial tracks (`0..+4 dB`): keep `player.volume = 1.0f` and `LoudnessEnhancer` off.
 * - For quieter tracks (`loudnessDb < 0`): keep `player.volume = 1.0f` and apply a clean positive
 *   boost (up to `+500 mB` / `+5 dB`) to lift low masters without compressor pumping.
 */
internal fun computeVolumeNormalization(loudnessDb: Float?): VolumeNormalizationResult {
    if (loudnessDb == null || loudnessDb !in -20f..20f || loudnessDb == 0f) {
        return VolumeNormalizationResult(playerVolume = 1.0f, enhancerGainMb = 0)
    }
    return if (loudnessDb > NORMALIZATION_REFERENCE_OFFSET_DB) {
        val effectiveDeltaDb = (loudnessDb - NORMALIZATION_REFERENCE_OFFSET_DB).toDouble()
        val linearGain =
            10.0
                .pow(-effectiveDeltaDb / 20.0)
                .toFloat()
                .coerceIn(NORMALIZATION_MIN_PLAYER_VOLUME, 1.0f)
        VolumeNormalizationResult(playerVolume = linearGain, enhancerGainMb = 0)
    } else if (loudnessDb < 0f) {
        val boostMb = (-loudnessDb * 100f).roundToInt().coerceIn(0, NORMALIZATION_MAX_ENHANCER_MB)
        VolumeNormalizationResult(playerVolume = 1.0f, enhancerGainMb = boostMb)
    } else {
        VolumeNormalizationResult(playerVolume = 1.0f, enhancerGainMb = 0)
    }
}
