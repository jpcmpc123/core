package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.AlbumItem
import com.maxrave.kotlinytmusicscraper.models.ArtistItem
import com.maxrave.kotlinytmusicscraper.models.PlaylistItem
import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.SearchResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val searchJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

class SearchPageParserTest {
    @Test
    fun parseSearchContinuationWithEmptyOrMissingShelfDoesNotThrowNpe() {
        val response = searchJson.decodeFromString<SearchResponse>("""{"continuationContents":{}}""")
        val parsed = SearchPage.parseSearchContinuation(response)
        assertTrue(parsed.items.isEmpty())
        assertTrue(parsed.listPodcast.isEmpty())
        assertNull(parsed.continuation)
    }

    @Test
    fun parseSearchResponseFindsMusicShelfEvenWithTrailingPromoSection() {
        val raw =
            """
            {
              "contents": {
                "tabbedSearchResultsRenderer": {
                  "tabs": [{
                    "tabRenderer": {
                      "content": {
                        "sectionListRenderer": {
                          "contents": [
                            {
                              "musicShelfRenderer": {
                                "contents": [{
                                  "musicResponsiveListItemRenderer": {
                                    "navigationEndpoint": {
                                      "browseEndpoint": {
                                        "browseId": "UCARTIST1",
                                        "browseEndpointContextSupportedConfigs": {
                                          "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" }
                                        }
                                      }
                                    },
                                    "flexColumns": [{
                                      "musicResponsiveListItemFlexColumnRenderer": {
                                        "text": { "runs": [{ "text": "Artist Without Menu" }] }
                                      }
                                    }],
                                    "thumbnail": {
                                      "musicThumbnailRenderer": {
                                        "thumbnail": { "thumbnails": [{ "url": "https://img/a.jpg", "width": 120, "height": 120 }] }
                                      }
                                    }
                                  }
                                }],
                                "continuations": [{ "nextContinuationData": { "continuation": "CONT_NEXT" } }]
                              }
                            },
                            {
                              "itemSectionRenderer": {}
                            }
                          ]
                        }
                      }
                    }
                  }]
                }
              }
            }
            """.trimIndent()

        val parsed = SearchPage.parseSearchResponse(searchJson.decodeFromString<SearchResponse>(raw))
        assertEquals("CONT_NEXT", parsed.continuation)
        assertEquals(1, parsed.items.size)
        val artist = assertIs<ArtistItem>(parsed.items.first())
        assertEquals("UCARTIST1", artist.id)
        assertEquals("Artist Without Menu", artist.title)
        assertNull(artist.shuffleEndpoint)
        assertNull(artist.radioEndpoint)
    }

    @Test
    fun playlistAndPodcastWithoutOptionalEndpointsArePreserved() {
        val playlistJson =
            """
            {
              "navigationEndpoint": {
                "browseEndpoint": {
                  "browseId": "VLPL12345",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_PLAYLIST" }
                  }
                }
              },
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Rock Mix" }] } } }
              ],
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/p.jpg", "width": 120, "height": 120 }] }
                }
              }
            }
            """.trimIndent()
        val playlistRenderer =
            searchJson.decodeFromString<com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer>(playlistJson)
        val parsedPlaylist = assertIs<PlaylistItem>(SearchPage.toYTItem(playlistRenderer))
        assertEquals("PL12345", parsedPlaylist.id)
        assertEquals("Rock Mix", parsedPlaylist.title)
        assertEquals("PL12345", parsedPlaylist.playEndpoint?.playlistId)

        val podcastJson =
            """
            {
              "navigationEndpoint": {
                "browseEndpoint": { "browseId": "MPSP123" }
              },
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Tech Show" }] } } },
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "RSS Host" }] } } }
              ],
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/pod.jpg", "width": 120, "height": 120 }] }
                }
              }
            }
            """.trimIndent()
        val podcastRenderer =
            searchJson.decodeFromString<com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer>(podcastJson)
        val parsedPodcast = SearchPage.toPodcast(podcastRenderer)
        assertEquals("MPSP123", parsedPodcast?.id)
        assertEquals("RSS Host", parsedPodcast?.author?.name)
        assertNull(parsedPodcast?.author?.id)
    }

    @Test
    fun suggestionSongWithoutAlbumBrowseIdAndSuggestedPlaylistAreParsed() {
        val songWithoutAlbumLinkJson =
            """
            {
              "playlistItemData": { "videoId": "VID123" },
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Track One" }] } } },
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Song" }, { "text": " • " }, { "text": "Singer" }] } } },
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "2.4M plays" }] } } }
              ],
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/s.jpg", "width": 120, "height": 120 }] }
                }
              }
            }
            """.trimIndent()
        val songRenderer =
            searchJson.decodeFromString<com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer>(songWithoutAlbumLinkJson)
        val parsedSong = assertIs<SongItem>(SearchSuggestionPage.fromMusicResponsiveListItemRenderer(songRenderer))
        assertEquals("VID123", parsedSong.id)
        assertNull(parsedSong.album)

        val suggestedPlaylistJson =
            """
            {
              "navigationEndpoint": {
                "browseEndpoint": {
                  "browseId": "VLRDCLAK5",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_PLAYLIST" }
                  }
                }
              },
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Workout" }] } } },
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Playlist" }, { "text": " • " }, { "text": "YouTube Music" }] } } }
              ],
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/pl.jpg", "width": 120, "height": 120 }] }
                }
              }
            }
            """.trimIndent()
        val playlistRenderer =
            searchJson.decodeFromString<com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer>(suggestedPlaylistJson)
        val parsedPlaylist = assertIs<PlaylistItem>(SearchSuggestionPage.fromMusicResponsiveListItemRenderer(playlistRenderer))
        assertEquals("RDCLAK5", parsedPlaylist.id)
        assertEquals("Workout", parsedPlaylist.title)
        assertEquals("YouTube Music", parsedPlaylist.author?.name)

        val albumWithoutShuffleJson =
            """
            {
              "navigationEndpoint": {
                "browseEndpoint": {
                  "browseId": "MPREb_123",
                  "browseEndpointContextSupportedConfigs": {
                    "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" }
                  }
                }
              },
              "flexColumns": [
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Album Name" }] } } },
                { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [{ "text": "Album" }, { "text": " • " }, { "text": "Band" }] } } }
              ],
              "thumbnail": {
                "musicThumbnailRenderer": {
                  "thumbnail": { "thumbnails": [{ "url": "https://img/al.jpg", "width": 120, "height": 120 }] }
                }
              }
            }
            """.trimIndent()
        val albumRenderer =
            searchJson.decodeFromString<com.maxrave.kotlinytmusicscraper.models.MusicResponsiveListItemRenderer>(albumWithoutShuffleJson)
        val parsedAlbum = assertIs<AlbumItem>(SearchSuggestionPage.fromMusicResponsiveListItemRenderer(albumRenderer))
        assertEquals("MPREb_123", parsedAlbum.browseId)
        assertEquals("", parsedAlbum.playlistId)
    }
}
