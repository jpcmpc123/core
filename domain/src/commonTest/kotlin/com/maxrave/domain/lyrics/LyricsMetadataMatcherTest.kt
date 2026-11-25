package com.maxrave.domain.lyrics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricsMetadataMatcherTest {
    @Test
    fun rejectsStudioSyncForLiveRecordingAndAllowsMatchingLiveCut() {
        assertFalse(
            LyricsMetadataMatcher.editionsCompatibleForSync(
                expectedTitle = "Flor De Lis (Ao Vivo)",
                candidateTitle = "Flor De Lis",
                candidateAlbum = "Luz",
            ),
        )
        assertTrue(
            LyricsMetadataMatcher.editionsCompatibleForSync(
                expectedTitle = "Flor De Lis (Ao Vivo)",
                candidateTitle = "Flor De Lis - Ao Vivo",
                candidateAlbum = "Emílio Santiago Ao Vivo",
            ),
        )
    }

    @Test
    fun rejectsRemixSlowedAndAcousticCrossMatchesWithoutFalsePositiveOnSongTitles() {
        assertFalse(
            LyricsMetadataMatcher.editionsCompatibleForSync(
                expectedTitle = "Midnight City (Slowed + Reverb)",
                candidateTitle = "Midnight City",
            ),
        )
        assertFalse(
            LyricsMetadataMatcher.editionsCompatibleForSync(
                expectedTitle = "Blinding Lights",
                candidateTitle = "Blinding Lights (Acoustic)",
            ),
        )
        // Normal song titles containing words like "Live" must not be treated as a live recording tag.
        assertEquals(emptySet(), LyricsMetadataMatcher.extractEditionTags("Live Wire"))
        assertTrue(
            LyricsMetadataMatcher.editionsCompatibleForSync(
                expectedTitle = "Live Wire",
                candidateTitle = "Live Wire",
            ),
        )
    }
}
