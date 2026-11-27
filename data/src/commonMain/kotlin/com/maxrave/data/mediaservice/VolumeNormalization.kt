package com.maxrave.data.mediaservice

internal data class VolumeNormalizationResult(
    val gainDb: Float,
)

/**
 * Offset above YouTube's `-14 LUFS` baseline for a mobile/Bluetooth `-10 LUFS` target.
 * Keeps normal commercial masters in `0..+4 dB` at exact unity (`0 dB`) without attenuation,
 * gently cuts overly hot tracks down to `-5 dB`, and cleanly lifts quiet tracks up to `+3.5 dB`
 * inside our 64-bit float DSP chain guarded by the transparent 4x True-Peak lookahead limiter
 * — completely avoiding Android's `LoudnessEnhancer` adaptive dynamic-range compressor.
 */
internal const val NORMALIZATION_REFERENCE_OFFSET_DB = 4.0f
internal const val NORMALIZATION_MIN_GAIN_DB = -5.0f
internal const val NORMALIZATION_MAX_BOOST_DB = 3.5f

/**
 * Computes pure linear ReplayGain adjustment in dB from YouTube's `loudnessDb` metadata.
 *
 * Unlike Android's `LoudnessEnhancer` (which injects `AdaptiveDynamicRangeCompression` inside
 * AOSP's audio HAL and crushes micro-dynamics), applying this gain linearly in 64-bit float inside
 * `EqualizerKernel` yields 0% compression and bit-transparent dynamics below `-1.0 dBFS`.
 */
internal fun computeVolumeNormalization(loudnessDb: Float?): VolumeNormalizationResult {
    if (loudnessDb == null || !loudnessDb.isFinite() || loudnessDb !in -20f..20f || loudnessDb == 0f) {
        return VolumeNormalizationResult(gainDb = 0f)
    }
    val gainDb =
        when {
            loudnessDb > NORMALIZATION_REFERENCE_OFFSET_DB -> {
                (NORMALIZATION_REFERENCE_OFFSET_DB - loudnessDb).coerceIn(NORMALIZATION_MIN_GAIN_DB, 0f)
            }
            loudnessDb < 0f -> {
                (-loudnessDb).coerceIn(0f, NORMALIZATION_MAX_BOOST_DB)
            }
            else -> 0f
        }
    return VolumeNormalizationResult(gainDb = gainDb)
}

