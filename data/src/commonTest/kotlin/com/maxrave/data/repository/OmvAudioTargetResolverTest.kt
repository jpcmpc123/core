package com.maxrave.data.repository

import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_ATV
import com.maxrave.domain.data.model.streams.YouTubeWatchEndpoint.WatchEndpointMusicSupportedConfigs.WatchEndpointMusicConfig.Companion.MUSIC_VIDEO_TYPE_OMV
import com.maxrave.kotlinytmusicscraper.AudioVariantResolution
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OmvAudioTargetResolverTest {
    @Test
    fun knownOmvResolvesAtvBeforeFirstPlayerCallAndCachesResult() {
        val resolver = OmvAudioTargetResolver()
        var lookups = 0

        val first =
            resolver.resolveInitialTarget(
                videoId = "ukbiRcyzrpc",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.Found("OCY9vN4QzqY")
            }

        assertEquals("OCY9vN4QzqY", first.targetId)
        assertTrue(first.upgradedBeforePlayer)
        assertTrue(first.preResolvedAtvAttempted)
        assertFalse(first.preResolveTransientFailure)
        assertEquals(1, lookups)
        assertEquals("OCY9vN4QzqY", resolver.mappedAtvOf("ukbiRcyzrpc"))

        // Second play hits positive cache without re-querying YouTube.
        val second =
            resolver.resolveInitialTarget(
                videoId = "ukbiRcyzrpc",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.Found("SHOULD_NOT_RUN")
            }

        assertEquals("OCY9vN4QzqY", second.targetId)
        assertTrue(second.upgradedBeforePlayer)
        assertEquals(1, lookups)
    }

    @Test
    fun transientTimeoutNeverBlacklistsOmvAndAllowsNextPlayToUpgrade() {
        val resolver = OmvAudioTargetResolver()
        var lookups = 0

        // First play: 4G stall causes timeout -> TransientFailure.
        val first =
            resolver.resolveInitialTarget(
                videoId = "KLD_OyCSloA",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.TransientFailure("timeout")
            }

        assertEquals("KLD_OyCSloA", first.targetId)
        assertFalse(first.upgradedBeforePlayer)
        assertTrue(first.preResolvedAtvAttempted)
        assertTrue(first.preResolveTransientFailure)
        assertFalse(resolver.hasNegativeEntry("KLD_OyCSloA"))
        // Duration mismatch detected post-player still triggers retry even in the same call.
        assertTrue(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "KLD_OyCSloA",
                resolvedSourceId = "KLD_OyCSloA",
                preResolvedAtvAttempted = first.preResolvedAtvAttempted,
                preResolveTransientFailure = first.preResolveTransientFailure,
                hasDurationMismatch = true,
            ),
        )

        // Second play: network recovers -> resolves official ATV studio track immediately.
        val second =
            resolver.resolveInitialTarget(
                videoId = "KLD_OyCSloA",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.Found("HzYrZoALCCA")
            }

        assertEquals("HzYrZoALCCA", second.targetId)
        assertTrue(second.upgradedBeforePlayer)
        assertEquals(2, lookups)
        assertEquals("HzYrZoALCCA", resolver.mappedAtvOf("KLD_OyCSloA"))
    }

    @Test
    fun confirmedNotFoundIsCachedUntilTtlExpiresAndOverriddenByDurationMismatch() {
        var nowMs = 1_000L
        val resolver =
            OmvAudioTargetResolver(
                negativeTtlMs = 60_000L,
                clockMs = { nowMs },
            )
        var lookups = 0

        val first =
            resolver.resolveInitialTarget(
                videoId = "OMV_ONLY_ID",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.NotFound
            }

        assertEquals("OMV_ONLY_ID", first.targetId)
        assertFalse(first.upgradedBeforePlayer)
        assertTrue(first.preResolvedAtvAttempted)
        assertTrue(resolver.hasNegativeEntry("OMV_ONLY_ID"))
        assertFalse(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "OMV_ONLY_ID",
                resolvedSourceId = "OMV_ONLY_ID",
                preResolvedAtvAttempted = first.preResolvedAtvAttempted,
                hasDurationMismatch = true,
            ),
        )

        // Within TTL, repeat pre-lookup is skipped — unless a real duration mismatch is detected post-player.
        nowMs += 30_000L
        val second =
            resolver.resolveInitialTarget(
                videoId = "OMV_ONLY_ID",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.Found("SHOULD_NOT_RUN")
            }
        assertEquals("OMV_ONLY_ID", second.targetId)
        assertEquals(1, lookups)
        assertFalse(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "OMV_ONLY_ID",
                resolvedSourceId = "OMV_ONLY_ID",
                preResolvedAtvAttempted = second.preResolvedAtvAttempted,
                hasDurationMismatch = false,
            ),
        )
        assertTrue(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "OMV_ONLY_ID",
                resolvedSourceId = "OMV_ONLY_ID",
                preResolvedAtvAttempted = second.preResolvedAtvAttempted,
                hasDurationMismatch = true,
            ),
        )

        // Once TTL expires, pre-lookup runs again automatically.
        nowMs += 31_000L
        assertFalse(resolver.hasNegativeEntry("OMV_ONLY_ID"))
        val third =
            resolver.resolveInitialTarget(
                videoId = "OMV_ONLY_ID",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.Found("NEW_ATV_ID")
            }
        assertEquals("NEW_ATV_ID", third.targetId)
        assertTrue(third.upgradedBeforePlayer)
        assertEquals(2, lookups)
    }

    @Test
    fun watchVideoModeAndNonOmvTracksSkipPrePlayerLookup() {
        val resolver = OmvAudioTargetResolver()
        var lookups = 0

        val videoMode =
            resolver.resolveInitialTarget(
                videoId = "ukbiRcyzrpc",
                localVideoType = MUSIC_VIDEO_TYPE_OMV,
                allowAudioUpgrade = false,
            ) {
                lookups++
                AudioVariantResolution.Found("OCY9vN4QzqY")
            }
        assertEquals("ukbiRcyzrpc", videoMode.targetId)
        assertFalse(videoMode.preResolvedAtvAttempted)
        assertEquals(0, lookups)

        val atvTrack =
            resolver.resolveInitialTarget(
                videoId = "HzYrZoALCCA",
                localVideoType = MUSIC_VIDEO_TYPE_ATV,
                allowAudioUpgrade = true,
            ) {
                lookups++
                AudioVariantResolution.Found("SHOULD_NOT_RUN")
            }
        assertEquals("HzYrZoALCCA", atvTrack.targetId)
        assertFalse(atvTrack.preResolvedAtvAttempted)
        assertEquals(0, lookups)
        assertTrue(
            resolver.shouldAttemptPostPlayerLookup(
                videoId = "HzYrZoALCCA",
                resolvedSourceId = "HzYrZoALCCA",
                preResolvedAtvAttempted = atvTrack.preResolvedAtvAttempted,
            ),
        )
    }

    @Test
    fun seedAndSnapshotPositiveMappingsRespectLruAndSessionPrecedence() {
        val resolver = OmvAudioTargetResolver(maxPositiveEntries = 2)
        resolver.recordPositive("KLD_OyCSloA", "HzYrZoALCCA")
        resolver.seedPositiveMappings(
            mapOf(
                "OLD_OMV" to "OLD_ATV",
                "KLD_OyCSloA" to "STALE_ATV",
                "MID_OMV" to "MID_ATV",
            ),
        )

        val snapshot = resolver.snapshotPositiveMappings()
        assertEquals(2, snapshot.size)
        // Session mapping for KLD_OyCSloA wins over stale persisted entry and stays newest.
        assertEquals("HzYrZoALCCA", snapshot["KLD_OyCSloA"])
        assertEquals("MID_ATV", snapshot["MID_OMV"])
        assertNull(snapshot["OLD_OMV"])
    }

    @Test
    fun evictMappingClearsCachedAtvAfterFallback() {
        val resolver = OmvAudioTargetResolver()
        resolver.recordPositive("ukbiRcyzrpc", "OCY9vN4QzqY")
        assertEquals("OCY9vN4QzqY", resolver.mappedAtvOf("ukbiRcyzrpc"))

        resolver.evictMapping("ukbiRcyzrpc")
        assertNull(resolver.mappedAtvOf("ukbiRcyzrpc"))
    }
}
