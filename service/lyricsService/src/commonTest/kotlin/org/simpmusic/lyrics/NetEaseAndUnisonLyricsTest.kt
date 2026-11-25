package org.simpmusic.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.lyrics.LyricsHeaderSanitizer
import org.simpmusic.lyrics.models.response.NetEaseAlbum
import org.simpmusic.lyrics.models.response.NetEaseArtist
import org.simpmusic.lyrics.models.response.NetEaseSong
import org.simpmusic.lyrics.models.response.UnisonLyricsData
import org.simpmusic.lyrics.models.response.UnisonSubmitter
import org.simpmusic.lyrics.parser.parseNetEaseYrcLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NetEaseAndUnisonLyricsTest {
    @Test
    fun parsesNetEaseYrcAndStripsCreditscleanly() {
        val rawYrc =
            """
            {"t":0,"c":[{"tx":"作词: Max Martin"}]}
            [0,1000](0,1000,0) 制作人 : The Weeknd/Max Martin
            [1000,1000](1000,1000,0) 作词 : Max Martin
            [23550,120](23550,120,0)Yeah
            [27360,1290](27360,240,0)I've (27600,90,0)been (27690,360,0)tryna (28050,600,0)call
            [30150,2610](30150,240,0)I've (30390,210,0)been (30600,120,0)on (30720,240,0)my (30960,210,0)own (31170,240,0)for (31410,420,0)long (31830,930,0)enough
            """.trimIndent()

        val parsed = parseNetEaseYrcLyrics(rawYrc)
        assertEquals("RICH_SYNCED", parsed.lyrics?.syncType)
        val domainLyrics =
            Lyrics(
                error = false,
                lines =
                    parsed.lyrics?.lines?.map {
                        Line(
                            endTimeMs = it.endTimeMs,
                            startTimeMs = it.startTimeMs,
                            syllables = it.syllables ?: emptyList(),
                            words = it.words,
                        )
                    },
                syncType = parsed.lyrics?.syncType,
            )
        val sanitized = LyricsHeaderSanitizer.sanitize(domainLyrics, "Blinding Lights", "The Weeknd")
        assertEquals(3, sanitized.lines?.size)
        assertEquals("23550", sanitized.lines?.get(0)?.startTimeMs)
        assertEquals("<00:27.36>I've <00:27.60>been <00:27.69>tryna <00:28.05>call", sanitized.lines?.get(1)?.words)
    }

    @Test
    fun ranksNetEaseCandidatesWithStrictDurationAndEditionGuards() {
        val songs =
            listOf(
                NetEaseSong(
                    id = 1L,
                    name = "夜空のイヤリング (Instrumental)",
                    duration = 238_000L,
                    artists = listOf(NetEaseArtist(name = "八神純子")),
                ),
                NetEaseSong(
                    id = 2L,
                    name = "夜空のイヤリング",
                    duration = 260_000L,
                    artists = listOf(NetEaseArtist(name = "八神純子")),
                ),
                NetEaseSong(
                    id = 3L,
                    name = "夜空のイヤリング",
                    duration = 238_100L,
                    artists = listOf(NetEaseArtist(name = "八神純子")),
                    album = NetEaseAlbum(name = "Lonely Girl"),
                ),
            )

        val ranked = rankNetEaseCandidates(songs, "夜空のイヤリング", "八神純子", durationSeconds = 238)
        assertEquals(1, ranked.size)
        assertEquals(3L, ranked.single().first.id)
        assertTrue(ranked.single().second)
    }

    @Test
    fun parsesUnisonPayloadRespectsVariantConfidenceAndSubmitterAttribution() {
        val ttml =
            """
            <tt xmlns="http://www.w3.org/ns/ttml" xmlns:itunes="http://music.apple.com/lyric-ttml-internal" itunes:timing="Word">
              <body>
                <div>
                  <p begin="0:12.00" end="0:15.00"><span begin="0:12.00" end="0:12.50">Hello </span><span begin="0:12.50" end="0:13.20">world</span></p>
                  <p begin="0:15.50" end="0:18.00"><span begin="0:15.50" end="0:16.20">Second </span><span begin="0:16.20" end="0:17.00">line</span></p>
                </div>
              </body>
            </tt>
            """.trimIndent()

        val valid =
            parseUnisonPayload(
                data =
                    UnisonLyricsData(
                        id = 10L,
                        videoId = "vid123",
                        song = "Hello Song",
                        artist = "Some Artist",
                        lyrics = ttml,
                        format = "ttml",
                        syncType = "richsync",
                        confidence = "high",
                        variant = "primary",
                        submitter = UnisonSubmitter(keyId = "abc123", displayName = "MaxCurator"),
                    ),
                expectedTrack = "Hello Song",
                expectedArtist = "Some Artist",
                durationSeconds = 180,
                matchedByVideoId = true,
                requestedVideoId = "vid123",
            )
        val found = assertIs<UnisonSearchResult.Found>(valid)
        assertEquals("RICH_SYNCED", found.lyrics.lyrics?.syncType)
        assertEquals("MaxCurator", found.submitterDisplayName)
        assertEquals("abc123", found.submitterKeyId)
        assertFalse(found.requiresVerification)

        val lowConfidence =
            parseUnisonPayload(
                data =
                    UnisonLyricsData(
                        id = 11L,
                        videoId = "vid123",
                        song = "Hello Song",
                        artist = "Some Artist",
                        lyrics = "[00:12.00]Hello world",
                        format = "lrc",
                        confidence = "low",
                        variant = "primary",
                    ),
                expectedTrack = "Hello Song",
                expectedArtist = "Some Artist",
                durationSeconds = 180,
                matchedByVideoId = true,
                requestedVideoId = "vid123",
            )
        val lowFound = assertIs<UnisonSearchResult.Found>(lowConfidence)
        assertTrue(lowFound.requiresVerification)

        val nonPrimary =
            parseUnisonPayload(
                data =
                    UnisonLyricsData(
                        id = 12L,
                        videoId = "vid123",
                        song = "Hello Song",
                        artist = "Some Artist",
                        lyrics = "[00:12.00]Hello world",
                        format = "lrc",
                        variant = "explore",
                    ),
                expectedTrack = "Hello Song",
                expectedArtist = "Some Artist",
                durationSeconds = 180,
                matchedByVideoId = true,
                requestedVideoId = "vid123",
            )
        assertIs<UnisonSearchResult.NotFound>(nonPrimary)
    }
}
