package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.mapping.toDomainSearchSuggestions
import com.maxrave.data.parser.parsePodcast
import com.maxrave.data.parser.search.parseSearchAlbum
import com.maxrave.data.parser.search.parseSearchArtist
import com.maxrave.data.parser.search.parseSearchPlaylist
import com.maxrave.data.parser.search.parseSearchSong
import com.maxrave.data.parser.search.parseSearchVideo
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.model.searchResult.PagedSearchResult
import com.maxrave.domain.data.model.searchResult.SearchSuggestions
import com.maxrave.domain.data.model.searchResult.albums.AlbumsResult
import com.maxrave.domain.data.model.searchResult.artists.ArtistsResult
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.SongsResult
import com.maxrave.domain.data.model.searchResult.videos.VideosResult
import com.maxrave.domain.repository.SearchRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.pages.SearchResult
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

internal class SearchRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : SearchRepository {
    override fun getSearchHistory(): Flow<List<SearchHistory>> =
        flow {
            emit(localDataSource.getSearchHistory())
        }.flowOn(Dispatchers.IO)

    override fun insertSearchHistory(searchHistory: SearchHistory): Flow<Long> =
        flow {
            emit(localDataSource.insertSearchHistory(searchHistory))
        }.flowOn(Dispatchers.IO)

    override suspend fun deleteSearchHistory() =
        withContext(Dispatchers.IO) {
            localDataSource.deleteSearchHistory()
        }

    override suspend fun deleteSearchHistoryByQuery(query: String) =
        withContext(Dispatchers.IO) {
            localDataSource.deleteSearchHistoryByQuery(query)
        }

    private fun <T> fetchPagedSearch(
        query: String,
        filter: YouTube.SearchFilter,
        continuation: String?,
        parser: (SearchResult) -> List<T>,
    ): Flow<Resource<PagedSearchResult<T>>> =
        flow {
            val responseResult =
                if (continuation.isNullOrBlank()) {
                    youTube.search(query, filter)
                } else {
                    youTube.searchContinuation(continuation)
                }
            responseResult
                .onSuccess { result ->
                    emit(
                        Resource.Success(
                            PagedSearchResult(
                                items = parser(result),
                                continuation = result.continuation,
                            ),
                        ),
                    )
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Logger.d("Search", "Error: ${e.message}")
                    emit(Resource.Error(e.message ?: "Search failed"))
                }
        }.flowOn(Dispatchers.IO)

    private fun <T> fetchSearchLegacy(
        query: String,
        filter: YouTube.SearchFilter,
        parser: (SearchResult) -> ArrayList<T>,
    ): Flow<Resource<ArrayList<T>>> =
        flow {
            youTube
                .search(query, filter)
                .onSuccess { result ->
                    emit(Resource.Success(parser(result)))
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Logger.d("Search", "Error: ${e.message}")
                    emit(Resource.Error(e.message ?: "Search failed"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getSearchDataSong(query: String): Flow<Resource<ArrayList<SongsResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_SONG, ::parseSearchSong)

    override fun getSearchDataVideo(query: String): Flow<Resource<ArrayList<VideosResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_VIDEO, ::parseSearchVideo)

    override fun getSearchDataPodcast(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_PODCAST) { result ->
            parsePodcast(result.listPodcast)
        }

    override fun getSearchDataFeaturedPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST, ::parseSearchPlaylist)

    override fun getSearchDataArtist(query: String): Flow<Resource<ArrayList<ArtistsResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_ARTIST, ::parseSearchArtist)

    override fun getSearchDataAlbum(query: String): Flow<Resource<ArrayList<AlbumsResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_ALBUM, ::parseSearchAlbum)

    override fun getSearchDataPlaylist(query: String): Flow<Resource<ArrayList<PlaylistsResult>>> =
        fetchSearchLegacy(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST, ::parseSearchPlaylist)

    override fun searchSongsPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<SongsResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_SONG, continuation, ::parseSearchSong)

    override fun searchVideosPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<VideosResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_VIDEO, continuation, ::parseSearchVideo)

    override fun searchPodcastsPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<PlaylistsResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_PODCAST, continuation) { result ->
            parsePodcast(result.listPodcast)
        }

    override fun searchFeaturedPlaylistsPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<PlaylistsResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_FEATURED_PLAYLIST, continuation, ::parseSearchPlaylist)

    override fun searchArtistsPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<ArtistsResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_ARTIST, continuation, ::parseSearchArtist)

    override fun searchAlbumsPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<AlbumsResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_ALBUM, continuation, ::parseSearchAlbum)

    override fun searchPlaylistsPaged(
        query: String,
        continuation: String?,
    ): Flow<Resource<PagedSearchResult<PlaylistsResult>>> =
        fetchPagedSearch(query, YouTube.SearchFilter.FILTER_COMMUNITY_PLAYLIST, continuation, ::parseSearchPlaylist)

    override fun getSuggestQuery(query: String): Flow<Resource<SearchSuggestions>> =
        flow {
            youTube
                .getYTMusicSearchSuggestions(query)
                .onSuccess {
                    emit(Resource.Success(it.toDomainSearchSuggestions()))
                }.onFailure { e ->
                    if (e is CancellationException) throw e
                    Logger.d("Suggest", "Error: ${e.message}")
                    emit(Resource.Error<SearchSuggestions>(e.message.toString()))
                }
        }.flowOn(Dispatchers.IO)
}