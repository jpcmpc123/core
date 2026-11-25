package com.maxrave.domain.data.model.update

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L,
    val sha256: String? = null,
)

data class UpdateData(
    val tagName: String,
    val releaseTime: String?,
    val body: String,
    val releaseUrl: String? = null,
    val assets: List<ReleaseAsset> = emptyList(),
)