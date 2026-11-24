package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.AutoEqCurveEntity
import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.entities.AutoEqIndexMetaEntity
import com.maxrave.domain.data.model.autoeq.AutoEqCurve
import com.maxrave.domain.data.player.AutoEqLayer
import com.maxrave.domain.data.player.BiquadFilterKind
import com.maxrave.domain.data.player.EQUALIZER_ISO_BANDS_HZ
import com.maxrave.domain.data.player.ParametricBand
import com.maxrave.domain.repository.AutoEqRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.simpmusic.autoeq.AutoEq
import org.simpmusic.autoeq.AutoEqFilterType
import org.simpmusic.autoeq.AutoEqIndexResult
import org.simpmusic.autoeq.AutoEqParametricData
import org.simpmusic.autoeq.parseAutoEqTextProfile
import kotlin.time.Clock

private const val TAG = "AutoEqRepositoryImpl"

/** How long a downloaded index is trusted before the app will even ask whether it changed. */
private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000
private const val PARAMETRIC_CACHE_PREFIX = "PARAM|"

internal class AutoEqRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val autoEq: AutoEq,
) : AutoEqRepository {
    override suspend fun search(
        query: String,
        limit: Int,
    ): List<AutoEqEntryEntity> =
        withContext(Dispatchers.IO) {
            localDataSource.searchAutoEqEntries(query.trim(), limit)
        }

    override suspend fun cachedCount(): Int =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqEntryCount()
        }

    override suspend fun refreshIndex(force: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val meta = localDataSource.getAutoEqIndexMeta()
            val cached = localDataSource.getAutoEqEntryCount()
            val now = Clock.System.now().toEpochMilliseconds()
            val withinTtl = meta != null && cached > 0 && now - meta.fetchedAt < INDEX_TTL_MS
            if (!force && withinTtl) return@withContext false

            val etag = meta?.etag?.takeIf { cached > 0 }
            when (val result = autoEq.fetchIndex(etag)) {
                is AutoEqIndexResult.NotModified -> {
                    localDataSource.updateAutoEqIndexMeta(
                        AutoEqIndexMetaEntity(etag = meta?.etag, fetchedAt = now),
                    )
                    false
                }

                is AutoEqIndexResult.Updated -> {
                    localDataSource.replaceAutoEqIndex(
                        entries =
                            result.entries.map {
                                AutoEqEntryEntity(
                                    path = it.path,
                                    name = it.name,
                                    source = it.source,
                                    rig = it.rig,
                                )
                            },
                        meta = AutoEqIndexMetaEntity(etag = result.etag, fetchedAt = now),
                    )
                    Logger.d(TAG, "index updated: ${result.entries.size} profiles")
                    true
                }

                is AutoEqIndexResult.Failed -> {
                    Logger.e(TAG, "index refresh failed: ${result.reason}")
                    false
                }
            }
        }

    override suspend fun cachedCurvePaths(): Set<String> =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqCachedCurvePaths().toSet()
        }

    override suspend fun loadCurve(entry: AutoEqEntryEntity): AutoEqCurve? =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqCurve(entry.path)?.let { cached ->
                if (!cached.bandsDb.startsWith(PARAMETRIC_CACHE_PREFIX)) {
                    val bands = cached.bandsDb.split(",").mapNotNull { it.trim().toFloatOrNull() }
                    if (bands.isNotEmpty()) return@withContext AutoEqCurve(bands, cached.preampDb)
                }
            }
            val fetched = autoEq.fetchFixedBandCurve(entry.path) ?: return@withContext null
            localDataSource.insertAutoEqCurve(
                AutoEqCurveEntity(
                    path = entry.path,
                    bandsDb = fetched.bandsDb.joinToString(","),
                    preampDb = fetched.preampDb,
                ),
            )
            AutoEqCurve(bandsDb = fetched.bandsDb, preampDb = fetched.preampDb)
        }

    override suspend fun loadAutoEqLayer(
        entry: AutoEqEntryEntity,
        displayLabel: String,
    ): AutoEqLayer? =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqCurve(entry.path)?.let { cached ->
                if (cached.bandsDb.startsWith(PARAMETRIC_CACHE_PREFIX)) {
                    decodeParametricBands(cached.bandsDb)?.let { bands ->
                        return@withContext AutoEqLayer(
                            enabled = true,
                            label = displayLabel,
                            path = entry.path,
                            preampDb = cached.preampDb,
                            bands = bands,
                            isFixedBand = false,
                        )
                    }
                }
            }

            val fetched = autoEq.fetchParametricCurve(entry.path)
            if (fetched != null) {
                val layer = fetched.toAutoEqLayer(label = displayLabel, path = entry.path)
                val serialized =
                    if (!fetched.isFixedBand) {
                        encodeParametricBands(layer.bands)
                    } else {
                        fetched.filters.joinToString(",") { it.gainDb.toString() }
                    }
                localDataSource.insertAutoEqCurve(
                    AutoEqCurveEntity(
                        path = entry.path,
                        bandsDb = serialized,
                        preampDb = layer.preampDb,
                    ),
                )
                return@withContext layer
            }

            // Fallback to already-cached legacy fixed-band curve when offline.
            localDataSource.getAutoEqCurve(entry.path)?.let { cached ->
                val legacyGains = cached.bandsDb.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (legacyGains.isNotEmpty()) {
                    return@withContext AutoEqLayer(
                        enabled = true,
                        label = displayLabel,
                        path = entry.path,
                        preampDb = cached.preampDb,
                        bands =
                            EQUALIZER_ISO_BANDS_HZ.mapIndexed { idx, hz ->
                                ParametricBand(
                                    kind = BiquadFilterKind.PEAKING,
                                    freqHz = hz.toFloat(),
                                    gainDb = legacyGains.getOrElse(idx) { 0f },
                                    q = 1.41f,
                                )
                            },
                        isFixedBand = true,
                    )
                }
            }
            null
        }

    override fun parseCustomAutoEqText(
        label: String,
        rawText: String,
    ): AutoEqLayer? {
        val parsed = parseAutoEqTextProfile(rawText) ?: return null
        val cleanLabel = label.trim().ifEmpty { "Custom AutoEq" }
        val uniquePath = "custom:${cleanLabel.lowercase().replace(Regex("""[^a-z0-9]+"""), "_")}_${Clock.System.now().toEpochMilliseconds()}"
        return parsed.toAutoEqLayer(label = cleanLabel, path = uniquePath)
    }

    private fun AutoEqParametricData.toAutoEqLayer(
        label: String,
        path: String,
    ): AutoEqLayer =
        AutoEqLayer(
            enabled = true,
            label = label,
            path = path,
            preampDb = preampDb,
            bands =
                filters.map { filter ->
                    ParametricBand(
                        kind =
                            when (filter.type) {
                                AutoEqFilterType.PEAKING -> BiquadFilterKind.PEAKING
                                AutoEqFilterType.LOW_SHELF -> BiquadFilterKind.LOW_SHELF
                                AutoEqFilterType.HIGH_SHELF -> BiquadFilterKind.HIGH_SHELF
                            },
                        freqHz = filter.freqHz,
                        gainDb = filter.gainDb,
                        q = filter.q,
                    )
                },
            isFixedBand = isFixedBand,
        )

    private fun encodeParametricBands(bands: List<ParametricBand>): String =
        PARAMETRIC_CACHE_PREFIX +
            bands.joinToString(";") { b ->
                "${b.kind.name}:${b.freqHz}:${b.gainDb}:${b.q}"
            }

    private fun decodeParametricBands(encoded: String): List<ParametricBand>? {
        if (!encoded.startsWith(PARAMETRIC_CACHE_PREFIX)) return null
        val body = encoded.removePrefix(PARAMETRIC_CACHE_PREFIX)
        if (body.isBlank()) return null
        val parsed =
            body.split(";").mapNotNull { entry ->
                val parts = entry.split(":")
                if (parts.size != 4) return@mapNotNull null
                val kind = runCatching { BiquadFilterKind.valueOf(parts[0]) }.getOrNull() ?: return@mapNotNull null
                val freq = parts[1].toFloatOrNull() ?: return@mapNotNull null
                val gain = parts[2].toFloatOrNull() ?: return@mapNotNull null
                val q = parts[3].toFloatOrNull() ?: return@mapNotNull null
                ParametricBand(kind = kind, freqHz = freq, gainDb = gain, q = q).sanitized()
            }
        return parsed.takeIf { it.isNotEmpty() }
    }
}
