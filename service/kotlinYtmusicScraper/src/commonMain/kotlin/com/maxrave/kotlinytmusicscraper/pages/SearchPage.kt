package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.Album
import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.kotlinytmusicscraper.models.Artist
import com.maxrave.kotlinytmusicscraper.models.ArtistItem
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.models.YTItem
import com.maxrave.kotlinytmusicscraper.models.getContinuation
import com.maxrave.kotlinytmusicscraper.models.oddElements
import com.maxrave.kotlinytmusicscraper.models.response.SearchResponse
import com.maxrave.kotlinytmusicscraper.models.splitBySeparator
import com.maxrave.kotlinytmusicscraper.utils.parseTime

data class SearchResult(
    val items: List<YTItem>,
    val listPodcast: List<PodcastItem>,
    val continuation: String? = null,
)

object SearchPage {
    fun parseSearchResponse(response: SearchResponse): SearchResult {
        val musicShelf =
            response.contents
                ?.tabbedSearchResultsRenderer
                ?.tabs
                ?.firstOrNull()
                ?.tabRenderer
                ?.content
                ?.sectionListRenderer
                ?.contents
                ?.lastOrNull { it.musicShelfRenderer != null }
                ?.musicShelfRenderer
        val contents = musicShelf?.contents.orEmpty()
        return SearchResult(
            items = contents.mapNotNull { toYTItem(it.musicResponsiveListItemRenderer) },
            listPodcast = contents.mapNotNull { toPodcast(it.musicResponsiveListItemRenderer) },
            continuation = musicShelf?.continuations?.getContinuation(),
        )
    }

    fun parseSearchContinuation(response: SearchResponse): SearchResult {
        val shelf = response.continuationContents?.musicShelfContinuation
        val contents = shelf?.contents.orEmpty()
        return SearchResult(
            items = contents.mapNotNull { toYTItem(it.musicResponsiveListItemRenderer) },
            listPodcast = contents.mapNotNull { toPodcast(it.musicResponsiveListItemRenderer) },
            continuation = shelf?.continuations?.getContinuation(),
        )
    }

    fun toPodcast(renderer: MusicResponsiveListItemRenderer?): PodcastItem? {
        return if (renderer == null) {
            null
        } else {
            val authorRun =
                renderer.flexColumns
                    .lastOrNull()
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.firstOrNull()
            PodcastItem(
                id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                title =
                    renderer.flexColumns
                        .firstOrNull()
                        ?.musicResponsiveListItemFlexColumnRenderer
                        ?.text
                        ?.runs
                        ?.firstOrNull()
                        ?.text
                        ?: return null,
                author =
                    Artist(
                        name = authorRun?.text ?: "",
                        id = authorRun?.navigationEndpoint?.browseEndpoint?.browseId,
                    ),
                thumbnail = renderer.thumbnail?.musicThumbnailRenderer?.thumbnail ?: return null,
            )
        }
    }

    fun toYTItem(renderer: MusicResponsiveListItemRenderer?): YTItem? {
        if (renderer == null) {
            return null
        } else {
            val secondaryLine =
                renderer.flexColumns
                    .getOrNull(1)
                    ?.musicResponsiveListItemFlexColumnRenderer
                    ?.text
                    ?.runs
                    ?.splitBySeparator()
                    ?: emptyList()
            return when {
                renderer.isSong -> {
                    SongItem(
                        id = renderer.videoId ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            secondaryLine.firstOrNull()?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: emptyList(),
                        album =
                            secondaryLine
                                .getOrNull(1)
                                ?.firstOrNull()
                                ?.let { run ->
                                    run.navigationEndpoint?.browseEndpoint?.browseId?.let { browseId ->
                                        Album(
                                            name = run.text,
                                            id = browseId,
                                        )
                                    }
                                },
                        duration =
                            secondaryLine
                                .lastOrNull()
                                ?.firstOrNull()
                                ?.text
                                ?.parseTime(),
                        thumbnail =
                            renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: return null,
                        explicit =
                            renderer.badges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                        thumbnails = renderer.thumbnail.musicThumbnailRenderer.thumbnail,
                        musicVideoType = renderer.musicVideoType,
                        isLive = renderer.isLive,
                    )
                }

                renderer.isArtist -> {
                    ArtistItem(
                        id = renderer.navigationEndpoint?.browseEndpoint?.browseId ?: return null,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text
                                ?: return null,
                        thumbnail =
                            renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: return null,
                        shuffleEndpoint =
                            renderer.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            renderer.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                    )
                }

                renderer.isAlbum -> {
                    AlbumItem(
                        browseId =
                            renderer.navigationEndpoint?.browseEndpoint?.browseId
                                ?: return null,
                        playlistId = renderer.playlistId ?: "",
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        artists =
                            secondaryLine.getOrNull(1)?.oddElements()?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: emptyList(),
                        year =
                            secondaryLine
                                .getOrNull(2)
                                ?.firstOrNull()
                                ?.text
                                ?.toIntOrNull(),
                        thumbnail =
                            renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: return null,
                        explicit =
                            renderer.badges?.find {
                                it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                            } != null,
                    )
                }

                renderer.isPlaylist -> {
                    val id =
                        renderer.navigationEndpoint
                            ?.browseEndpoint
                            ?.browseId
                            ?.removePrefix("VL")
                            ?: return null
                    PlaylistItem(
                        id = id,
                        title =
                            renderer.flexColumns
                                .firstOrNull()
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.firstOrNull()
                                ?.text ?: return null,
                        author =
                            secondaryLine.firstOrNull()?.firstOrNull()?.let {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            },
                        songCountText =
                            renderer.flexColumns
                                .getOrNull(1)
                                ?.musicResponsiveListItemFlexColumnRenderer
                                ?.text
                                ?.runs
                                ?.lastOrNull()
                                ?.text,
                        thumbnail =
                            renderer.thumbnail?.musicThumbnailRenderer?.getThumbnailUrl()
                                ?: return null,
                        playEndpoint =
                            renderer.overlay
                                ?.musicItemThumbnailOverlayRenderer
                                ?.content
                                ?.musicPlayButtonRenderer
                                ?.playNavigationEndpoint
                                ?.watchPlaylistEndpoint
                                ?: WatchEndpoint(playlistId = id),
                        shuffleEndpoint =
                            renderer.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MUSIC_SHUFFLE" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                        radioEndpoint =
                            renderer.menu
                                ?.menuRenderer
                                ?.items
                                ?.find { it.menuNavigationItemRenderer?.icon?.iconType == "MIX" }
                                ?.menuNavigationItemRenderer
                                ?.navigationEndpoint
                                ?.watchPlaylistEndpoint,
                    )
                }

                else -> null
            }
        }
    }
}

data class PodcastItem(
    val id: String,
    val title: String,
    val author: Artist,
    val thumbnail: Thumbnails,
)