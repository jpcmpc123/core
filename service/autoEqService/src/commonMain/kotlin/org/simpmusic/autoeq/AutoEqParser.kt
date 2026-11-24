package org.simpmusic.autoeq

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

/**
 * The ten centres AutoEq generates its fixed-band output at.
 *
 * Straight from AutoEq's own `10_BAND_GRAPHIC_EQ` config in `autoeq/constants.py`:
 * `'filters': [{'fc': 31.25 * 2 ** i} for i in range(10)]`, with `'q': math.sqrt(2)` and gains
 * bounded to `-12.0 .. 12.0`.
 */
val AUTOEQ_FIXED_BANDS_HZ: List<Double> = List(10) { 31.25 * 2.0.pow(it) }

private val INDEX_LINE = Regex("""^-\s+\[(.+)]\((\S+)\)\s+by\s+(.+?)(?:\s+on\s+(.+))?$""")

private val PREAMP_LINE = Regex("""^Preamp:\s*(-?[0-9.]+)\s*dB""", RegexOption.IGNORE_CASE)

private val FILTER_LINE =
    Regex(
        """^Filter\s+\d+:\s*ON\s+(PK|LSC|HSC|LS|HS|LPQ|HPQ)\s+Fc\s+([0-9.]+)\s*Hz\s+Gain\s+(-?[0-9.]+)\s*dB(?:\s+Q\s+([0-9.]+))?""",
        RegexOption.IGNORE_CASE,
    )

private val GRAPHIC_EQ_PREFIX = Regex("""^GraphicEQ:\s*(.+)$""", RegexOption.IGNORE_CASE)

/** Every profile listed in `results/INDEX.md`. Lines that do not match the shape are skipped. */
fun parseAutoEqIndex(markdown: String): List<AutoEqIndexEntry> =
    markdown
        .lineSequence()
        .mapNotNull { line ->
            val match = INDEX_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val name = match.groupValues[1].trim()
            val path = match.groupValues[2].removePrefix("./").trim()
            if (name.isEmpty() || path.isEmpty()) return@mapNotNull null
            AutoEqIndexEntry(
                path = path,
                name = name,
                source = match.groupValues[3].trim(),
                rig = match.groupValues[4].trim().takeIf { it.isNotEmpty() },
            )
        }.toList()

/**
 * A `<name> FixedBandEQ.txt` file, or null if it does not hold a full curve.
 */
fun parseAutoEqFixedBandEq(text: String): AutoEqCurveData? {
    var preampDb = 0f
    val parsed = mutableListOf<Pair<Double, Float>>()

    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        val preamp = PREAMP_LINE.find(line)
        if (preamp != null) {
            preampDb = preamp.groupValues[1].toFloatOrNull() ?: 0f
            return@forEach
        }
        val filter = FILTER_LINE.find(line) ?: return@forEach
        if (!filter.groupValues[1].equals("PK", ignoreCase = true)) return@forEach
        val hz = filter.groupValues[2].toDoubleOrNull() ?: return@forEach
        val gain = filter.groupValues[3].toFloatOrNull() ?: return@forEach
        parsed += hz to gain
    }

    if (parsed.size < AUTOEQ_FIXED_BANDS_HZ.size) return null

    return AutoEqCurveData(
        bandsDb =
            AUTOEQ_FIXED_BANDS_HZ.map { centre ->
                parsed.firstOrNull { (hz, _) -> abs(hz - centre) <= centre * 0.05 }?.second ?: 0f
            },
        preampDb = preampDb,
    )
}

/**
 * Parses an EqualizerAPO / Squiglink / AutoEq `ParametricEQ.txt` file (`ON PK` / `LSC` / `HSC`)
 * with optional `Preamp: ... dB`.
 */
