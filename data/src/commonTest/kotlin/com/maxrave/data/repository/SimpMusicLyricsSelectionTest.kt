package com.maxrave.data.repository

import org.simpmusic.lyrics.models.response.LyricsResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SimpMusicLyricsSelectionTest {
    @Test
    fun prefersRichSyncedOverHigherVotedLineSynced() {
        val lineHighVote =
            candidate(
                id = "line-top",
                vote = 25,
                syncedLyrics = "[00:05.00]Line\n[00:10.00]Two",
                richSyncLyrics = null,
            )
        val richLowVote =
            candidate(
                id = "rich-low",
                vote = 1,
                syncedLyrics = null,
                richSyncLyrics = "[00:05.00] <00:05.00>Word <00:05.50>sync",
            )

        val picked = assertNotNull(pickBestSimpMusicLyrics(listOf(lineHighVote, richLowVote)))
        assertEquals("rich-low", picked.simpMusicLyrics?.id)
        assertEquals("RICH_SYNCED", picked.syncType)
    }

    @Test
    fun breaksSameQualityTiesByHigherVoteAndSkipsEmptyCandidates() {
        val unusable =
            candidate(
                id = "bad",
                vote = 99,
                syncedLyrics = null,
                richSyncLyrics = null,
                plainLyric = "   ",
            )
        val lineLowVote =
            candidate(
                id = "line-1",
                vote = 3,
                syncedLyrics = "[00:05.00]A\n[00:10.00]B",
            )
        val lineHighVote =
            candidate(
                id = "line-2",
                vote = 12,
                syncedLyrics = "[00:05.00]A\n[00:10.00]B",
            )

        val picked = assertNotNull(pickBestSimpMusicLyrics(listOf(unusable, lineLowVote, lineHighVote)))
        assertEquals("line-2", picked.simpMusicLyrics?.id)
        assertEquals(12, picked.simpMusicLyrics?.vote)
    }

    @Test
    fun returnsNullWhenAllCandidatesUnusable() {
        val unusable = candidate(id = "bad", vote = 10, syncedLyrics = null, richSyncLyrics = null, plainLyric = "")
        assertNull(pickBestSimpMusicLyrics(listOf(unusable)))
    }

    @Test
    fun rejectsNegativeVotesWrongArtistAndCorruptedAsrSubmissions() {
        val downvoted = candidate(id = "down", vote = -2, syncedLyrics = "[00:05.00]Good\n[00:10.00]Lyrics")
        val wrongArtist =
            candidate(id = "wrong-art", vote = 10, syncedLyrics = "[00:05.00]In the morning\n[00:10.00]When I wake")
                .copy(songTitle = "Bloom", artistName = "The Paper Kites", durationSeconds = 137)
        val asrJunk =
            candidate(
                id = "asr",
                vote = 5,
                syncedLyrics = "[00:01.00]belza que\n[00:03.00][Muzică]\n[00:06.00]soha\n[00:09.00][Muzică]",
            )

        assertNull(
            pickBestSimpMusicLyrics(
                candidates = listOf(downvoted, wrongArtist, asrJunk),
                expectedTitle = "Bloom",
                expectedArtist = "j^p^n",
                expectedDurationSeconds = 137,
            ),
        )
    }

    @Test
    fun rejectsSyncOutsideFiveSecondToleranceAndMismatchedLiveEditionAndFlagsUnvotedForVerification() {
        val tooFar =
            candidate(
                id = "far-12s",
                vote = 10,
                durationSeconds = 192,
                syncedLyrics = "[00:05.00]Line\n[00:10.00]Two",
            )
        val liveWrongEdition =
            candidate(
                id = "studio-for-live",
                vote = 10,
                songTitle = "Flor De Lis",
                albumName = "Luz Studio",
                durationSeconds = 180,
                syncedLyrics = "[00:05.00]Valei-me Deus\n[00:10.00]E o fim",
            )
        assertNull(
            pickBestSimpMusicCandidate(
                candidates = listOf(tooFar),
                expectedTitle = "Song",
                expectedArtist = "Artist",
                expectedDurationSeconds = 180,
            ),
        )
        assertNull(
            pickBestSimpMusicCandidate(
                candidates = listOf(liveWrongEdition),
                expectedTitle = "Flor De Lis (Ao Vivo)",
                expectedArtist = "Artist",
                expectedDurationSeconds = 180,
            ),
        )

        val unvoted =
            candidate(
                id = "zero-vote",
                vote = 0,
                durationSeconds = 182,
                syncedLyrics = "[00:05.00]Matching line one\n[00:10.00]Matching line two",
            )
        val picked =
            assertNotNull(
                pickBestSimpMusicCandidate(
                    candidates = listOf(unvoted),
                    expectedTitle = "Song",
                    expectedArtist = "Artist",
                    expectedDurationSeconds = 180,
                ),
            )
        kotlin.test.assertTrue(picked.requiresVerification)
    }

    private fun candidate(
        id: String,
        vote: Int,
        richSyncLyrics: String? = null,
        syncedLyrics: String? = null,
        plainLyric: String = "",
        songTitle: String = "Song",
        albumName: String = "Album",
        durationSeconds: Int = 180,
    ) = LyricsResponse(
        id = id,
        videoId = "vid1",
        songTitle = songTitle,
        artistName = "Artist",
        albumName = albumName,
        durationSeconds = durationSeconds,
        plainLyric = plainLyric,
        syncedLyrics = syncedLyrics,
        richSyncLyrics = richSyncLyrics,
        trackType = "SONG",
        vote = vote,
        contributor = "c",
        contributorEmail = "e",
    )
}
