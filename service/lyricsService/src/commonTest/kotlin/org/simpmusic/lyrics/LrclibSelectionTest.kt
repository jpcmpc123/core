package org.simpmusic.lyrics

import kotlinx.serialization.json.Json
import org.simpmusic.lyrics.models.response.LrclibObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LrclibSelectionTest {
    @Test
    fun prefersWordSyncAmongDurationMatches() {
        val plain = record(1, 137f)
        val rich = record(2, 139f, hasWordSync = true)
        assertEquals(rich, selectLrclibLyricsRecord(listOf(plain, rich), 137))
    }

    @Test
    fun wordSyncNeverBypassesDurationTolerance() {
        val outside = record(1, 148f, hasWordSync = true)
        val matching = record(2, 137f)
        assertEquals(matching, selectLrclibLyricsRecord(listOf(outside, matching), 137))
        assertNull(selectLrclibLyricsRecord(listOf(outside), 137))
    }

    @Test
    fun enforcesStrictFiveSecondBoundaryForSyncedRecordsAndFallsBackToPlain() {
        val plain = record(1, 137f, syncedLyrics = null, plainLyrics = "Plain")
        val tooFarSynced = record(2, 145f, hasWordSync = true, plainLyrics = null)
        assertEquals(plain, selectLrclibLyricsRecord(listOf(plain, tooFarSynced), 137))

        val withinFiveSec = record(3, 142f, hasWordSync = true)
        assertEquals(withinFiveSec, selectLrclibLyricsRecord(listOf(plain, withinFiveSec), 137))
    }

    @Test
    fun prefersClosestDurationAmongWordSyncMatches() {
        val twoSecOff = record(2, 139f, hasWordSync = true)
        val exactDuration = record(3, 137f, hasWordSync = true)
        assertEquals(exactDuration, selectLrclibLyricsRecord(listOf(record(1, 137f), twoSecOff, exactDuration), 137))
    }

    @Test
    fun allowsExactDurationLiveCatalogMatchAndStripsSyncWhenDurationDiffers() {
        val exactCatalogLive =
            record(
                id = 26056296,
                duration = 169.56f,
                hasWordSync = true,
                plainLyrics = "Valei-me Deus, é o fim do nosso amor",
                syncedLyrics = "[00:12.00]Valei-me Deus, é o fim do nosso amor",
            ).copy(trackName = "Flor de Lis", artistName = "Alcione/Emilio Santiago", albumName = "Casa de Samba, Vol. 2")

        val pickedExact =
            selectLrclibLyricsRecord(
                records = listOf(exactCatalogLive),
                duration = 169,
                expectedTrack = "Flor De Lis (Ao Vivo)",
                expectedArtist = "Emílio Santiago, Alcione",
            )
        assertEquals(exactCatalogLive, pickedExact)

        // A studio cut off by 4s must NOT supply synced timing to an (Ao Vivo) track;
        // it safely falls back to static plainLyrics (syncedLyrics stripped).
        val driftedStudio =
            record(
                id = 10,
                duration = 173f,
                plainLyrics = "Valei-me Deus, é o fim do nosso amor",
                syncedLyrics = "[00:12.00]Valei-me Deus, é o fim do nosso amor",
            ).copy(trackName = "Flor de Lis", artistName = "Emílio Santiago", albumName = "Luz Studio")

        val pickedDrifted =
            selectLrclibLyricsRecord(
                records = listOf(driftedStudio),
                duration = 169,
                expectedTrack = "Flor De Lis (Ao Vivo)",
                expectedArtist = "Emílio Santiago",
            )
        assertEquals("Valei-me Deus, é o fim do nosso amor", pickedDrifted?.plainLyrics)
        assertNull(pickedDrifted?.syncedLyrics)
    }

    @Test
    fun rejectsDifferentEditionSyncedAndStripsSyncToPlainWhenSameTitleDiffersInEditionTag() {
        val remixSynced =
            record(
                id = 20,
                duration = 180f,
                plainLyrics = "Normal lyrics text",
                syncedLyrics = "[00:10.00]Remix timed line",
            ).copy(trackName = "Example - Remix", artistName = "Example")
        // Verify edition mismatch strips sync when candidate matches via fallback or rejects sync
        val picked =
            selectLrclibLyricsRecord(
                records = listOf(remixSynced),
                duration = 180,
                expectedTrack = "Example",
                expectedArtist = "Example",
            )
        assertNull(picked)
    }

    @Test
    fun keepsClosestDurationMatchWhenNoWordSyncExists() {
        val twoSecOff = record(2, 139f)
        val exact = record(3, 137f)
        assertEquals(exact, selectLrclibLyricsRecord(listOf(record(1, 200f), twoSecOff, exact), 137))
    }

    @Test
    fun unknownDurationStillPrefersWordSync() {
        val rich = record(2, 200f, hasWordSync = true)
        assertEquals(rich, selectLrclibLyricsRecord(listOf(record(1, 137f), rich), null))
    }

    @Test
    fun unknownDurationWithoutWordSyncKeepsFirstResult() {
        val first = record(1, 137f)
        assertEquals(first, selectLrclibLyricsRecord(listOf(first, record(2, 200f)), null))
    }

    @Test
    fun durationZeroAndNegativeTreatedAsUnknown() {
        val song = record(1, 200f, hasWordSync = false)
        assertEquals(song, selectLrclibLyricsRecord(listOf(song), 0))
        assertEquals(song, selectLrclibLyricsRecord(listOf(song), -1))
    }

    @Test
    fun emptyResultsStillReturnNull() {
        assertNull(selectLrclibLyricsRecord(emptyList(), null))
        assertNull(selectLrclibLyricsRecord(emptyList(), 137))
    }

    @Test
    fun decodesTheServerWordSyncFlag() {
        val source =
            """
            {
              "id": 1, "name": "Example", "trackName": "Example", "artistName": "Example",
              "albumName": null, "duration": 137, "instrumental": false,
              "plainLyrics": null, "syncedLyrics": null, "hasWordSync": true
            }
            """.trimIndent()
        assertEquals(true, Json.decodeFromString<LrclibObject>(source).hasWordSync)
    }

    @Test
    fun filtersOutInstrumentalAndEmptyLyricsAndPrefersSyncedOverPlain() {
        val instrumental = record(1, 137f, instrumental = true)
        val blankBoth = record(2, 137f, plainLyrics = "   ", syncedLyrics = null)
        val plainOnly = record(3, 137f, plainLyrics = "Plain words", syncedLyrics = null)
        val synced = record(4, 138f, plainLyrics = "Plain", syncedLyrics = "[00:01.00]Synced")

        assertEquals(synced, selectLrclibLyricsRecord(listOf(instrumental, blankBoth, plainOnly, synced), 137))
        assertEquals(plainOnly, selectLrclibLyricsRecord(listOf(instrumental, blankBoth, plainOnly), 137))
        assertNull(selectLrclibLyricsRecord(listOf(instrumental, blankBoth), 137))
    }

    @Test
    fun rejectsWrongArtistAndConfirmsMatchingInstrumentalForSameTitle() {
        val jpnInstrumental =
            record(id = 1251726, duration = 137f, instrumental = true, plainLyrics = null, syncedLyrics = null)
                .copy(trackName = "Bloom", artistName = "j^p^n")
        val paperKitesWrongArtist =
            record(id = 9999, duration = 137f, instrumental = false, syncedLyrics = "[00:01.00]In the morning when I wake")
                .copy(trackName = "Bloom", artistName = "The Paper Kites")

        val picked =
            selectLrclibLyricsRecord(
                records = listOf(jpnInstrumental, paperKitesWrongArtist),
                duration = 137,
                expectedTrack = "Bloom",
                expectedArtist = "j^p^n",
            )
        assertNull(picked)
        kotlin.test.assertTrue(
            hasMatchingInstrumentalRecord(
                records = listOf(jpnInstrumental, paperKitesWrongArtist),
                duration = 137,
                expectedTrack = "Bloom",
                expectedArtist = "j^p^n",
            ),
        )
    }

    @Test
    fun prefersProperlyTimedRecordOverOverflowingRecordOfSameDuration() {
        val shiftedOverflow =
            record(
                id = 8428733,
                duration = 141f,
                syncedLyrics =
                    """
                    [00:18.77] Eles se perguntam quando que eu vou parar
                    [02:16.05] E cê não vai falir
                    [02:21.51] I guess you wonder where I've been
                    [02:22.16] I searched to find a love within
                    [02:25.82] 
                    """.trimIndent(),
            ).copy(trackName = "Aham", artistName = "Massaru")
        val properlyTimed =
            record(
                id = 10020903,
                duration = 141f,
                syncedLyrics =
                    """
                    [00:10.38] Eles se perguntam quando que eu vou parar
                    [02:10.03] E cê não vai falir
                    [02:11.68] I guess you wonder where I've been
                    [02:16.65] I searched to find a love within
                    [02:19.65] 
                    """.trimIndent(),
            ).copy(trackName = "Aham", artistName = "Massaru")

        val picked =
            selectLrclibLyricsRecord(
                records = listOf(shiftedOverflow, properlyTimed),
                duration = 141,
                expectedTrack = "Aham",
                expectedArtist = "Massaru",
            )
        assertEquals(properlyTimed, picked)
    }

    private fun record(
        id: Int,
        duration: Float,
        hasWordSync: Boolean = false,
        instrumental: Boolean = false,
        plainLyrics: String? = "Example",
        syncedLyrics: String? = "[00:01.00]Example",
    ): LrclibObject =
        LrclibObject(
            id = id,
            name = "Example",
            trackName = "Example",
            artistName = "Example",
            albumName = "Example",
            duration = duration,
            instrumental = instrumental,
            plainLyrics = plainLyrics,
            syncedLyrics = syncedLyrics,
            hasWordSync = hasWordSync,
        )
}
