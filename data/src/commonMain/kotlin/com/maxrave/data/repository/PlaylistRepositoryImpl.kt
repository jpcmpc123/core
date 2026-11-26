package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.extension.getFullDataFromDB
import com.maxrave.data.mapping.toListTrack
import com.maxrave.data.mapping.toTrack
import com.maxrave.data.mapping.toYouTubeWatchEndpoint
import com.maxrave.data.parser.parseLibraryPlaylist
import com.maxrave.data.parser.parseNextLibraryPlaylist
import com.maxrave.data.parser.parsePlaylistData
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.playlist.Author
import com.maxrave.domain.data.model.browse.playlist.PlaylistBrowse
import com.maxrave.domain.data.model.browse.playlist.PlaylistExtras
import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.ChartItem
import com.maxrave.domain.data.type.PlaylistType
import com.maxrave.domain.extension.now
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.PlaylistRepository
import com.maxrave.domain.utils.Resource
import com.maxrave.domain.utils.isRadioMix
import com.maxrave.domain.utils.toTrack
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.kotlinytmusicscraper.extension.verifyYouTubePlaylistId
import com.maxrave.kotlinytmusicscraper.models.MusicShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.WatchEndpoint
import com.maxrave.kotlinytmusicscraper.pages.NextPage
import com.maxrave.kotlinytmusicscraper.pages.SearchPage
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistContinuation
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistRadioEndpoint
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistSectionContinuation
import com.maxrave.kotlinytmusicscraper.parser.getPlaylistShuffleEndpoint
import com.maxrave.kotlinytmusicscraper.parser.getRelatedPlaylistItems
import com.maxrave.kotlinytmusicscraper.parser.getReloadParams
import com.maxrave.kotlinytmusicscraper.parser.getSuggestionSongItems
import com.maxrave.kotlinytmusicscraper.parser.hasReloadParams
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime

