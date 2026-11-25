package com.maxrave.data.repository

import com.maxrave.common.ITAG
import com.maxrave.common.QUALITY
import com.maxrave.data.mediaservice.computeVolumeNormalization
import com.maxrave.kotlinytmusicscraper.models.response.PlayerResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class StreamRepositoryAudioSelectionTest {
    private fun audioFormat(
        itag: Int,
        bitrate: Int,
        url: String,
        mimeType: String = "audio/webm; codecs=\"opus\"",
        isDrc: Boolean? = null,
    ) = PlayerResponse.StreamingData.Format(
        itag = itag,
        url = url,
        mimeType = mimeType,
        bitrate = bitrate,
        width = null,
        height = null,
        contentLength = 3_000_000L,
        quality = "tiny",
        fps = null,
        qualityLabel = null,
        averageBitrate = bitrate,
        audioQuality = "AUDIO_QUALITY_MEDIUM",
        approxDurationMs = "200000",
        audioSampleRate = if (mimeType.contains("opus")) 48_000 else 44_100,
        audioChannels = 2,
        loudnessDb = -2.0,
        lastModified = null,
        signatureCipher = null,
        isDrc = isDrc,
    )

    @Test
    fun qualityDefaultPointsToOpus251InsteadOf66kbps() {
        assertEquals("Medium - 129kps", QUALITY.defaultItem)
        assertEquals("Medium - 129kps", QUALITY.normalize(null))
        assertEquals("Medium - 129kps", QUALITY.normalize("unknown-legacy-value"))
        assertEquals(ITAG.AUDIO_OPUS_MEDIUM, QUALITY.itagOf(QUALITY.defaultItem))
        assertEquals(ITAG.AUDIO_OPUS_MEDIUM, QUALITY.itagOf(null))
    }

    @Test
    fun freeAccountAskingForHigh256PrefersOpus251NonDrcOverHigherBitrateAac140And251Drc() {
        val opus251Drc =
            audioFormat(
                itag = ITAG.AUDIO_OPUS_MEDIUM,
                bitrate = 142_000,
                url = "https://rr1.googlevideo.com/videoplayback?itag=251&dur=200.0&xtags=acont%3Doriginal%3Adrc%3D1%3Alang%3Den",
                isDrc = true,
            )
        val aac140Clean =
            audioFormat(
                itag = ITAG.AUDIO_AAC_MEDIUM,
                bitrate = 131_000,
                url = "https://rr1.googlevideo.com/videoplayback?itag=140&dur=200.0&xtags=acont%3Doriginal%3Alang%3Den",
                mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
            )
        val opus251Clean =
            audioFormat(
                itag = ITAG.AUDIO_OPUS_MEDIUM,
                bitrate = 125_000,
                url = "https://rr1.googlevideo.com/videoplayback?itag=251&dur=200.0&xtags=acont%3Doriginal%3Alang%3Den",
            )

        val chosen =
            selectBestAudioFormat(
                formats = listOf(opus251Drc, aac140Clean, opus251Clean),
                requestedItag = ITAG.AUDIO_OPUS_HIGH,
                preferredLanguage = null,
                expectedDurationSec = 200,
            )

        assertNotNull(chosen)
        assertEquals(ITAG.AUDIO_OPUS_MEDIUM, chosen.itag)
        assertFalse(chosen.isDrcAudio)
        assertEquals(opus251Clean.url, chosen.url)
    }

    @Test
    fun premiumAccountGetsRequested256NonDrc() {
        val opus251Clean =
            audioFormat(
                itag = ITAG.AUDIO_OPUS_MEDIUM,
                bitrate = 138_000,
                url = "https://rr1.googlevideo.com/videoplayback?itag=251&dur=200.0",
            )
        val opus774Clean =
            audioFormat(
                itag = ITAG.AUDIO_OPUS_HIGH,
                bitrate = 256_000,
                url = "https://rr1.googlevideo.com/videoplayback?itag=774&dur=200.0",
            )

        val chosen =
            selectBestAudioFormat(
                formats = listOf(opus251Clean, opus774Clean),
                requestedItag = ITAG.AUDIO_OPUS_HIGH,
                preferredLanguage = null,
                expectedDurationSec = 200,
            )

        assertNotNull(chosen)
        assertEquals(ITAG.AUDIO_OPUS_HIGH, chosen.itag)
    }

    @Test
    fun fallsBackToDrcOnlyWhenNoNonDrcStreamExists() {
        val opus251Drc =
            audioFormat(
                itag = ITAG.AUDIO_OPUS_MEDIUM,
                bitrate = 130_000,
                url = "https://rr1.googlevideo.com/videoplayback?itag=251&dur=200.0&xtags=drc%3D1",
                isDrc = true,
            )
        val chosen =
            selectBestAudioFormat(
                formats = listOf(opus251Drc),
                requestedItag = ITAG.AUDIO_OPUS_MEDIUM,
                preferredLanguage = null,
                expectedDurationSec = 200,
            )
        assertNotNull(chosen)
        assertTrue(chosen.isDrcAudio)
    }

    @Test
    fun computeVolumeNormalizationNeverEmitsNegativeEnhancerGain() {
        val loudTrack = computeVolumeNormalization(6.0f)
        assertTrue(loudTrack.playerVolume in 0.49f..0.51f)
        assertEquals(0, loudTrack.enhancerGainMb)

        val quietTrack = computeVolumeNormalization(-4.0f)
        assertEquals(1.0f, quietTrack.playerVolume)
        assertEquals(400, quietTrack.enhancerGainMb)

        val veryQuietTrack = computeVolumeNormalization(-15.0f)
        assertEquals(1.0f, veryQuietTrack.playerVolume)
        assertEquals(600, veryQuietTrack.enhancerGainMb)

        val neutralTrack = computeVolumeNormalization(0f)
        assertEquals(1.0f, neutralTrack.playerVolume)
        assertEquals(0, neutralTrack.enhancerGainMb)

        val invalidTrack = computeVolumeNormalization(null)
        assertEquals(1.0f, invalidTrack.playerVolume)
        assertEquals(0, invalidTrack.enhancerGainMb)
    }
}
