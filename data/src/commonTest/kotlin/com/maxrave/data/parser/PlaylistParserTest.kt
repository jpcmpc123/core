package com.maxrave.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class PlaylistParserTest {
    @Test
    fun localizedEmptyPlaylistCountsReturnZeroWithoutError() {
        assertEquals(0, parsePlaylistTrackCount("Nenhum"))
        assertEquals(0, parsePlaylistTrackCount("Nenhuma música"))
        assertEquals(0, parsePlaylistTrackCount("No tracks"))
        assertEquals(0, parsePlaylistTrackCount(""))
        assertEquals(0, parsePlaylistTrackCount(null))
    }

    @Test
    fun numericAndFormattedTrackCountsParseCleanly() {
        assertEquals(1, parsePlaylistTrackCount("1 música"))
        assertEquals(42, parsePlaylistTrackCount("42 songs"))
        assertEquals(1234, parsePlaylistTrackCount("1.234 músicas"))
        assertEquals(2500, parsePlaylistTrackCount("2,500 tracks"))
    }
}
