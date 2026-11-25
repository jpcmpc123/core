package com.maxrave.domain.data.player

/**
 * Generic tracks information
 */
data class GenericTracks(
    val groups: List<GenericTrackGroup>,
) {
    data class GenericTrackGroup(
        val trackCount: Int,
        val selectedAudioMimeType: String? = null,
        val selectedAudioCodecs: String? = null,
        val selectedAudioBitrate: Int? = null,
        val selectedAudioSampleRate: Int? = null,
    )
}

/**
 * Runtime audio track metadata reported by the underlying media player when tracks are selected.
 * Acts as a live fallback when the local `new_format` database row is missing (e.g., legacy cache
 * entries or local files).
 */
data class ActiveAudioTrackInfo(
    val mediaId: String,
    val mimeType: String? = null,
    val codecs: String? = null,
    val bitrate: Int? = null,
    val sampleRate: Int? = null,
)