package com.maxrave.domain.data.model.browse.playlist

import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult

data class DualPlaylistCreationResult(
    val localId: Long,
    val youtubePlaylistId: String? = null,
    val synced: Boolean = false,
    val errorMessage: String? = null,
)

data class PlaylistExtras(
    val reloadParams: String? = null,
    val suggestions: List<Track> = emptyList(),
    val relatedPlaylists: List<PlaylistsResult> = emptyList(),
)
