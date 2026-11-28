package com.maxrave.domain.extension

import com.maxrave.common.LibraryChipType
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.common.isLocalDeviceAudioId
import com.maxrave.common.localDeviceAudioContentUriString
import com.maxrave.common.localDeviceAudioId
import com.maxrave.common.localDeviceAudioMediaStoreId
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalDeviceAudioIdTest {
    @Test
    fun localDeviceAudioIdHelpersParseAndFormatCorrectly() {
        val id = localDeviceAudioId(42987L)
        assertEquals("local_device_audio:42987", id)
        assertTrue(isLocalDeviceAudioId(id))
        assertEquals(42987L, localDeviceAudioMediaStoreId(id))
        assertEquals("content://media/external/audio/media/42987", localDeviceAudioContentUriString(id))

        assertFalse(isLocalDeviceAudioId("dQw4w9WgXcQ"))
        assertFalse(isLocalDeviceAudioId(null))
        assertNull(localDeviceAudioMediaStoreId("dQw4w9WgXcQ"))
        assertNull(localDeviceAudioMediaStoreId("local_device_audio:notanumber"))
        assertNull(localDeviceAudioContentUriString("dQw4w9WgXcQ"))
    }

    @Test
    fun libraryChipTypeDeviceAudioRoundTrips() {
        val raw = LibraryChipType.DEVICE_AUDIO.toStringValue()
        assertEquals("device_audio", raw)
        assertEquals(LibraryChipType.DEVICE_AUDIO, LibraryChipType.fromStringValue(raw))
    }

    @Test
    fun localTrackConvertsToGenericMediaItemWithoutYouTubeThumbnailFallback() {
        val track =
            Track(
                album = Album(id = "42", name = "Offline Album"),
                artists = listOf(Artist(id = null, name = "Offline Artist")),
                duration = "3:25",
                durationSeconds = 205,
                isAvailable = true,
                isExplicit = false,
                likeStatus = "INDIFFERENT",
                thumbnails = listOf(Thumbnail(544, "content://media/external/audio/albumart/42", 544)),
                title = "Lossless Song",
                videoId = localDeviceAudioId(1234L),
                videoType = "LOCAL_DEVICE_AUDIO",
                category = "Local",
                feedbackTokens = null,
                resultType = "Song",
            )
        val mediaItem = track.toGenericMediaItem()
        assertEquals("local_device_audio:1234", mediaItem.mediaId)
        assertEquals("content://media/external/audio/media/1234", mediaItem.uri)
        assertEquals("content://media/external/audio/albumart/42", mediaItem.metadata.artworkUri)
        assertEquals(MERGING_DATA_TYPE.SONG, mediaItem.metadata.description)
        assertNull(mediaItem.customCacheKey)

        val noThumbItem = track.copy(thumbnails = null).toGenericMediaItem()
        assertNull(noThumbItem.metadata.artworkUri)
    }
}