internal class PlaylistRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val youTube: YouTube,
) : PlaylistRepository {
    override fun getAllPlaylists(limit: Int): Flow<List<PlaylistEntity>> =
        flow {
            emit(localDataSource.getAllPlaylists(limit))
        }.flowOn(Dispatchers.IO)

    override fun getPlaylist(id: String): Flow<PlaylistEntity?> =
        flow {
            emit(localDataSource.getPlaylist(id))
        }.flowOn(Dispatchers.IO)

    override fun getLikedPlaylists(): Flow<List<PlaylistEntity>> =
        flow {
            emit(
                getFullDataFromDB { limit, offset ->
                    localDataSource.getLikedPlaylists(limit, offset)
                },
            )
        }.flowOn(Dispatchers.IO)

    override suspend fun insertPlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) { localDataSource.insertPlaylist(playlistEntity) }

    override suspend fun insertAndReplacePlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) {
            val oldPlaylist = getPlaylist(playlistEntity.id).firstOrNull()
            if (oldPlaylist != null) {
                localDataSource.insertAndReplacePlaylist(
                    playlistEntity.copy(
                        downloadState = oldPlaylist.downloadState,
                        liked = oldPlaylist.liked,
                    ),
                )
            } else {
                localDataSource.insertAndReplacePlaylist(playlistEntity)
            }
        }

    override suspend fun insertRadioPlaylist(playlistEntity: PlaylistEntity) =
        withContext(Dispatchers.IO) { localDataSource.insertRadioPlaylist(playlistEntity) }

    override suspend fun updatePlaylistLiked(
        playlistId: String,
        likeStatus: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistLiked(
            likeStatus,
            playlistId,
        )
    }

    override suspend fun updatePlaylistInLibrary(
        inLibrary: LocalDateTime,
        playlistId: String,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistInLibrary(
            inLibrary,
            playlistId,
        )
    }

    override suspend fun updatePlaylistDownloadState(
        playlistId: String,
        downloadState: Int,
    ) = withContext(Dispatchers.Main) {
        localDataSource.updatePlaylistDownloadState(
            downloadState,
            playlistId,
        )
    }

    override fun getAllDownloadedPlaylist(): Flow<List<PlaylistType>> =
        flow { emit(localDataSource.getAllDownloadedPlaylist()) }.flowOn(Dispatchers.IO)

    override fun getAllDownloadingPlaylist(): Flow<List<PlaylistType>> =
        flow { emit(localDataSource.getAllDownloadingPlaylist()) }.flowOn(Dispatchers.IO)

    private suspend fun insertSetVideoId(setVideoId: SetVideoIdEntity) = withContext(Dispatchers.IO) { localDataSource.insertSetVideoId(setVideoId) }

    override fun getRadio(
        radioId: String,
        defaultDescription: String,
        radioString: String,
        viewString: String,
        originalTrack: SongEntity?,
        artist: ArtistEntity?,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        if (radioId.isRadioMix()) {
            getRDATRadioData(radioId, viewString)
        } else {
            flow {
                runCatching {
                    youTube
                        .next(endpoint = WatchEndpoint(playlistId = radioId))
                        .onSuccess { next ->
                            Logger.w("Radio", "Title: ${next.title}")
                            val data: ArrayList<SongItem> = arrayListOf()
                            data.addAll(next.items)
                            var continuation = next.continuation
                            Logger.w("Radio", "data: ${data.size}")
                            var count = 0
                            while (continuation != null && count < 3) {
                                youTube
                                    .next(
                                        endpoint = WatchEndpoint(playlistId = radioId),
                                        continuation = continuation,
                                    ).onSuccess { nextContinue ->
                                        data.addAll(nextContinue.items)
                                        continuation = nextContinue.continuation
                                        if (data.size >= 50) {
                                            count = 3
                                        }
                                        Logger.w("Radio", "data: ${data.size}")
                                        count++
                                    }.onFailure {
                                        count = 3
                                    }
                            }
                            val listTrackResult = data.toListTrack()
                            if (originalTrack != null) {
                                listTrackResult.add(0, originalTrack.toTrack())
                            }
                            Logger.w("Repository", "data: ${data.size}")
                            val playlistBrowse =
                                PlaylistBrowse(
                                    author = Author(id = "", name = "YouTube Music"),
                                    description =
                                    defaultDescription,
                                    duration = "",
                                    durationSeconds = 0,
                                    id = radioId,
                                    privacy = "PRIVATE",
                                    thumbnails =
                                        listOf(
                                            Thumbnail(
                                                544,
                                                originalTrack?.thumbnails ?: artist?.thumbnails ?: "",
                                                544,
                                            ),
                                        ),
                                    title = "${originalTrack?.title ?: artist?.name} $radioString",
                                    trackCount = listTrackResult.size,
                                    tracks = listTrackResult,
                                    year = now().year.toString(),
                                )
                            Logger.w("Repository", "playlistBrowse: $playlistBrowse")
                            emit(Resource.Success<Pair<PlaylistBrowse, String?>>(Pair(playlistBrowse, continuation)))
                        }.onFailure { exception ->
                            exception.printStackTrace()
                            emit(Resource.Error<Pair<PlaylistBrowse, String?>>(exception.message.toString()))
                        }
                }
            }.flowOn(Dispatchers.IO)
        }

    override fun getRDATRadioData(
        radioId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        flow<Resource<Pair<PlaylistBrowse, String?>>> {
            runCatching {
                val id =
                    if (radioId.startsWith("VL")) {
                        radioId
                    } else {
                        "VL$radioId"
                    }
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<MusicShelfRenderer.Content> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("Data", "data: $data")
                            Logger.d("Data", "data size: ${data.size}")
                            listContent.addAll(data)
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("Header", "header: $header")
                        val finalContinueParam =
                            result.getPlaylistContinuation()
                        Logger.d("Repository", "playlist data: ${listContent.size}")
                        Logger.d("Repository", "continueParam: $finalContinueParam")
//                        else {
//                            var listTrack = playlistBrowse.tracks.toMutableList()
                        Logger.d("Repository", "playlist final data: ${listContent.size}")
                        if (finalContinueParam != null) {
                            parsePlaylistData(header, listContent, radioId, viewString)?.let { playlist ->
                                emit(
                                    Resource.Success(
                                        Pair(
                                            playlist.copy(
                                                author = Author("", "YouTube Music"),
                                            ),
                                            finalContinueParam,
                                        ),
                                    ),
                                )
                            } ?: emit(Resource.Error("Can't parse data"))
                        } else {
                            emit(Resource.Error("Continue param is null"))
                        }
                    }.onFailure { e ->
                        Logger.e("Playlist Data", e.message ?: "Error")
                        emit(Resource.Error(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getFullPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<PlaylistBrowse>> =
        flow {
            runCatching {
                var id = ""
                id +=
                    if (!playlistId.startsWith("VL")) {
                        "VL$playlistId"
                    } else {
                        playlistId
                    }
                Logger.d("getPlaylistData", "playlist id: $id")
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<Track> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("getPlaylistData", "data: $data")
                            Logger.d("getPlaylistData", "data size: ${data.size}")
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        Logger.d("getPlaylistData", "header: $header")
                        var continueParam =
                            result.getPlaylistContinuation()
                        var count = 0
                        Logger.d("getPlaylistData", "playlist data: ${listContent.size}")
                        Logger.d("getPlaylistData", "continueParam: $continueParam")
//                        else {
//                            var listTrack = playlistBrowse.tracks.toMutableList()
                        while (continueParam != null) {
                            youTube
                                .customQuery(
                                    browseId = null,
                                    continuation = continueParam,
                                    setLogin = true,
                                ).onSuccess { values ->
                                    Logger.d("getPlaylistData", "continue: $continueParam")
                                    Logger.d(
                                        "getPlaylistData",
                                        "values: ${values.onResponseReceivedActions}",
                                    )
                                    val dataMore: List<SongItem> =
                                        values.onResponseReceivedActions
                                            ?.firstOrNull()
                                            ?.appendContinuationItemsAction
                                            ?.continuationItems
                                            ?.apply {
                                                Logger.w("getPlaylistData", "dataMore: ${this.size}")
                                            }?.mapNotNull {
                                                NextPage.fromMusicResponsiveListItemRenderer(
                                                    it.musicResponsiveListItemRenderer ?: return@mapNotNull null,
                                                )
                                            } ?: emptyList()
                                    listContent.addAll(dataMore.map { it.toTrack() })
                                    continueParam =
                                        values.getPlaylistContinuation()
                                    count++
                                }.onFailure {
                                    Logger.e("getPlaylistData", "Error: ${it.message}")
                                    continueParam = null
                                    count++
                                }
                        }
                        Logger.d("getPlaylistData", "playlist final data: ${listContent.size}")
                        parsePlaylistData(header, data ?: emptyList(), playlistId, viewString)?.let { playlist ->
                            emit(
                                Resource.Success<PlaylistBrowse>(
                                    playlist.copy(
                                        tracks =
                                            playlist.tracks.toMutableList().apply {
                                                addAll(listContent)
                                            },
                                        trackCount = (playlist.trackCount + listContent.size),
                                    ),
                                ),
                            )
                        } ?: emit(Resource.Error<PlaylistBrowse>("Error"))
                    }.onFailure { e ->
                        Logger.e("getPlaylistData", e.message ?: "Error")
                        emit(Resource.Error<PlaylistBrowse>(e.message.toString()))
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getPlaylistData(
        playlistId: String,
        viewString: String,
    ): Flow<Resource<Pair<PlaylistBrowse, String?>>> =
        flow {
            runCatching {
                var id = ""
                id +=
                    if (!playlistId.startsWith("VL")) {
                        "VL$playlistId"
                    } else {
                        playlistId
                    }
                Logger.d("getPlaylistData", "playlist id: $id")
                youTube
                    .customQuery(browseId = id, setLogin = true)
                    .onSuccess { result ->
                        val listContent: ArrayList<Track> = arrayListOf()
                        val data: List<MusicShelfRenderer.Content>? =
                            result.contents
                                ?.singleColumnBrowseResultsRenderer
                                ?.tabs
                                ?.get(
                                    0,
                                )?.tabRenderer
                                ?.content
                                ?.sectionListRenderer
                                ?.contents
                                ?.get(
                                    0,
                                )?.musicPlaylistShelfRenderer
                                ?.contents
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.secondaryContents
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicPlaylistShelfRenderer
                                    ?.contents
                        if (data != null) {
                            Logger.d("getPlaylistData", "data size: ${data.size}")
                        }
                        val header =
                            result.header?.musicDetailHeaderRenderer
                                ?: result.header?.musicEditablePlaylistDetailHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicResponsiveHeaderRenderer
                                ?: result.contents
                                    ?.twoColumnBrowseResultsRenderer
                                    ?.tabs
                                    ?.get(0)
                                    ?.tabRenderer
                                    ?.content
                                    ?.sectionListRenderer
                                    ?.contents
                                    ?.get(0)
                                    ?.musicEditablePlaylistDetailHeaderRenderer
                                    ?.header
                                    ?.musicResponsiveHeaderRenderer
                        val continueParam =
                            result.getPlaylistContinuation()
                        val extrasContinueParam =
                            result.getPlaylistSectionContinuation()
                        val inlineReloadParams =
                            if (result.hasReloadParams()) result.getReloadParams() else null
                        val inlineSuggestions =
                            if (inlineReloadParams != null) {
                                result.getSuggestionSongItems().map { it.toTrack() }
                            } else {
                                emptyList()
                            }
                        val inlineRelated =
                            result.getRelatedPlaylistItems().map { it.toPlaylistsResult() }
                        val inlineExtras =
                            if (inlineReloadParams != null || inlineSuggestions.isNotEmpty() || inlineRelated.isNotEmpty()) {
                                PlaylistExtras(
                                    reloadParams = inlineReloadParams,
                                    suggestions = inlineSuggestions,
                                    relatedPlaylists = inlineRelated,
                                )
                            } else {
                                null
                            }
                        val radioEndpoint =
                            result.getPlaylistRadioEndpoint()
                        val shuffleEndpoint =
                            result.getPlaylistShuffleEndpoint()
                        try {
                            parsePlaylistData(header, data ?: emptyList(), playlistId, viewString)?.let { playlist ->
                                emit(
                                    Resource.Success<Pair<PlaylistBrowse, String?>>(
                                        Pair(
                                            playlist.copy(
                                                tracks =
                                                    playlist.tracks.toMutableList().apply {
                                                        addAll(listContent)
                                                    },
                                                trackCount = (playlist.trackCount + listContent.size),
                                                shuffleEndpoint = shuffleEndpoint?.toYouTubeWatchEndpoint(),
                                                radioEndpoint = radioEndpoint?.toYouTubeWatchEndpoint(),
                                                extrasContinuation = extrasContinueParam,
                                                initialExtras = inlineExtras,
                                            ),
                                            continueParam,
                                        ),
                                    ),
                                )
                            } ?: emit(
                                Resource.Error<
                                    Pair<PlaylistBrowse, String?>,
                                >("Error"),
                            )
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            e.printStackTrace()
                            emit(
                                Resource.Error<
                                    Pair<PlaylistBrowse, String?>,
                                >(e.message.toString()),
                            )
                        }
                    }.onFailure { e ->
                        if (e is CancellationException) throw e
                        Logger.e("getPlaylistData", e.message ?: "Error")
                        emit(
                            Resource.Error<
                                Pair<PlaylistBrowse, String?>,
                            >(e.message.toString()),
                        )
                    }
            }
        }.flowOn(Dispatchers.IO)

    override fun getLibraryPlaylist(): Flow<List<PlaylistsResult>?> =
        flow {
            youTube
                .getLibraryPlaylists()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.get(
                                0,
                            )?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.get(
                                0,
                            )?.gridRenderer
                            ?.items
                    val listItem = mutableListOf<PlaylistsResult>()
                    if (input.isNullOrEmpty()) {
                        Logger.w("Library", "No playlists found")
                        emit(null)
                        return@onSuccess
                    }
                    listItem.addAll(
                        parseLibraryPlaylist(input),
                    )
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                Logger.w("Library", "continuation: $continuation")
                                val nextInput = nextData.first
                                listItem.addAll(
                                    parseNextLibraryPlaylist(nextInput),
                                )
                            }.onFailure { exception ->
                                exception.printStackTrace()
                                Logger.e("Library", "Error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) {
                        emit(listItem)
                        val account = localDataSource.getUsedGoogleAccount()
                        val isNeeded =
                            dataStoreManager.keepYouTubePlaylistOffline.first() == DataStoreManager.TRUE &&
                                dataStoreManager.loggedIn.first() == DataStoreManager.TRUE && account != null
                        if (isNeeded) {
                            insertYourYouTubePlaylist(
                                YourYouTubePlaylistList(
                                    emailPageId = "${account.email}_${account.pageId ?: ""}",
                                    listBrowseIds = listItem.map { it.browseId },
                                ),
                            )
                        }
                    } else {
                        emit(null)
                    }
                }.onFailure { e ->
                    Logger.e("Library", "Error: ${e.message}")
                    e.printStackTrace()
                    val account = localDataSource.getUsedGoogleAccount()
                    val isNeeded =
                        dataStoreManager.keepYouTubePlaylistOffline.first() == DataStoreManager.TRUE &&
                            dataStoreManager.loggedIn.first() == DataStoreManager.TRUE && account != null
                    if (isNeeded) {
                        val list =
                            getYourYouTubePlaylistList("${account.email}_${account.pageId ?: ""}")
                                .lastOrNull()
                        if (list != null) {
                            emit(
                                list.listBrowseIds.mapNotNull { id ->
                                    getPlaylist(id).lastOrNull()?.let {
                                        PlaylistsResult(
                                            author = it.author ?: "",
                                            browseId = it.id,
                                            category = "",
                                            itemCount = "${ it.trackCount }",
                                            resultType = "",
                                            thumbnails =
                                                listOf(
                                                    Thumbnail(
                                                        width = 544,
                                                        url = it.thumbnails,
                                                        height = 544,
                                                    ),
                                                ),
                                            title = it.title,
                                        )
                                    }
                                },
                            )
                        } else {
                            emit(null)
                        }
                    } else {
                        emit(null)
                    }
                }
        }.flowOn(Dispatchers.IO)

    override fun getMixedForYou(): Flow<List<PlaylistsResult>?> =
        flow {
            youTube
                .getMixedForYou()
                .onSuccess { data ->
                    val input =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.get(
                                0,
                            )?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.get(
                                0,
                            )?.gridRenderer
                            ?.items
                    val listItem = mutableListOf<PlaylistsResult>()
                    if (input.isNullOrEmpty()) {
                        Logger.w("Mixed For You", "No playlists found")
                        emit(null)
                        return@onSuccess
                    }
                    listItem.addAll(
                        parseLibraryPlaylist(input),
                    )
                    var continuation =
                        data.contents
                            ?.singleColumnBrowseResultsRenderer
                            ?.tabs
                            ?.firstOrNull()
                            ?.tabRenderer
                            ?.content
                            ?.sectionListRenderer
                            ?.contents
                            ?.firstOrNull()
                            ?.gridRenderer
                            ?.continuations
                            ?.firstOrNull()
                            ?.nextContinuationData
                            ?.continuation
                    while (continuation != null) {
                        youTube
                            .nextYouTubePlaylists(continuation)
                            .onSuccess { nextData ->
                                continuation = nextData.second
                                Logger.w("Mixed For You", "continuation: $continuation")
                                val nextInput = nextData.first
                                listItem.addAll(
                                    parseNextLibraryPlaylist(nextInput),
                                )
                            }.onFailure { exception ->
                                exception.printStackTrace()
                                Logger.e("Mixed For You", "Error: ${exception.message}")
                                continuation = null
                            }
                    }
                    if (listItem.isNotEmpty()) {
                        emit(listItem)
                    } else {
                        emit(null)
                    }
                }
        }.flowOn(Dispatchers.IO)

    override fun updateYourYouTubePlaylistTitle(
        playlistId: String,
        newTitle: String,
    ): Flow<Resource<String>> =
        flow {
            youTube
                .editPlaylist(playlistId, newTitle)
                .onSuccess {
                    emit(Resource.Success(it.toString()))
                }.onFailure {
                    emit(Resource.Error<String>(it.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override suspend fun insertYourYouTubePlaylist(yourYouTubePlaylist: YourYouTubePlaylistList) =
        withContext(Dispatchers.IO) {
            localDataSource.insertYourYouTubePlaylist(yourYouTubePlaylist)
        }

    override suspend fun deleteAllYourYouTubePlaylist() =
        withContext(Dispatchers.IO) {
            localDataSource.deleteAllYourYouTubePlaylist()
        }

    override fun getYourYouTubePlaylistList(emailPageId: String): Flow<YourYouTubePlaylistList?> =
        flow {
            emit(localDataSource.getYourYouTubePlaylistList(emailPageId))
        }.flowOn(Dispatchers.IO)

    override fun getChartPlaylist(): Flow<Resource<List<ChartItem>>> =
        flow {
            youTube
                .getSimpMusicChart()
                .onSuccess { response ->
                    val data = response.data?.filterNotNull() ?: emptyList()
                    val result =
                        data.mapNotNull {
                            ChartItem(
                                name = it.name ?: return@mapNotNull null,
                                ytPlaylistId = it.youtubePlaylistId ?: return@mapNotNull null,
                            )
                        }
                    emit(Resource.Success(result))
                }.onFailure { exception ->
                    exception.printStackTrace()
                    emit(Resource.Error<List<ChartItem>>(exception.message ?: "Unknown error"))
                }
        }.flowOn(Dispatchers.IO)

    override fun getPlaylistExtras(
        playlistId: String,
        initialContinuation: String?,
        initialExtras: PlaylistExtras?,
    ): Flow<Resource<PlaylistExtras>> =
        flow {
            val bareId = playlistId.removePrefix("VL")
            val hasInitialSuggestions =
                initialExtras != null &&
                    (!initialExtras.reloadParams.isNullOrEmpty() || initialExtras.suggestions.isNotEmpty())
            val hasInitialRelated = initialExtras?.relatedPlaylists?.isNotEmpty() == true
            if (initialExtras != null && hasInitialSuggestions && hasInitialRelated) {
                Logger.i(
                    "PlaylistExtras",
                    "id=$bareId inline=true suggestions=${initialExtras.suggestions.size} related=${initialExtras.relatedPlaylists.size}",
                )
                emit(Resource.Success(initialExtras))
                return@flow
            }
            if (initialExtras != null && initialContinuation.isNullOrBlank()) {
                Logger.i(
                    "PlaylistExtras",
                    "id=$bareId inlineOnly=true suggestions=${initialExtras.suggestions.size} related=${initialExtras.relatedPlaylists.size}",
                )
                emit(Resource.Success(initialExtras))
                return@flow
            }

            var result = youTube.getPlaylistSuggestionsAndRelated(bareId, initialContinuation = initialContinuation)
            if (result.isFailure) {
                val err = result.exceptionOrNull()
                if (err is CancellationException) throw err
                delay(1000L)
                result = youTube.getPlaylistSuggestionsAndRelated(bareId, initialContinuation = initialContinuation)
            }
            result
                .onSuccess { data ->
                    val fetchedSuggestions = data.suggestions.map { it.toTrack() }
                    val fetchedRelated = data.relatedPlaylists.map { it.toPlaylistsResult() }
                    val mergedSuggestions =
                        if (fetchedSuggestions.isNotEmpty()) fetchedSuggestions else initialExtras?.suggestions.orEmpty()
                    val mergedReload =
                        data.reloadParams ?: initialExtras?.reloadParams
                    val mergedRelated =
                        buildList {
                            val seen = HashSet<String>()
                            initialExtras?.relatedPlaylists?.forEach { item ->
                                if (seen.add(item.browseId)) add(item)
                            }
                            fetchedRelated.forEach { item ->
                                if (seen.add(item.browseId)) add(item)
                            }
                        }
                    Logger.i(
                        "PlaylistExtras",
                        "id=$bareId reusedCont=${!initialContinuation.isNullOrBlank()} suggestions=${mergedSuggestions.size} related=${mergedRelated.size}",
                    )
                    emit(
                        Resource.Success(
                            PlaylistExtras(
                                reloadParams = mergedReload,
                                suggestions = mergedSuggestions,
                                relatedPlaylists = mergedRelated,
                            ),
                        ),
                    )
                }.onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    if (initialExtras != null) {
                        emit(Resource.Success(initialExtras))
                    } else {
                        Logger.e("getPlaylistExtras", exception.message ?: "Error")
                        emit(Resource.Error<PlaylistExtras>(exception.message ?: "Unknown error"))
                    }
                }
        }.flowOn(Dispatchers.IO)

    private fun PlaylistItem.toPlaylistsResult(): PlaylistsResult =
        PlaylistsResult(
            author = author?.name.orEmpty(),
            browseId = id,
            category = "",
            itemCount = songCountText.orEmpty(),
            resultType = "PLAYLIST",
            thumbnails =
                if (thumbnail.isNotBlank()) {
                    listOf(Thumbnail(width = 544, url = thumbnail, height = 544))
                } else {
                    emptyList()
                },
            title = title,
        )

    override fun reloadPlaylistSuggestions(reloadParams: String): Flow<Resource<Pair<String?, List<Track>>>> =
        flow {
            runCatching {
                youTube
                    .customQuery(browseId = "", continuation = reloadParams, setLogin = true)
                    .onSuccess { values ->
                        val contents = values.continuationContents?.musicShelfContinuation?.contents.orEmpty()
                        val nextReload =
                            values.continuationContents
                                ?.musicShelfContinuation
                                ?.continuations
                                ?.firstOrNull()
                                ?.reloadContinuationData
                                ?.continuation
                        val tracks =
                            contents.mapNotNull { item ->
                                (SearchPage.toYTItem(item.musicResponsiveListItemRenderer) as? SongItem)?.toTrack()
                            }
                        if (tracks.isNotEmpty() || nextReload != null) {
                            emit(Resource.Success<Pair<String?, List<Track>>>(nextReload to tracks))
                        } else {
                            emit(Resource.Error<Pair<String?, List<Track>>>("Empty suggestions"))
                        }
                    }.onFailure { exception ->
                        if (exception is CancellationException) throw exception
                        emit(Resource.Error<Pair<String?, List<Track>>>(exception.message ?: "Unknown error"))
                    }
            }.onFailure { exception ->
                if (exception is CancellationException) throw exception
                emit(Resource.Error<Pair<String?, List<Track>>>(exception.message ?: "Unknown error"))
            }
        }.flowOn(Dispatchers.IO)

    override fun addTrackToYouTubePlaylist(
        playlistId: String,
        videoId: String,
    ): Flow<Resource<String>> =
        flow {
            youTube
                .addPlaylistItem(playlistId.verifyYouTubePlaylistId(), videoId)
                .onSuccess { response ->
                    if (response.playlistEditResults.isNotEmpty()) {
                        for (editResult in response.playlistEditResults) {
                            insertSetVideoId(
                                SetVideoIdEntity(
                                    videoId = editResult.playlistEditVideoAddedResultData.videoId,
                                    setVideoId = editResult.playlistEditVideoAddedResultData.setVideoId,
                                    youtubePlaylistId = playlistId.removePrefix("VL"),
                                ),
                            )
                        }
                        emit(Resource.Success(response.status))
                    } else {
                        emit(Resource.Error<String>("FAILED"))
                    }
                }.onFailure { exception ->
                    if (exception is CancellationException) throw exception
                    emit(Resource.Error<String>(exception.message ?: "FAILED"))
                }
        }.flowOn(Dispatchers.IO)
}