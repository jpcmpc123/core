package org.simpmusic.lyrics.models.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NetEaseSearchResponse(
    @SerialName("code")
    val code: Int = 0,
    @SerialName("result")
    val result: NetEaseSearchResultPayload? = null,
)

@Serializable
data class NetEaseSearchResultPayload(
    @SerialName("songs")
    val songs: List<NetEaseSong> = emptyList(),
)

@Serializable
data class NetEaseSong(
    @SerialName("id")
    val id: Long,
    @SerialName("name")
    val name: String = "",
    @SerialName("duration")
    val duration: Long = 0L,
    @SerialName("artists")
    val artists: List<NetEaseArtist> = emptyList(),
    @SerialName("album")
    val album: NetEaseAlbum? = null,
    @SerialName("alias")
    val alias: List<String> = emptyList(),
)

@Serializable
data class NetEaseArtist(
    @SerialName("id")
    val id: Long = 0L,
    @SerialName("name")
    val name: String = "",
)

@Serializable
data class NetEaseAlbum(
    @SerialName("id")
    val id: Long = 0L,
    @SerialName("name")
    val name: String = "",
)

@Serializable
data class NetEaseLyricResponse(
    @SerialName("code")
    val code: Int = 0,
    @SerialName("nolyric")
    val nolyric: Boolean? = null,
    @SerialName("uncollected")
    val uncollected: Boolean? = null,
    @SerialName("lrc")
    val lrc: NetEaseLyricContent? = null,
    @SerialName("yrc")
    val yrc: NetEaseLyricContent? = null,
)

@Serializable
data class NetEaseLyricContent(
    @SerialName("version")
    val version: Int = 0,
    @SerialName("lyric")
    val lyric: String? = null,
)
