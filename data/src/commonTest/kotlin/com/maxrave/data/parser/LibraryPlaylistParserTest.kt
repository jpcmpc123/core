package com.maxrave.data.parser

import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryPlaylistParserTest {
    @Test
    fun extractsAuthorAndItemCountFromPortugueseSubtitleRuns() {
        val (author, count) =
            extractLibraryPlaylistSubtitle(
                listOf("Playlist", " • ", "Algoritmo da Zueira", " • ", "62 itens"),
            )
        assertEquals("Algoritmo da Zueira", author)
        assertEquals("62 itens", count)
    }

    @Test
    fun extractsAuthorAndItemCountFromEnglishSubtitleRuns() {
        val (author, count) =
            extractLibraryPlaylistSubtitle(
                listOf("Playlist", " • ", "Channel Name", " • ", "15 songs"),
            )
        assertEquals("Channel Name", author)
        assertEquals("15 songs", count)
    }

    @Test
    fun extractsAuthorWithoutItemCount() {
        val (author, count) =
            extractLibraryPlaylistSubtitle(
                listOf("Playlist", " • ", "YouTube Music"),
            )
        assertEquals("YouTube Music", author)
        assertEquals("", count)
    }

    @Test
    fun handlesSingleItemCountTokenAndEmptyRuns() {
        assertEquals("" to "12 itens", extractLibraryPlaylistSubtitle(listOf("12 itens")))
        assertEquals("" to "", extractLibraryPlaylistSubtitle(emptyList()))
        assertEquals("" to "", extractLibraryPlaylistSubtitle(null))
    }
}
