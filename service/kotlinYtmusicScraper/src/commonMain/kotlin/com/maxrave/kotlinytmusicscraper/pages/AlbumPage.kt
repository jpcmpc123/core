package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.Album
import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.kotlinytmusicscraper.models.Artist
import com.maxrave.kotlinytmusicscraper.models.MusicCarouselShelfRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer
import com.maxrave.kotlinytmusicscraper.models.MusicTwoRowItemRenderer
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.Thumbnail
import com.maxrave.kotlinytmusicscraper.models.Thumbnails
import com.maxrave.kotlinytmusicscraper.models.oddElements
import com.maxrave.kotlinytmusicscraper.models.splitBySeparator
import com.maxrave.kotlinytmusicscraper.utils.parseTime

data class AlbumPage(
    val album: AlbumItem,
    val songs: List<SongItem>,
    val description: String?,
    val thumbnails: Thumbnails?,
    val duration: String?,
    val otherVersion: List<AlbumItem>,
    val similarAlbums: List<AlbumItem> = emptyList(),
) {
    companion object {
        private const val ITEM_SIZE_MEDIUM = "COLLECTION_STYLE_ITEM_SIZE_MEDIUM"
        private const val ITEM_SIZE_SMALL = "COLLECTION_STYLE_ITEM_SIZE_SMALL"
        private val YEAR_REGEX = Regex("""\b(19|20)\d{2}\b""")
        private val EDITION_SUFFIX_REGEX =
            Regex(
                """\s*[\(\[](?:deluxe|super deluxe|expanded|remaster(?:ed)?|anniversary|special|collector'?s?|bonus|explicit|clean|acoustic|live|instrumental|complete|extended|standard|platinum|gold|legacy|edition|version)[^\)\]]*[\)\]]""",
                RegexOption.IGNORE_CASE,
            )
        private val EDITION_TRAILING_WORDS_REGEX =
            Regex(
                """\s*[-–:]\s*(?:deluxe|super deluxe|expanded|remaster(?:ed)?|anniversary|special|bonus|explicit|clean|acoustic|live|edition|version).*$""",
                RegexOption.IGNORE_CASE,
            )

        internal fun normalizeBaseAlbumTitle(title: String): String {
            var cleaned = title.trim()
            var prev: String
            do {
                prev = cleaned
                cleaned = EDITION_SUFFIX_REGEX.replace(cleaned, "").trim()
                cleaned = EDITION_TRAILING_WORDS_REGEX.replace(cleaned, "").trim()
            } while (cleaned != prev)
            return cleaned
                .lowercase()
                .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
        }

        private fun isOtherVersionsHeader(title: String?): Boolean {
            val lower = title?.lowercase()?.trim() ?: return false
            return lower.contains("version") ||
                lower.contains("versõ") ||
                lower.contains("versã") ||
                lower.contains("edición") ||
                lower.contains("edicion") ||
                lower.contains("ediç") ||
                lower.contains("edition") ||
                lower.contains("верси") ||
                lower.contains("バージョン") ||
                lower.contains("버전")
        }

        /**
         * Splits YouTube Music's `secondaryContents` album carousels into:
         * - `first` (`otherVersion`): alternative editions of this same album (Deluxe, Clean, Explicit, Remastered, etc.)
         * - `second` (`similarAlbums`): related releases/recommendations ("Releases for you" / "You might also like")
         */
        fun classifySecondaryCarousels(
            currentBrowseId: String?,
            albumTitle: String,
            carousels: List<MusicCarouselShelfRenderer>,
        ): Pair<List<AlbumItem>, List<AlbumItem>> {
            if (carousels.isEmpty()) return emptyList<AlbumItem>() to emptyList()
            val versions = mutableListOf<AlbumItem>()
            val similar = mutableListOf<AlbumItem>()
            val seenVersionIds = mutableSetOf<String>()
            val seenSimilarIds = mutableSetOf<String>()
            val normalizedBase = normalizeBaseAlbumTitle(albumTitle)

            for (carousel in carousels) {
                val items =
                    carousel.contents.mapNotNull { content ->
                        fromMusicTwoRowItemRenderer(content.musicTwoRowItemRenderer)
                    }.filter { it.browseId != currentBrowseId }
                if (items.isEmpty()) continue

                val headerTitle =
                    carousel.header
                        ?.musicCarouselShelfBasicHeaderRenderer
                        ?.title
                        ?.runs
                        ?.joinToString("") { it.text }

                val isOtherVersions =
                    when {
                        carousel.itemSize == ITEM_SIZE_MEDIUM -> true
                        carousel.itemSize == ITEM_SIZE_SMALL -> false
                        isOtherVersionsHeader(headerTitle) -> true
                        normalizedBase.isNotEmpty() &&
                            items.any { normalizeBaseAlbumTitle(it.title) == normalizedBase } -> true
                        else -> false
                    }

                if (isOtherVersions) {
                    for (item in items) {
                        if (seenVersionIds.add(item.browseId)) {
                            versions.add(item)
                        }
                    }
                } else {
                    for (item in items) {
                        if (seenSimilarIds.add(item.browseId)) {
                            similar.add(item)
                        }
                    }
                }
            }

            // Defensive dedupe: never show an edition card twice across both shelves.
            val finalSimilar = similar.filter { it.browseId !in seenVersionIds }
            return versions to finalSimilar
        }

        fun fromMusicTwoRowItemRenderer(renderer: MusicTwoRowItemRenderer?): AlbumItem? {
            if (renderer == null) {
                return null
            }
            val browseId = renderer.navigationEndpoint.browseEndpoint?.browseId ?: return null
            val title =
                renderer.title.runs
                    ?.firstOrNull()
                    ?.text ?: return null
            val subtitleRuns = renderer.subtitle?.runs.orEmpty()
            val subtitleText =
                subtitleRuns
                    .joinToString("") { it.text }
                    .trim()
            val extractedYear = YEAR_REGEX.find(subtitleText)?.value?.toIntOrNull()
            val extractedArtists =
                subtitleRuns
                    .filter { it.navigationEndpoint?.browseEndpoint != null }
                    .map {
                        Artist(
                            id = it.navigationEndpoint?.browseEndpoint?.browseId,
                            name = it.text,
                        )
                    }.ifEmpty {
                        subtitleRuns
                            .lastOrNull { it.text.isNotBlank() && it.text.trim() != "•" }
                            ?.takeIf { YEAR_REGEX.matchEntire(it.text.trim()) == null }
                            ?.let {
                                listOf(
                                    Artist(
                                        id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                        name = it.text.trim(),
                                    ),
                                )
                            } ?: emptyList()
                    }
            val hasExplicitBadge =
                renderer.subtitleBadges?.any {
                    it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                } == true ||
                    renderer.badges?.any {
                        it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                    } == true

            return AlbumItem(
                browseId = browseId,
                playlistId = renderer.playlistId ?: "",
                title = title,
                artists = extractedArtists,
                year = extractedYear,
                isSingle =
                    subtitleText.contains("Single", ignoreCase = true) ||
                        subtitleText.contains("EP", ignoreCase = true),
                thumbnail = renderer.thumbnailRenderer.musicThumbnailRenderer?.getThumbnailUrl() ?: "",
                explicit = hasExplicitBadge,
                subtitle = subtitleText.takeIf { it.isNotBlank() },
            )
        }

        fun fromMusicResponsiveListItemRenderer(
            renderer: MusicResponsiveListItemRenderer?,
            album: AlbumItem,
        ): SongItem? {
            if (renderer == null) {
                return null
            } else {
                return SongItem(
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
                        renderer.flexColumns
                            .getOrNull(1)
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            // The column reads "Artist • Album • 13M plays"; only the first group
                            // is artists, so everything after the first " • " is dropped.
                            ?.splitBySeparator()
                            ?.firstOrNull()
                            ?.oddElements()
                            ?.map {
                                Artist(
                                    name = it.text,
                                    id = it.navigationEndpoint?.browseEndpoint?.browseId,
                                )
                            } ?: album.artists ?: emptyList(),
                    album =
                        Album(
                            name = album.title,
                            id = album.id,
                        ),
                    duration =
                        renderer.fixedColumns
                            ?.firstOrNull()
                            ?.musicResponsiveListItemFlexColumnRenderer
                            ?.text
                            ?.runs
                            ?.firstOrNull()
                            ?.text
                            ?.parseTime() ?: return null,
                    thumbnail = album.thumbnail,
                    thumbnails =
                        Thumbnails(
                            thumbnails =
                                listOf(
                                    Thumbnail(
                                        url = album.thumbnail,
                                        width = 544,
                                        height = 544,
                                    ),
                                ),
                        ),
                    explicit =
                        renderer.badges?.find {
                            it.musicInlineBadgeRenderer?.icon?.iconType == "MUSIC_EXPLICIT_BADGE"
                        } != null,
                    musicVideoType = renderer.musicVideoType,
                    isLive = renderer.isLive,
                )
            }
        }
    }
}