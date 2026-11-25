package com.maxrave.domain.data.player

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** The ten ISO octave centres shared by Android, Desktop (mpv) and AutoEq fixed bands. */
val EQUALIZER_ISO_BANDS_HZ: IntArray = intArrayOf(31, 62, 125, 250, 500, 1_000, 2_000, 4_000, 8_000, 16_000)

const val EQUALIZER_ISO_Q: Double = 1.41
const val EQUALIZER_BAND_MIN_DB: Float = -12f
const val EQUALIZER_BAND_MAX_DB: Float = 12f
const val EQUALIZER_PREAMP_MIN_DB: Float = -15f
const val EQUALIZER_PREAMP_MAX_DB: Float = 6f
const val EQUALIZER_BASS_MAX_DB: Float = 9f
const val EQUALIZER_TREBLE_MIN_DB: Float = -6f
const val EQUALIZER_TREBLE_MAX_DB: Float = 9f
const val EQUALIZER_STEREO_WIDTH_MIN: Float = 0f
const val EQUALIZER_STEREO_WIDTH_MAX: Float = 1.75f
const val EQUALIZER_SPATIAL_STAGE_MAX: Float = 1.0f

const val EQUALIZER_SUBSONIC_HZ: Double = 28.0
const val EQUALIZER_BASS_SHELF_HZ: Double = 70.0
const val EQUALIZER_TREBLE_SHELF_HZ: Double = 6_000.0
const val EQUALIZER_LOUDNESS_LOW_HZ: Double = 85.0
const val EQUALIZER_LOUDNESS_HIGH_HZ: Double = 10_000.0
const val EQUALIZER_BASS_MONO_HZ: Double = 100.0

@Serializable
enum class PreampMode {
    /** Cuts ~35% of positive peak above +1 dB (capped at -4.5 dB) and lets the transparent limiter handle rare peaks. */
    AUTO_BALANCED,

    /** Cuts the full summed response peak (-0.5 dB margin) for strict studio headroom. */
    AUTO_STRICT,

    /** Applies the user's explicit [EqualizerConfig.manualPreampDb]. */
    MANUAL,
}

@Serializable
enum class BiquadFilterKind {
    PEAKING,
    LOW_SHELF,
    HIGH_SHELF,
    HIGH_PASS,
}

@Serializable
data class ParametricBand(
    val kind: BiquadFilterKind = BiquadFilterKind.PEAKING,
    val freqHz: Float,
    val gainDb: Float,
    val q: Float = 1.41f,
) {
    fun sanitized(): ParametricBand =
        copy(
            freqHz = finiteOr(freqHz, 1_000f).coerceIn(10f, 22_000f),
            gainDb = finiteOr(gainDb, 0f).coerceIn(-18f, 18f),
            q = finiteOr(q, 1.41f).coerceIn(0.1f, 16f),
        )
}

@Serializable
data class EqualizerEnhancements(
    val preampMode: PreampMode = PreampMode.AUTO_BALANCED,
    val bassBoostDb: Float = 0f,
    val trebleBoostDb: Float = 0f,
    val stereoWidth: Float = 1f,
    val bassMonoEnabled: Boolean = true,
    val spatialStage: Float = 0f,
    val loudnessAmount: Float = 0f,
    val subsonicFilterEnabled: Boolean = true,
    val limiterEnabled: Boolean = true,
) {
    fun sanitized(): EqualizerEnhancements =
        copy(
            bassBoostDb = finiteOr(bassBoostDb, 0f).coerceIn(0f, EQUALIZER_BASS_MAX_DB),
            trebleBoostDb = finiteOr(trebleBoostDb, 0f).coerceIn(EQUALIZER_TREBLE_MIN_DB, EQUALIZER_TREBLE_MAX_DB),
            stereoWidth = finiteOr(stereoWidth, 1f).coerceIn(EQUALIZER_STEREO_WIDTH_MIN, EQUALIZER_STEREO_WIDTH_MAX),
            spatialStage = finiteOr(spatialStage, 0f).coerceIn(0f, EQUALIZER_SPATIAL_STAGE_MAX),
            loudnessAmount = finiteOr(loudnessAmount, 0f).coerceIn(0f, 1f),
        )

    val isNeutral: Boolean
        get() =
            bassBoostDb == 0f &&
                trebleBoostDb == 0f &&
                abs(stereoWidth - 1f) < 0.005f &&
                spatialStage < 0.01f &&
                loudnessAmount == 0f
}

