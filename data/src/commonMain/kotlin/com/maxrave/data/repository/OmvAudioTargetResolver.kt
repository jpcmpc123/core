package com.maxrave.data.repository

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.maxrave.kotlinytmusicscraper.AudioVariantResolution
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Resolves and caches `OMV -> ATV` audio target IDs before the first `youTube.player(...)`
 * extraction so known music videos do not pay a wasted extraction on the OMV ID first.
 *
 * - Positive mappings are bounded LRU ([maxPositiveEntries]) and can be seeded/snapshotted for
 *   lightweight persistence across app restarts.
 * - Negative entries carry a short TTL ([negativeTtlMs]) and are ONLY recorded for confirmed
 *   [AudioVariantResolution.NotFound] results — never for transient network/timeout failures.
 */
@OptIn(ExperimentalTime::class)
internal class OmvAudioTargetResolver(
    private val maxPositiveEntries: Int = MAX_POSITIVE_ENTRIES,
    private val maxNegativeEntries: Int = MAX_NEGATIVE_ENTRIES,
    private val negativeTtlMs: Long = DEFAULT_NEGATIVE_TTL_MS,
    private val clockMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    data class InitialResolution(
        val targetId: String,
        val preResolvedAtvAttempted: Boolean,
        val upgradedBeforePlayer: Boolean,
        val preResolveTransientFailure: Boolean = false,
    )

    private val omvToAtvCache = MutableStateFlow<Map<String, String>>(emptyMap())
    private val omvNoAtvExpiryMs = MutableStateFlow<Map<String, Long>>(emptyMap())

    inline fun resolveInitialTarget(
        videoId: String,
        localVideoType: String?,
        allowAudioUpgrade: Boolean,
        lookupAtv: () -> AudioVariantResolution,
    ): InitialResolution {
        if (!allowAudioUpgrade) {
            return InitialResolution(
                targetId = videoId,
                preResolvedAtvAttempted = false,
                upgradedBeforePlayer = false,
            )
        }
        mappedAtvOf(videoId)?.let { cachedAtv ->
            return InitialResolution(
                targetId = cachedAtv,
                preResolvedAtvAttempted = true,
                upgradedBeforePlayer = true,
            )
        }
        if (localVideoType == MUSIC_VIDEO_TYPE_OMV && !hasNegativeEntry(videoId)) {
            return when (val outcome = lookupAtv()) {
                is AudioVariantResolution.Found -> {
                    val atvId = outcome.videoId
                    if (atvId.isNotBlank() && atvId != videoId) {
                        recordPositive(videoId, atvId)
                        InitialResolution(
                            targetId = atvId,
                            preResolvedAtvAttempted = true,
                            upgradedBeforePlayer = true,
                        )
                    } else {
                        recordNegative(videoId)
                        InitialResolution(
                            targetId = videoId,
                            preResolvedAtvAttempted = true,
                            upgradedBeforePlayer = false,
                        )
                    }
                }

                AudioVariantResolution.NotFound -> {
                    recordNegative(videoId)
                    InitialResolution(
                        targetId = videoId,
                        preResolvedAtvAttempted = true,
                        upgradedBeforePlayer = false,
                    )
                }

                is AudioVariantResolution.TransientFailure -> {
                    // Never blacklist on a network timeout or transient error.
                    InitialResolution(
                        targetId = videoId,
                        preResolvedAtvAttempted = true,
                        upgradedBeforePlayer = false,
                        preResolveTransientFailure = true,
                    )
                }
            }
        }
        return InitialResolution(
            targetId = videoId,
            preResolvedAtvAttempted = false,
            upgradedBeforePlayer = false,
        )
    }

    fun shouldAttemptPostPlayerLookup(
        videoId: String,
        resolvedSourceId: String,
        preResolvedAtvAttempted: Boolean,
        preResolveTransientFailure: Boolean = false,
        hasDurationMismatch: Boolean = false,
    ): Boolean {
        if (resolvedSourceId != videoId) return false
        if (hasDurationMismatch) {
            // A real duration discrepancy (e.g. 207s OMV vs 199s album metadata) overrides any
            // cached negative entry; only skip if we already ran a confirmed NotFound pre-lookup
            // in this very same resolve call.
            return !preResolvedAtvAttempted || preResolveTransientFailure
        }
        return !preResolvedAtvAttempted && !hasNegativeEntry(videoId)
    }

    fun recordPositive(
        videoId: String,
        atvId: String,
    ) {
        if (videoId.isBlank() || atvId.isBlank() || atvId == videoId) return
        omvNoAtvExpiryMs.update { if (videoId in it) it - videoId else it }
        omvToAtvCache.update { current ->
            val withoutKey = if (videoId in current) current - videoId else current
            val trimmed =
                if (withoutKey.size >= maxPositiveEntries) {
                    withoutKey - withoutKey.keys.first()
                } else {
                    withoutKey
                }
            trimmed + (videoId to atvId)
        }
    }

    fun seedPositiveMappings(entries: Map<String, String>) {
        if (entries.isEmpty()) return
        omvToAtvCache.update { current ->
            val merged = LinkedHashMap<String, String>(entries.size + current.size)
            for ((omv, atv) in entries) {
                if (omv.isNotBlank() && atv.isNotBlank() && omv != atv) {
                    merged[omv] = atv
                }
            }
            // In-memory entries from the current session win over older persisted entries.
            for ((omv, atv) in current) {
                if (omv.isNotBlank() && atv.isNotBlank() && omv != atv) {
                    merged.remove(omv)
                    merged[omv] = atv
                }
            }
            while (merged.size > maxPositiveEntries) {
                val oldestKey = merged.keys.firstOrNull() ?: break
                merged.remove(oldestKey)
            }
            merged
        }
    }

    fun snapshotPositiveMappings(): Map<String, String> = omvToAtvCache.value

    fun recordNegative(videoId: String) {
        if (videoId.isBlank()) return
        val now = clockMs()
        val expiresAt = now + negativeTtlMs
        omvNoAtvExpiryMs.update { current ->
            val live = current.filterValues { it > now }
            val trimmed =
                if (live.size >= maxNegativeEntries && videoId !in live) {
                    live - live.keys.first()
                } else {
                    live
                }
            trimmed + (videoId to expiresAt)
        }
    }

    fun evictMapping(videoId: String) {
        omvToAtvCache.update { current ->
            if (videoId in current) current - videoId else current
        }
    }

    fun mappedAtvOf(videoId: String): String? = omvToAtvCache.value[videoId]

    fun hasNegativeEntry(videoId: String): Boolean {
        val expiry = omvNoAtvExpiryMs.value[videoId] ?: return false
        return clockMs() < expiry
    }

    companion object {
        const val MAX_POSITIVE_ENTRIES = 256
        const val MAX_NEGATIVE_ENTRIES = 256
        const val DEFAULT_NEGATIVE_TTL_MS = 10 * 60 * 1000L
    }
}