fun parseAutoEqParametricEq(text: String): AutoEqParametricData? {
    var preampDb = 0f
    val filters = mutableListOf<AutoEqFilterSpec>()

    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        PREAMP_LINE.find(line)?.let { match ->
            preampDb = match.groupValues[1].toFloatOrNull() ?: 0f
            return@forEach
        }
        val filter = FILTER_LINE.find(line) ?: return@forEach
        val rawType = filter.groupValues[1].uppercase()
        val kind =
            when (rawType) {
                "PK" -> AutoEqFilterType.PEAKING
                "LSC", "LS" -> AutoEqFilterType.LOW_SHELF
                "HSC", "HS" -> AutoEqFilterType.HIGH_SHELF
                else -> return@forEach
            }
        val hz = filter.groupValues[2].toFloatOrNull()?.takeIf { it in 10f..22_000f } ?: return@forEach
        val gain = filter.groupValues[3].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@forEach
        val q = filter.groupValues[4].toFloatOrNull()?.takeIf { it in 0.1f..16f } ?: 0.71f
        filters += AutoEqFilterSpec(type = kind, freqHz = hz, gainDb = gain, q = q)
    }

    if (filters.isEmpty()) return null
    val fixed =
        filters.size == AUTOEQ_FIXED_BANDS_HZ.size &&
            AUTOEQ_FIXED_BANDS_HZ.all { centre ->
                filters.any { it.type == AutoEqFilterType.PEAKING && abs(it.freqHz - centre) <= centre * 0.05 }
            }
    return AutoEqParametricData(
        preampDb = preampDb,
        filters = filters,
        isFixedBand = fixed,
    )
}

/**
 * Parses a Wavelet `GraphicEQ.txt` (`GraphicEQ: 20 -3.2; 21 -3.0; ...`) by sampling the curve on
 * the ten ISO octave centres at `Q = 1.41`.
 */
fun parseWaveletGraphicEq(text: String): AutoEqParametricData? {
    val points = mutableListOf<Pair<Double, Float>>()
    text.lineSequence().forEach { raw ->
        val match = GRAPHIC_EQ_PREFIX.find(raw.trim()) ?: return@forEach
        match.groupValues[1].split(';').forEach { token ->
            val parts = token.trim().split(Regex("""\s+"""))
            if (parts.size >= 2) {
                val hz = parts[0].toDoubleOrNull()?.takeIf { it in 10.0..24_000.0 } ?: return@forEach
                val db = parts[1].toFloatOrNull()?.takeIf { it.isFinite() } ?: return@forEach
                points += hz to db
            }
        }
    }
    if (points.size < 4) return null
    val sorted = points.sortedBy { it.first }
    val filters =
        AUTOEQ_FIXED_BANDS_HZ.map { centre ->
            val interpolated = interpolateLogFreq(sorted, centre).coerceIn(-12f, 12f)
            AutoEqFilterSpec(
                type = AutoEqFilterType.PEAKING,
                freqHz = centre.toFloat(),
                gainDb = interpolated,
                q = 1.41f,
            )
        }
    val maxBoost = filters.maxOfOrNull { it.gainDb } ?: 0f
    return AutoEqParametricData(
        preampDb = if (maxBoost > 0f) -max(maxBoost, 0f) else 0f,
        filters = filters,
        isFixedBand = true,
    )
}

/**
 * Auto-detects and parses any user-imported `.txt` EQ profile:
 * 1. EqualizerAPO / Squiglink / AutoEq `ParametricEQ.txt`
 * 2. AutoEq `FixedBandEQ.txt`
 * 3. Wavelet `GraphicEQ.txt`
 */
fun parseAutoEqTextProfile(text: String): AutoEqParametricData? =
    parseAutoEqParametricEq(text)
        ?: parseAutoEqFixedBandEq(text)?.let { fixed ->
            AutoEqParametricData(
                preampDb = fixed.preampDb,
                filters =
                    AUTOEQ_FIXED_BANDS_HZ.mapIndexed { idx, hz ->
                        AutoEqFilterSpec(
                            type = AutoEqFilterType.PEAKING,
                            freqHz = hz.toFloat(),
                            gainDb = fixed.bandsDb.getOrElse(idx) { 0f },
                            q = 1.41f,
                        )
                    },
                isFixedBand = true,
            )
        }
        ?: parseWaveletGraphicEq(text)

private fun interpolateLogFreq(
    sorted: List<Pair<Double, Float>>,
    targetHz: Double,
): Float {
    if (targetHz <= sorted.first().first) return sorted.first().second
    if (targetHz >= sorted.last().first) return sorted.last().second
    for (i in 0 until sorted.lastIndex) {
        val (f0, g0) = sorted[i]
        val (f1, g1) = sorted[i + 1]
        if (targetHz in f0..f1) {
            if (f1 <= f0) return g0
            val t = ((ln(targetHz) - ln(f0)) / (ln(f1) - ln(f0))).toFloat().coerceIn(0f, 1f)
            return g0 + (g1 - g0) * t
        }
    }
    return 0f
}
