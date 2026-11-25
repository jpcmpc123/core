package org.simpmusic.lyrics.models.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UnisonApiEnvelope(
    @SerialName("success")
    val success: Boolean = false,
    @SerialName("data")
    val data: UnisonLyricsData? = null,
    @SerialName("error")
    val error: String? = null,
    @SerialName("code")
    val code: String? = null,
)

@Serializable
data class UnisonLyricsData(
    @SerialName("id")
    val id: Long = 0L,
    @SerialName("videoId")
    val videoId: String = "",
    @SerialName("song")
    val song: String = "",
    @SerialName("artist")
    val artist: String = "",
    @SerialName("album")
    val album: String? = null,
    @SerialName("lyrics")
    val lyrics: String = "",
    @SerialName("format")
    val format: String = "",
    @SerialName("syncType")
    val syncType: String? = null,
    @SerialName("confidence")
    val confidence: String? = null,
    @SerialName("hidden")
    val hidden: Boolean = false,
    @SerialName("variant")
    val variant: String? = null,
    @SerialName("matchedBy")
    val matchedBy: String? = null,
    @SerialName("submitter")
    val submitter: UnisonSubmitter? = null,
)

@Serializable
data class UnisonSubmitter(
    @SerialName("keyId")
    val keyId: String = "",
    @SerialName("displayName")
    val displayName: String = "",
)