@Serializable
data class AutoEqLayer(
    val enabled: Boolean = true,
    val label: String,
    val path: String,
    val preampDb: Float = 0f,
    val bands: List<ParametricBand> = emptyList(),
    val isFixedBand: Boolean = false,
) {
    fun sanitized(): AutoEqLayer =
        copy(
            preampDb = finiteOr(preampDb, 0f).coerceIn(EQUALIZER_PREAMP_MIN_DB, EQUALIZER_PREAMP_MAX_DB),
            bands = bands.take(16).map { it.sanitized() },
        )

    val isActive: Boolean
        get() = enabled && bands.any { abs(it.gainDb) > 0.01f }
}

/**
 * Immutable DSP configuration consumed per-buffer by the audio processor and mpv backend.
 *
 * Compared by reference identity (`===`) in the real-time audio path so changing any knob
 * produces a single snapshot without comparing floats on every audio buffer.
 */
data class EqualizerConfig(
    val enabled: Boolean = false,
    val bandsDb: List<Float> = emptyList(),
    val manualPreampDb: Float = 0f,
    val enhancements: EqualizerEnhancements = EqualizerEnhancements(),
    val autoEqLayer: AutoEqLayer? = null,
) {
    val normalizedBandsDb: List<Float> =
        List(EQUALIZER_ISO_BANDS_HZ.size) { index ->
            finiteOr(bandsDb.getOrElse(index) { 0f }, 0f).coerceIn(EQUALIZER_BAND_MIN_DB, EQUALIZER_BAND_MAX_DB)
        }

    val safeEnhancements: EqualizerEnhancements = enhancements.sanitized()
    val safeManualPreampDb: Float = finiteOr(manualPreampDb, 0f).coerceIn(EQUALIZER_PREAMP_MIN_DB, EQUALIZER_PREAMP_MAX_DB)
    val safeAutoEqLayer: AutoEqLayer? = autoEqLayer?.sanitized()

    val hasTonalShaping: Boolean
        get() =
            normalizedBandsDb.any { abs(it) > 0.01f } ||
                !safeEnhancements.isNeutral ||
                safeAutoEqLayer?.isActive == true

    /** True when the audio processor should take the direct bypass copy path. */
    val isBypass: Boolean
        get() =
            !enabled ||
                (!hasTonalShaping && abs(safeManualPreampDb) < 0.01f)

    /**
     * Computes the preamp gain (in dB) to apply ahead of the EQ stages.
     *
     * Evaluated from the combined frequency response so overlapping adjacent bands and shelf boosts
     * do not silently eat headroom and clip into 0 dBFS.
     */
    fun effectivePreampDb(sampleRate: Double = 48_000.0): Float {
        if (!enabled) return 0f
        return when (safeEnhancements.preampMode) {
            PreampMode.MANUAL -> safeManualPreampDb
            PreampMode.AUTO_STRICT -> {
                val peak = computePeakResponseDb(this, sampleRate)
                val target = if (peak > 0f) -(peak + 0.5f) else 0f
                target.coerceIn(EQUALIZER_PREAMP_MIN_DB, 0f)
            }
            PreampMode.AUTO_BALANCED -> {
                val peak = computePeakResponseDb(this, sampleRate)
                val target = if (peak > 0.5f) -((peak - 0.5f) * 0.58f) else 0f
                target.coerceIn(-8.0f, 0f)
            }
        }
    }

    /** Builds the exact list of biquad filter stages representing the active EQ/tone response. */
    fun tonalBiquadStages(): List<ParametricBand> {
        val stages = ArrayList<ParametricBand>(26)

        // Subsonic rumble guard: active when user enables it and any bass gain or EQ is active.
        val hasBassGain =
            safeEnhancements.bassBoostDb > 0.1f ||
                safeEnhancements.loudnessAmount > 0.05f ||
                normalizedBandsDb.take(3).any { it > 0.5f }
        if (safeEnhancements.subsonicFilterEnabled && hasBassGain) {
            stages += ParametricBand(BiquadFilterKind.HIGH_PASS, EQUALIZER_SUBSONIC_HZ.toFloat(), 0f, 0.7071f)
        }

        // Independent headphone AutoEq correction layer (Parametric or FixedBand).
        safeAutoEqLayer?.takeIf { it.enabled }?.let { layer ->
            layer.bands.forEach { band ->
                if (abs(band.gainDb) > 0.01f) {
                    stages += band
                }
            }
        }

        // 10-band ISO graphic/peaking stage. Top 16 kHz uses a smooth high-shelf when boosted/cut
        // above 0.5 dB unless paired with FixedBand AutoEq expectations.
        normalizedBandsDb.forEachIndexed { index, gain ->
            if (abs(gain) > 0.01f) {
                val hz = EQUALIZER_ISO_BANDS_HZ[index].toFloat()
                val kind =
                    if (index == EQUALIZER_ISO_BANDS_HZ.lastIndex) {
                        BiquadFilterKind.HIGH_SHELF
                    } else {
                        BiquadFilterKind.PEAKING
                    }
                val q = if (kind == BiquadFilterKind.HIGH_SHELF) 0.7071f else EQUALIZER_ISO_Q.toFloat()
                stages += ParametricBand(kind, hz, gain, q)
            }
        }

        // Dedicated musical Bass & Air shelves.
        if (safeEnhancements.bassBoostDb > 0.01f) {
            stages += ParametricBand(BiquadFilterKind.LOW_SHELF, EQUALIZER_BASS_SHELF_HZ.toFloat(), safeEnhancements.bassBoostDb, 0.7071f)
        }
        if (abs(safeEnhancements.trebleBoostDb) > 0.01f) {
            stages += ParametricBand(BiquadFilterKind.HIGH_SHELF, EQUALIZER_TREBLE_SHELF_HZ.toFloat(), safeEnhancements.trebleBoostDb, 0.7071f)
        }

        // Fletcher-Munson dynamic loudness contour.
        if (safeEnhancements.loudnessAmount > 0.01f) {
            val amount = safeEnhancements.loudnessAmount
            stages += ParametricBand(BiquadFilterKind.LOW_SHELF, EQUALIZER_LOUDNESS_LOW_HZ.toFloat(), 4.5f * amount, 0.65f)
            stages += ParametricBand(BiquadFilterKind.HIGH_SHELF, EQUALIZER_LOUDNESS_HIGH_HZ.toFloat(), 2.5f * amount, 0.65f)
        }

        return stages
    }

    companion object {
        val FLAT = EqualizerConfig()

        fun fromLegacy(
            bandsDb: List<Float>,
            preampDb: Float,
        ): EqualizerConfig {
            val active = preampDb != 0f || bandsDb.any { it != 0f }
            return EqualizerConfig(
                enabled = active,
                bandsDb = bandsDb,
                manualPreampDb = preampDb,
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.MANUAL,
                        subsonicFilterEnabled = false,
                        limiterEnabled = true,
                    ),
            )
        }
    }
}

