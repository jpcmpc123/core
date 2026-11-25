package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Line
import com.maxrave.domain.data.model.metadata.Lyrics
import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsHeaderSanitizerTest {
    @Test
    fun removesRichSyncAndLineSyncTitleArtistHeaderAtZeroSeconds() {
        val lyrics =
            Lyrics(
                error = false,
                syncType = "RICH_SYNCED",
                lines =
                    listOf(
                        line("0", "<00:00.00>Manic <00:00.20>Pixie <00:00.40>Dream <00:00.60>Girl <00:00.80>- <00:01.00>EASHA"),
                        line("12400", "<00:12.40>Meet <00:12.70>cute <00:13.00>so <00:13.20>soon"),
                        line("16000", "But we make up any sign from above"),
                    ),
            )

        val sanitized =
            LyricsHeaderSanitizer.sanitize(
                lyrics = lyrics,
                songTitle = "Manic Pixie Dream Girl",
                artistName = "EASHA",
            )

        assertEquals(2, sanitized.lines?.size)
        assertEquals("12400", sanitized.lines?.first()?.startTimeMs)
    }

    @Test
    fun removesSwappedArtistTitleWithFeatureSuffixAndAccents() {
        val lyrics =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("200", "The Killers – Miss Atomic Bomb"),
                        line("10500", "You were standing with your girlfriends in the street"),
                    ),
            )

        val sanitized =
            LyricsHeaderSanitizer.sanitize(
                lyrics = lyrics,
                songTitle = "Miss Atomic Bomb (Official Audio)",
                artistName = "The Killers, Brandon Flowers",
            )

        assertEquals(listOf("You were standing with your girlfriends in the street"), sanitized.lines?.map { it.words })
    }

    @Test
    fun removesConsecutiveTitleAndArtistHeaderPairButKeepsTitleSungAloneAsVerse() {
        val paired =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("0", "Manic Pixie Dream Girl"),
                        line("1500", "EASHA"),
                        line("11000", "Meet cute so soon"),
                    ),
            )
        val sanitizedPaired = LyricsHeaderSanitizer.sanitize(paired, "Manic Pixie Dream Girl", "EASHA")
        assertEquals(listOf("Meet cute so soon"), sanitizedPaired.lines?.map { it.words })

        // A real song opening by singing its own title must NOT be stripped.
        val realOpeningChorus =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("500", "Miss Atomic Bomb"),
                        line("4200", "Making out we've got the radio on"),
                    ),
            )
        val untouched = LyricsHeaderSanitizer.sanitize(realOpeningChorus, "Miss Atomic Bomb", "The Killers")
        assertEquals(2, untouched.lines?.size)
    }

    @Test
    fun removesExplicitCreditLinesAtStartAndEnd() {
        val lyrics =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("0", "Lyrics by: Someone"),
                        line("1000", "Written by: Author"),
                        line("9000", "Real first verse"),
                        line("180000", "Real last verse"),
                        line("185000", "Synced by Community"),
                    ),
            )

        val sanitized = LyricsHeaderSanitizer.sanitize(lyrics, "Some Song", "Some Artist")
        assertEquals(listOf("Real first verse", "Real last verse"), sanitized.lines?.map { it.words })
    }

    @Test
    fun removesBilingualParentheticalTitleAndArtistHeaderLikeYoungGirlA() {
        val lyrics =
            Lyrics(
                error = false,
                syncType = "RICH_SYNCED",
                lines =
                    listOf(
                        line("0", "少女A（Young Girl A）- 椎名もた（ぽわぽわP）/ 鏡音鈴（鏡音リン）"),
                        line("14000", "僕の命っつったって"),
                        line("18000", "誰の命っつったって"),
                    ),
            )

        val sanitized =
            LyricsHeaderSanitizer.sanitize(
                lyrics = lyrics,
                songTitle = "Young Girl A (feat. Kagamine Rin)",
                artistName = "椎名もた, 鏡音リン",
            )
        assertEquals(listOf("僕の命っつったって", "誰の命っつったって"), sanitized.lines?.map { it.words })
    }

    @Test
    fun cleansTrailingCommasAndSinglePeriodsWhileKeepingExpressivePunctuation() {
        assertEquals(
            "Depois de tudo que passei olhando para você aqui previsto",
            LyricsHeaderSanitizer.cleanTranslatedLinePunctuation("Depois de tudo que passei olhando para você aqui previsto,"),
        )
        assertEquals(
            "Os segredos dentro do seu peito",
            LyricsHeaderSanitizer.cleanTranslatedLinePunctuation("Os segredos dentro do seu peito."),
        )
        assertEquals(
            "Se eu gosto dela?",
            LyricsHeaderSanitizer.cleanTranslatedLinePunctuation("Se eu gosto dela?"),
        )
        assertEquals(
            "Espere por mim...",
            LyricsHeaderSanitizer.cleanTranslatedLinePunctuation("Espere por mim..."),
        )
        assertEquals(
            "Olhe para mim!",
            LyricsHeaderSanitizer.cleanTranslatedLinePunctuation("Olhe para mim!"),
        )
    }

    @Test
    fun removesStandaloneJapaneseLyricistCreditAtStartLikeNorikoMiura() {
        val lyrics =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("0", "三浦徳子"),
                        line("18500", "夜空に向かって走る車のライト"),
                        line("24000", "ふたりだけの夜に消えてゆく"),
                    ),
            )

        val sanitized =
            LyricsHeaderSanitizer.sanitize(
                lyrics = lyrics,
                songTitle = "夜空のイヤリング",
                artistName = "八神純子",
            )

        assertEquals(
            listOf("夜空に向かって走る車のライト", "ふたりだけの夜に消えてゆく"),
            sanitized.lines?.map { it.words },
        )
    }

    @Test
    fun removesJapaneseCreditPrefixesAndExtraCreditNamesFromDescription() {
        val creditNames =
            LyricsHeaderSanitizer.extractCreditNamesFromDescription(
                """
                Provided to YouTube by Victor Entertainment
                Composer Lyricist: Noriko Miura / Junko Yagami
                Auto-generated by YouTube.
                """.trimIndent(),
            )
        val lyrics =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("0", "作詞：三浦徳子"),
                        line("1000", "Noriko Miura"),
                        line("9500", "夜空に向かって走る車のライト"),
                    ),
            )

        val sanitized =
            LyricsHeaderSanitizer.sanitize(
                lyrics = lyrics,
                songTitle = "夜空のイヤリング",
                artistName = "八神純子",
                extraCreditNames = creditNames,
            )

        assertEquals(listOf("夜空に向かって走る車のライト"), sanitized.lines?.map { it.words })
    }

    @Test
    fun preservesShortRealOpeningLineWhenFollowedSoonBySingingOrRepeated() {
        val repeatedAdlib =
            Lyrics(
                error = false,
                syncType = "LINE_SYNCED",
                lines =
                    listOf(
                        line("500", "愛してる"),
                        line("2500", "君だけをずっと"),
                        line("45000", "愛してる"),
                    ),
            )

        val sanitized = LyricsHeaderSanitizer.sanitize(repeatedAdlib, "愛してる", "Artist")
        assertEquals(3, sanitized.lines?.size)
    }

    private fun line(
        startMs: String,
        words: String,
    ) = Line(
        startTimeMs = startMs,
        endTimeMs = (startMs.toLong() + 1000L).toString(),
        syllables = null,
        words = words,
    )
}
