package com.maxrave.media3.di

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.amr.AmrExtractor
import androidx.media3.extractor.flac.FlacExtractor
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp3.Mp3Extractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.ogg.OggExtractor
import androidx.media3.extractor.ts.Ac3Extractor
import androidx.media3.extractor.ts.Ac4Extractor
import androidx.media3.extractor.ts.AdtsExtractor
import androidx.media3.extractor.wav.WavExtractor
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class LocalDeviceExtractorFactoryTest {
    @Test
    fun localDeviceExtractorFactorySupportsAllStandardOfflineAudioFormats() {
        val extractorClasses =
            provideLocalDeviceExtractorFactory()
                .createExtractors()
                .map { it::class.java }
                .toSet()

        assertTrue("MP3 extractor missing", Mp3Extractor::class.java in extractorClasses)
        assertTrue("FLAC extractor missing", FlacExtractor::class.java in extractorClasses)
        assertTrue("WAV extractor missing", WavExtractor::class.java in extractorClasses)
        assertTrue("OGG/Opus extractor missing", OggExtractor::class.java in extractorClasses)
        assertTrue("AAC ADTS extractor missing", AdtsExtractor::class.java in extractorClasses)
        assertTrue("MP4/M4A extractor missing", Mp4Extractor::class.java in extractorClasses)
        assertTrue("Fragmented MP4 extractor missing", FragmentedMp4Extractor::class.java in extractorClasses)
        assertTrue("Matroska/WebM extractor missing", MatroskaExtractor::class.java in extractorClasses)
        assertTrue("AMR extractor missing", AmrExtractor::class.java in extractorClasses)
        assertTrue("AC3 extractor missing", Ac3Extractor::class.java in extractorClasses)
        assertTrue("AC4 extractor missing", Ac4Extractor::class.java in extractorClasses)
    }
}
