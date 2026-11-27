package com.maxrave.kotlinytmusicscraper.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientLoudnessScaleTest {
    @Test
    fun androidFallbackLoudnessMatchesWebRemixScaleFromRealLog() {
        // Real log track EEsCBJX8yYE: WEB_REMIX reported -3.6000004 dB while ANDROID reported +3.3999996 dB.
        val webRemix = normalizeLoudnessDbToYtMusicScale(YouTubeClient.WEB_REMIX, -3.6000004)!!
        val android = normalizeLoudnessDbToYtMusicScale(YouTubeClient.ANDROID, 3.3999996)!!
        assertEquals(-3.6000004, webRemix, 1e-6)
        assertEquals(-3.6000004, android, 1e-6)
    }

    @Test
    fun youtubeMusicClientsPreserveValueWhileVideoClientsShiftByMinus7Db() {
        assertEquals(-1.78, normalizeLoudnessDbToYtMusicScale(YouTubeClient.WEB_REMIX, -1.78)!!, 1e-6)
        assertEquals(-1.78, normalizeLoudnessDbToYtMusicScale(YouTubeClient.ANDROID_MUSIC, -1.78)!!, 1e-6)

        assertEquals(-7.0, normalizeLoudnessDbToYtMusicScale(YouTubeClient.IOS, 0.0)!!, 1e-6)
        assertEquals(-5.5, normalizeLoudnessDbToYtMusicScale(YouTubeClient.TVHTML5, 1.5)!!, 1e-6)
        assertEquals(-8.2, normalizeLoudnessDbToYtMusicScale(YouTubeClient.TVHTML5_SIMPLY, -1.2)!!, 1e-6)
        assertEquals(-10.6, normalizeLoudnessDbToYtMusicScale(YouTubeClient.VISIONOS, -3.6)!!, 1e-6)
    }

    @Test
    fun nullAndNonFiniteValuesAreHandledSafely() {
        assertNull(normalizeLoudnessDbToYtMusicScale(YouTubeClient.ANDROID, null))
        assertTrue(normalizeLoudnessDbToYtMusicScale(YouTubeClient.ANDROID, Double.NaN)!!.isNaN())
    }
}
