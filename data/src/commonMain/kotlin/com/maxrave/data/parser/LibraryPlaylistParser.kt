package com.maxrave.data.parser

import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult
import com.maxrave.kotlinytmusicscraper.models.GridRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer

private val PLAYLIST_ITEM_COUNT_REGEX =
    Regex("""^\d[\d.,]*\s+(?:item|itens|track|tracks|song|songs|m[uú]sica|m[uú]sicas|video|videos|v[ií]deo|v[ií]deos|episode|episodes|epis[oó]dio|epis[oó]dios)\b.*$""", RegexOption.IGNORE_CASE)

internal fun extractLibraryPlaylistSubtitle(rawRuns: List<String?>?): Pair<String, String> {
    if (rawRuns.isNullOrEmpty()) return "" to ""
    val tokens =
        rawRuns
            .asSequence()
            .mapNotNull { it?.trim() }
            .filter { it.isNotEmpty() && it != "•" && it != "·" && it != "|" && it != "-" }
            .toMutableList()
    if (tokens.isEmpty()) return "" to ""

    if (tokens.size >= 2 && tokens.first().equals("playlist", ignoreCase = true)) {
        tokens.removeAt(0)
    }

    return when {
        tokens.isEmpty() -> "" to ""
        tokens.size == 1 -> {
            val single = tokens.first()
            if (PLAYLIST_ITEM_COUNT_REGEX.matches(single)) "" to single else single to ""
        }
        else -> {
            val last = tokens.last()
            if (PLAYLIST_ITEM_COUNT_REGEX.matches(last) || last.firstOrNull()?.isDigit() == true) {
                tokens.dropLast(1).joinToString(" • ") to last
            } else {
                tokens.joinToString(" • ") to ""
            }
        }
    }
}

internal fun parseLibraryPlaylist(input: List<GridRenderer.Item>): List<PlaylistsResult> {
    val list: MutableList<PlaylistsResult> = mutableListOf()
    if (input.isNotEmpty()) {
        for (i in input.indices) {
            input[i].musicTwoRowItemRenderer?.let {
                if (it.navigationEndpoint?.browseEndpoint?.browseId != null) {
                    val (author, itemCount) = extractLibraryPlaylistSubtitle(it.subtitle?.runs?.map { run -> run.text })
                    list.add(
                        PlaylistsResult(
                            author = author,
                            browseId = it.navigationEndpoint?.browseEndpoint?.browseId ?: "",
                            category = "",
                            itemCount = itemCount,
                            resultType = "",
                            thumbnails =
                                it.thumbnailRenderer
                                    ?.musicThumbnailRenderer
                                    ?.thumbnail
                                    ?.thumbnails
                                    ?.toListThumbnail() ?: listOf(),
                            title =
                                it.title
                                    ?.runs
                                    ?.getOrNull(0)
                                    ?.text ?: "",
                        ),
                    )
                }
            }
        }
    }
    return list
}

internal fun parseNextLibraryPlaylist(input: List<MusicTwoRowItemRenderer>): List<PlaylistsResult> =
    input.map {
        val (author, itemCount) = extractLibraryPlaylistSubtitle(it.subtitle?.runs?.map { run -> run.text })
        PlaylistsResult(
            author = author,
            browseId = it.navigationEndpoint?.browseEndpoint?.browseId ?: "",
            category = "",
            itemCount = itemCount,
            resultType = "",
            thumbnails =
                it.thumbnailRenderer
                    ?.musicThumbnailRenderer
                    ?.thumbnail
                    ?.thumbnails
                    ?.toListThumbnail() ?: listOf(),
            title =
                it.title
                    ?.runs
                    ?.getOrNull(0)
                    ?.text ?: "",
        )
    }