@Serializable
data class SavedEqualizerProfile(
    val id: String,
    val name: String,
    val bandsDb: List<Float>,
    val preampMode: PreampMode = PreampMode.AUTO_BALANCED,
    val manualPreampDb: Float = 0f,
    val bassBoostDb: Float = 0f,
    val trebleBoostDb: Float = 0f,
    val stereoWidth: Float = 1f,
    val bassMonoEnabled: Boolean = true,
    val spatialStage: Float = 0f,
    val loudnessAmount: Float = 0f,
    val subsonicFilterEnabled: Boolean = true,
    val limiterEnabled: Boolean = true,
    val autoEqLayer: AutoEqLayer? = null,
    val updatedAtEpochMs: Long = 0L,
) {
    fun toEnhancements(): EqualizerEnhancements =
        EqualizerEnhancements(
            preampMode = preampMode,
            bassBoostDb = bassBoostDb,
            trebleBoostDb = trebleBoostDb,
            stereoWidth = stereoWidth,
            bassMonoEnabled = bassMonoEnabled,
            spatialStage = spatialStage,
            loudnessAmount = loudnessAmount,
            subsonicFilterEnabled = subsonicFilterEnabled,
            limiterEnabled = limiterEnabled,
        ).sanitized()

    fun matches(
        currentBandsDb: List<Float>,
        currentManualPreampDb: Float,
        currentEnhancements: EqualizerEnhancements,
        currentAutoEqLayer: AutoEqLayer?,
    ): Boolean {
        val safeBands = List(EQUALIZER_ISO_BANDS_HZ.size) { bandsDb.getOrElse(it) { 0f } }
        val otherBands = List(EQUALIZER_ISO_BANDS_HZ.size) { currentBandsDb.getOrElse(it) { 0f } }
        val bandsEqual = safeBands.indices.all { abs(safeBands[it] - otherBands[it]) < 0.05f }
        if (!bandsEqual) return false
        val safeEnh = currentEnhancements.sanitized()
        if (preampMode != safeEnh.preampMode) return false
        if (preampMode == PreampMode.MANUAL && abs(manualPreampDb - currentManualPreampDb) >= 0.05f) return false
        if (abs(bassBoostDb - safeEnh.bassBoostDb) >= 0.05f) return false
        if (abs(trebleBoostDb - safeEnh.trebleBoostDb) >= 0.05f) return false
        if (abs(stereoWidth - safeEnh.stereoWidth) >= 0.02f) return false
        if (bassMonoEnabled != safeEnh.bassMonoEnabled) return false
        if (abs(spatialStage - safeEnh.spatialStage) >= 0.02f) return false
        if (abs(loudnessAmount - safeEnh.loudnessAmount) >= 0.02f) return false
        if (subsonicFilterEnabled != safeEnh.subsonicFilterEnabled) return false
        if (limiterEnabled != safeEnh.limiterEnabled) return false
        val savedAutoPath = autoEqLayer?.takeIf { it.enabled }?.path
        val curAutoPath = currentAutoEqLayer?.takeIf { it.enabled }?.path
        return savedAutoPath == curAutoPath
    }
}

