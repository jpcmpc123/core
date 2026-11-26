package com.maxrave.data.repository

import com.maxrave.data.db.datasource.queryInSqlChunks
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SongChunkingTest {
    @Test
    fun queryInSqlChunksSplitsLargePlaylistsBelowSqliteParameterLimitAndDeduplicates() {
        val ids = (1..1250).map { "vid_$it" } + listOf("vid_1", "vid_500", "vid_1250")
        val chunkSizes = mutableListOf<Int>()

        val result =
            queryInSqlChunks(ids, chunkSize = 500) { chunk ->
                chunkSizes += chunk.size
                chunk.map { "row:$it" }
            }

        assertEquals(listOf(500, 500, 250), chunkSizes)
        assertEquals(1250, result.size)
        assertTrue(chunkSizes.all { it <= 500 })
        assertEquals("row:vid_1", result.first())
        assertEquals("row:vid_1250", result.last())
    }

    @Test
    fun queryInSqlChunksSkipsEmptyInput() {
        var calls = 0
        val result =
            queryInSqlChunks(emptyList()) {
                calls++
                it
            }
        assertEquals(0, calls)
        assertTrue(result.isEmpty())
    }
}
