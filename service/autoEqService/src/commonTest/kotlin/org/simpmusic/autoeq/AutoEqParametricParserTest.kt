package org.simpmusic.autoeq

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutoEqParametricParserTest {
    @Test
    fun parsesParametricEqFiltersIncludingShelvesAndPreamp() {
        val raw =
            """
            Preamp: -4.2 dB
            Filter 1: ON PK Fc 105 Hz Gain -3.5 dB Q 0.70
            Filter 2: ON LSC Fc 105 Hz Gain 4.2 dB Q 0.71
            Filter 3: ON HSC Fc 10000 Hz Gain -2.0 dB Q 0.71
            """.trimIndent()

        val parsed = parseAutoEqParametricEq(raw)
        assertNotNull(parsed)
        assertFalse(parsed.isFixedBand)
        assertEquals(-4.2f, parsed.preampDb)
        assertEquals(3, parsed.filters.size)
        assertEquals(AutoEqFilterType.PEAKING, parsed.filters[0].type)
        assertEquals(AutoEqFilterType.LOW_SHELF, parsed.filters[1].type)
        assertEquals(AutoEqFilterType.HIGH_SHELF, parsed.filters[2].type)
    }

    @Test
    fun parsesWaveletGraphicEqIntoIsoBands() {
        val raw = "GraphicEQ: 20 2.0; 31 3.0; 62 1.5; 125 0.0; 1000 -1.0; 8000 2.5; 16000 3.0"
        val parsed = parseAutoEqTextProfile(raw)
        assertNotNull(parsed)
        assertTrue(parsed.isFixedBand)
        assertEquals(10, parsed.filters.size)
        assertTrue(kotlin.math.abs(parsed.filters[0].gainDb - 3.0f) < 0.05f)
        assertTrue(kotlin.math.abs(parsed.filters[1].gainDb - 1.5f) < 0.05f)
        assertTrue(parsed.preampDb <= -2.9f)
    }
}