/** Canonical primary key for linking an EQ profile to an active [AudioOutput]. */
fun audioOutputKeyFor(output: AudioOutput?): String? {
    output ?: return null
    val trimmedName = output.name.trim()
    return when {
        output.kind == AudioOutputKind.BLUETOOTH && trimmedName.isNotEmpty() -> "BLUETOOTH:$trimmedName"
        output.kind == AudioOutputKind.USB && trimmedName.isNotEmpty() -> "USB:$trimmedName"
        else -> output.kind.name
    }
}

/** Generic fallback key when a specific device name isn't explicitly bound yet. */
fun audioOutputFallbackKeyFor(output: AudioOutput?): String? = output?.kind?.name

/**
 * Evaluates the combined peak magnitude boost (in dB) of [config]'s active EQ/tone/AutoEq stages
 * across 64 logarithmically spaced frequencies from 20 Hz to min(20 kHz, 0.45 * Fs).
 */
fun computePeakResponseDb(
    config: EqualizerConfig,
    sampleRate: Double = 48_000.0,
): Float {
    val enh = config.safeEnhancements
    var spatialStereoExtraDb = 0.0
    if (enh.stereoWidth > 1.005f) {
        spatialStereoExtraDb += 20.0 * log10(0.5 * (1.0 + enh.stereoWidth.toDouble()))
    }
    if (enh.spatialStage > 0.01f) {
        spatialStereoExtraDb += 20.0 * log10(1.0 + enh.spatialStage.toDouble() * 0.24 * 0.65)
    }

    val stages = config.tonalBiquadStages()
    if (stages.isEmpty()) return spatialStereoExtraDb.toFloat()

    val fs = if (sampleRate.isFinite() && sampleRate >= 8_000.0) sampleRate else 48_000.0
    val coeffs = stages.mapNotNull { band -> computeNormalizedBiquad(band, fs) }
    if (coeffs.isEmpty()) return spatialStereoExtraDb.toFloat()

    val fMin = 20.0
    val fMax = minOf(20_000.0, fs * 0.45)
    if (fMax <= fMin) return spatialStereoExtraDb.toFloat()
    val logRatio = ln(fMax / fMin)

    var maxDb = Float.NEGATIVE_INFINITY
    val steps = 64
    for (i in 0..steps) {
        val freq = fMin * kotlin.math.exp(logRatio * i / steps)
        val w = 2.0 * PI * freq / fs
        val cosW = cos(w)
        val sinW = sin(w)
        val cos2W = 2.0 * cosW * cosW - 1.0
        val sin2W = 2.0 * sinW * cosW

        var magSq = 1.0
        for (c in coeffs) {
            val numRe = c[0] + c[1] * cosW + c[2] * cos2W
            val numIm = -(c[1] * sinW + c[2] * sin2W)
            val denRe = 1.0 + c[3] * cosW + c[4] * cos2W
            val denIm = -(c[3] * sinW + c[4] * sin2W)
            val denMagSq = denRe * denRe + denIm * denIm
            if (denMagSq > 1e-18) {
                magSq *= (numRe * numRe + numIm * numIm) / denMagSq
            }
        }
        val db = (10.0 * log10(magSq.coerceAtLeast(1e-12)) + spatialStereoExtraDb).toFloat()
        if (db.isFinite() && db > maxDb) {
            maxDb = db
        }
    }
    return if (maxDb.isFinite()) maxDb else spatialStereoExtraDb.toFloat()
}

