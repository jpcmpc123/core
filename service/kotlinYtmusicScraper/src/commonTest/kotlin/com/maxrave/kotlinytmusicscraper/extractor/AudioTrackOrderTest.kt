package com.maxrave.kotlinytmusicscraper.extractor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// xtags values copied from a live player response for 0e3GPea1Tyg (25 audio tracks per itag).
private fun url(
    xtags: String?,
    clen: Long = 1,
    dur: String? = null,
) = "https://rr1---sn-x.googlevideo.com/videoplayback?expire=1&itag=251" +
    (xtags?.let { "&xtags=$it" } ?: "") +
    "&clen=$clen" +
    (dur?.let { "&dur=$it" } ?: "") +
    "&mime=audio%2Fwebm"

private val ARABIC = url("acont%3Ddubbed%3Alang%3Dar", clen = 25099982)
private val VIETNAMESE_AUTO = url("acont%3Ddubbed-auto%3Alang%3Dvi")
private val ENGLISH_DESCRIPTIVE = url("acont%3Ddescriptive%3Alang%3Den")
private val ORIGINAL = url("acont%3Doriginal%3Alang%3Den-US", clen = 24140597)
private val SINGLE_TRACK = url(null)

class AudioTrackOrderTest {
    private val multiTrack = listOf(ARABIC, ENGLISH_DESCRIPTIVE, ORIGINAL, VIETNAMESE_AUTO).map { 251 to it }

    @Test
    fun noPreferencePicksOriginal() {
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack("").first { it.first == 251 }.second)
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack(null).first { it.first == 251 }.second)
    }

    @Test
    fun preferredLanguageWinsWhenPresent() {
        assertEquals(VIETNAMESE_AUTO, multiTrack.orderByAudioTrack("vi").first().second)
        assertEquals(VIETNAMESE_AUTO, multiTrack.orderByAudioTrack("VI").first().second)
    }

    @Test
    fun missingPreferredLanguageFallsBackToOriginal() {
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack("ja").first().second)
    }

    @Test
    fun descriptiveTrackIsNeverThePreferredOne() {
        // "en" matches both the descriptive track and the en-US original; only the original counts.
        assertEquals(ORIGINAL, multiTrack.orderByAudioTrack("en").first().second)
    }

    @Test
    fun singleTrackVideoKeepsItsOrder() {
        val streams = listOf(140 to SINGLE_TRACK, 251 to SINGLE_TRACK)
        assertEquals(streams, streams.orderByAudioTrack("vi"))
    }

    @Test
    fun contentLengthComesFromTheChosenUrl() {
        assertEquals(24140597L, contentLengthOf(ORIGINAL))
        assertEquals(25099982L, contentLengthOf(ARABIC))
    }

    @Test
    fun durationCompatibilityRejectsOmvIntroMismatchAndAcceptsAtv() {
        val atvUrl = url(null, dur = "198.721")
        val omvUrl = url(null, dur = "206.100")

        assertEquals(198.721, durationSecondsOf(atvUrl))
        assertEquals(206.1, durationSecondsOf(omvUrl))
        assertNull(durationSecondsOf(SINGLE_TRACK))

        assertTrue(isUrlDurationCompatible(atvUrl, expectedSeconds = 199))
        assertFalse(isUrlDurationCompatible(omvUrl, expectedSeconds = 199))
        assertTrue(isUrlDurationCompatible(SINGLE_TRACK, expectedSeconds = 199))
        assertTrue(isUrlDurationCompatible(omvUrl, expectedSeconds = null))
    }

    @Test
    fun isDrcAudioUrlDetectsEncodedAndDecodedFlags() {
        val encodedDrc = url("acont%3Doriginal%3Adrc%3D1%3Alang%3Den-US")
        val decodedDrc = "https://rr1---sn-x.googlevideo.com/videoplayback?itag=251&xtags=acont=original:drc=1:lang=pt"
        val booleanDrc = url("acont%3Doriginal%3Adrc%3Dtrue%3Alang%3Den")

        assertTrue(isDrcAudioUrl(encodedDrc))
        assertTrue(isDrcAudioUrl(decodedDrc))
        assertTrue(isDrcAudioUrl(booleanDrc))
        assertFalse(isDrcAudioUrl(ORIGINAL))
        assertFalse(isDrcAudioUrl(SINGLE_TRACK))
    }

    @Test
    fun nonDrcAlwaysBeatsDrcWithinSameLanguageAndOriginalTrack() {
        val originalDrc = url("acont%3Doriginal%3Adrc%3D1%3Alang%3Den-US", clen = 22000000)
        val originalClean = url("acont%3Doriginal%3Alang%3Den-US", clen = 24000000)
        val ptDrc = url("acont%3Ddubbed%3Adrc%3D1%3Alang%3Dpt", clen = 21000000)
        val ptClean = url("acont%3Ddubbed%3Alang%3Dpt", clen = 23000000)

        val orderedNoPref = listOf(251 to originalDrc, 251 to originalClean).orderByAudioTrack(null)
        assertEquals(originalClean, orderedNoPref.first().second)

        val orderedPt = listOf(251 to originalClean, 251 to ptDrc, 251 to ptClean).orderByAudioTrack("pt")
        assertEquals(ptClean, orderedPt.first().second)

        assertTrue(
            audioTrackRank(ORIGINAL, preferredLanguage = null, isDrcFlag = false) <
                audioTrackRank(ORIGINAL, preferredLanguage = null, isDrcFlag = true),
        )
    }
}
