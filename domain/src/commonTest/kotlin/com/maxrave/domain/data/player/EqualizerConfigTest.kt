package com.maxrave.domain.data.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EqualizerConfigTest {
    @Test
    fun computePeakResponseDbCapturesAdjacentBandOverlap() {
        val bassBoost =
            EqualizerConfig(
                enabled = true,
                bandsDb = listOf(6f, 5.5f, 4.5f, 2.5f, 0.5f, 0f, 0f, 0f, 0f, 0f),
                enhancements = EqualizerEnhancements(subsonicFilterEnabled = false),
            )
        val peakDb = computePeakResponseDb(bassBoost, 48_000.0)
        assertTrue(
            peakDb > 7.0f,
            "Overlapping bass bands should produce > 7 dB summed peak (was $peakDb)",
        )

        // Balanced mode preserves punch while gently reclaiming headroom above +1 dB.
        val balancedPre = bassBoost.copy(enhancements = bassBoost.enhancements.copy(preampMode = PreampMode.AUTO_BALANCED)).effectivePreampDb()
        assertTrue(balancedPre in -4.5f..-1.5f, "AUTO_BALANCED preamp should sit cleanly between -4.5 and -1.5 dB (was $balancedPre)")

        // Strict mode cuts full peak + 0.5 dB margin.
        val strictPre = bassBoost.copy(enhancements = bassBoost.enhancements.copy(preampMode = PreampMode.AUTO_STRICT)).effectivePreampDb()
        assertTrue(strictPre <= -(peakDb + 0.4f), "AUTO_STRICT preamp should cover full summed peak (was $strictPre, peak=$peakDb)")
    }

    @Test
    fun savedEqualizerProfileDetectsMatchesAndModifications() {
        val enh = EqualizerEnhancements(bassBoostDb = 3.5f, trebleBoostDb = 2f, stereoWidth = 1.2f, spatialStage = 0.3f)
        val profile =
            SavedEqualizerProfile(
                id = "p1",
                name = "Galaxy Buds Core",
                bandsDb = listOf(2f, 2f, 0f, 0f, 0f, 0.5f, 1.5f, 2f, 1.5f, 2f),
                preampMode = enh.preampMode,
                bassBoostDb = enh.bassBoostDb,
                trebleBoostDb = enh.trebleBoostDb,
                stereoWidth = enh.stereoWidth,
                bassMonoEnabled = enh.bassMonoEnabled,
                spatialStage = enh.spatialStage,
                loudnessAmount = enh.loudnessAmount,
                subsonicFilterEnabled = enh.subsonicFilterEnabled,
                limiterEnabled = enh.limiterEnabled,
            )

        assertTrue(profile.matches(profile.bandsDb, 0f, enh, null))
        assertFalse(profile.matches(listOf(4f, 2f, 0f, 0f, 0f, 0.5f, 1.5f, 2f, 1.5f, 2f), 0f, enh, null))
        assertFalse(profile.matches(profile.bandsDb, 0f, enh.copy(bassBoostDb = 5f), null))
        assertFalse(profile.matches(profile.bandsDb, 0f, enh.copy(spatialStage = 0f), null))
    }

    @Test
    fun audioOutputKeyForProducesDeviceSpecificAndFallbackKeys() {
        val buds = AudioOutput(id = "bt1", name = "Galaxy Buds Core", kind = AudioOutputKind.BLUETOOTH, isActive = true)
        assertEquals("BLUETOOTH:Galaxy Buds Core", audioOutputKeyFor(buds))
        assertEquals("BLUETOOTH", audioOutputFallbackKeyFor(buds))

        val speaker = AudioOutput(id = "spk", name = "Phone Speaker", kind = AudioOutputKind.DEVICE_SPEAKER, isActive = true)
        assertEquals("DEVICE_SPEAKER", audioOutputKeyFor(speaker))
    }

    @Test
    fun stereoWidthAndSpatialStageReserveHeadroomEvenWhenTrebleIsCut() {
        val wideNegativeTreble =
            EqualizerConfig(
                enabled = true,
                bandsDb = emptyList(),
                enhancements =
                    EqualizerEnhancements(
                        preampMode = PreampMode.AUTO_BALANCED,
                        trebleBoostDb = -2f,
                        stereoWidth = 1.35f,
                        spatialStage = 0.35f,
                        subsonicFilterEnabled = false,
                    ),
            )
        val peak = computePeakResponseDb(wideNegativeTreble, 48_000.0)
        assertTrue(peak > 1.2f, "Expected positive combined peak from stereoWidth + spatialStage (got $peak)")
        val pre = wideNegativeTreble.effectivePreampDb(48_000.0)
        assertTrue(pre < -0.3f, "Expected AUTO_BALANCED to allocate negative preamp for stereo/spatial headroom (got $pre)")
    }

    @Test
    fun highShelfCoefficientsRemainFiniteWithoutResonantNanForAllValidQ() {
        for (q in listOf(0.3f, 0.7071f, 1.0f, 1.41f, 2.0f)) {
            val coeffs =
                computeNormalizedBiquad(
                    ParametricBand(BiquadFilterKind.HIGH_SHELF, 16_000f, 9f, q),
                    48_000.0,
                )
            assertTrue(coeffs != null && coeffs.all { it.isFinite() }, "Expected valid finite HIGH_SHELF coeffs for q=$q")
        }
    }
}