/** Computes normalized `[b0, b1, b2, a1, a2]` RBJ cookbook coefficients for [band] at [sampleRate]. */
fun computeNormalizedBiquad(
    band: ParametricBand,
    sampleRate: Double,
): DoubleArray? {
    if (!sampleRate.isFinite() || sampleRate <= 0.0) return null
    val safe = band.sanitized()
    if (safe.freqHz.toDouble() >= sampleRate * 0.49) return null

    val w0 = 2.0 * PI * safe.freqHz / sampleRate
    val cosW0 = cos(w0)
    val sinW0 = sin(w0)
    val a = 10.0.pow(safe.gainDb / 40.0)

    val b0: Double
    val b1: Double
    val b2: Double
    val a0: Double
    val a1: Double
    val a2: Double

    when (safe.kind) {
        BiquadFilterKind.PEAKING -> {
            val alpha = sinW0 / (2.0 * safe.q)
            b0 = 1.0 + alpha * a
            b1 = -2.0 * cosW0
            b2 = 1.0 - alpha * a
            a0 = 1.0 + alpha / a
            a1 = -2.0 * cosW0
            a2 = 1.0 - alpha / a
        }

        BiquadFilterKind.LOW_SHELF -> {
            val safeQ = safe.q.toDouble().coerceIn(0.3, 1.2)
            val alpha = sinW0 / (2.0 * safeQ)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha)
            b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0)
            b2 = a * ((a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha)
            a0 = (a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha
            a1 = -2.0 * ((a - 1.0) + (a + 1.0) * cosW0)
            a2 = (a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha
        }

        BiquadFilterKind.HIGH_SHELF -> {
            val safeQ = safe.q.toDouble().coerceIn(0.3, 1.2)
            val alpha = sinW0 / (2.0 * safeQ)
            val twoSqrtAAlpha = 2.0 * sqrt(a) * alpha
            b0 = a * ((a + 1.0) + (a - 1.0) * cosW0 + twoSqrtAAlpha)
            b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0)
            b2 = a * ((a + 1.0) + (a - 1.0) * cosW0 - twoSqrtAAlpha)
            a0 = (a + 1.0) - (a - 1.0) * cosW0 + twoSqrtAAlpha
            a1 = 2.0 * ((a - 1.0) - (a + 1.0) * cosW0)
            a2 = (a + 1.0) - (a - 1.0) * cosW0 - twoSqrtAAlpha
        }

        BiquadFilterKind.HIGH_PASS -> {
            val alpha = sinW0 / (2.0 * safe.q)
            val v = (1.0 + cosW0) / 2.0
            b0 = v
            b1 = -(1.0 + cosW0)
            b2 = v
            a0 = 1.0 + alpha
            a1 = -2.0 * cosW0
            a2 = 1.0 - alpha
        }
    }

    if (!a0.isFinite() || abs(a0) < 1e-12) return null
    val result = doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    return if (result.all { it.isFinite() }) result else null
}

private fun finiteOr(
    value: Float,
    fallback: Float,
): Float = if (value.isFinite()) value else fallback
