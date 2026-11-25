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
                durationSeconds = 184,
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

        val yagamiRealPayload =
            candidate(
                id = "69c21ee50010278b4afd",
                vote = 0,
                songTitle = "夜空のイヤリング",
                albumName = "LONELY GIRL",
                durationSeconds = 238,
                syncedLyrics = "[00:15.09] 夜空に向って走る車のライト\n[00:24.05] あなたと寄り添えば 流星になるのよ...",
                richSyncLyrics =
                    "[00:07.22] <00:07.22>三 <00:08.12>浦 <00:09.03>徳 <00:09.93>子\n" +
                        "[00:16.26] <00:16.26>夜 <00:16.63>空 <00:17.90>に <00:19.16>向 <00:19.44>っ <00:19.73>て <00:21.41>走 <00:21.65>る <00:22.01>車 <00:22.44>の <00:22.86>ラ <00:23.23>イ <00:23.85>ト\n" +
                        "[00:24.82] <00:24.82>あ <00:25.12>な <00:25.34>た <00:26.06>と <00:27.11>寄 <00:27.36>り <00:27.60>添 <00:27.95>え <00:28.24>ば",
            ).copy(artistName = "八神純子")
        val cleanedYagami =
            assertNotNull(
                pickBestSimpMusicLyrics(
                    candidates = listOf(yagamiRealPayload),
                    expectedTitle = "夜空のイヤリング",
                    expectedArtist = "八神純子",
                    expectedDurationSeconds = 238,
                ),
            )
        assertEquals("16260", cleanedYagami.lines?.first()?.startTimeMs)
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
