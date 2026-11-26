package com.maxrave.kotlinytmusicscraper.parser

import com.maxrave.kotlinytmusicscraper.models.Context
import com.maxrave.kotlinytmusicscraper.models.body.CreatePlaylistBody
import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaylistParserTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun parsesSuggestionsAndRelatedPlaylistsFromSectionListContinuation() {
        val raw =
            """
            {
              "responseContext": {
                "serviceTrackingParams": []
              },
              "continuationContents": {
                "sectionListContinuation": {
                  "contents": [
                    {
                      "musicShelfRenderer": {
                        "contents": [
                          {
                            "musicResponsiveListItemRenderer": {
                              "flexColumns": [
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": {
                                      "runs": [
                                        {
                                          "text": "Mr. Blue Sky",
                                          "navigationEndpoint": {
                                            "watchEndpoint": {
                                              "videoId": "vid123"
                                            }
                                          }
                                        }
                                      ]
                                    }
                                  }
                                },
                                {
                                  "musicResponsiveListItemFlexColumnRenderer": {
                                    "text": {
                                      "runs": [
                                        {
                                          "text": "Electric Light Orchestra",
                                          "navigationEndpoint": {
                                            "browseEndpoint": {
                                              "browseId": "UC_ELO",
                                              "browseEndpointContextSupportedConfigs": {
                                                "browseEndpointContextMusicConfig": {
                                                  "pageType": "MUSIC_PAGE_TYPE_ARTIST"
                                                }
                                              }
                                            }
                                          }
                                        }
                                      ]
                                    }
                                  }
                                }
                              ],
                              "fixedColumns": [],
                              "thumbnail": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [
                                      { "url": "https://img.example.com/song.jpg", "width": 120, "height": 120 }
                                    ]
                                  }
                                }
                              }
                            }
                          }
                        ],
                        "continuations": [
                          {
                            "reloadContinuationData": {
                              "continuation": "RELOAD_TOKEN_1"
                            }
                          }
                        ]
                      }
                    },
                    {
                      "musicCarouselShelfRenderer": {
                        "itemSize": "MUSIC_CAROUSEL_ITEM_SIZE_MEDIUM_SQUARE",
                        "contents": [
                          {
                            "musicTwoRowItemRenderer": {
                              "title": {
                                "runs": [
                                  { "text": "40s larp" }
                                ]
                              },
                              "subtitle": {
                                "runs": [
                                  { "text": "Playlist" },
                                  { "text": " • " },
                                  { "text": "Jett Pendergrass" },
                                  { "text": " • " },
                                  { "text": "175 visualizações" }
                                ]
                              },
                              "thumbnailRenderer": {
                                "musicThumbnailRenderer": {
                                  "thumbnail": {
                                    "thumbnails": [
                                      { "url": "https://img.example.com/pl.jpg", "width": 544, "height": 544 }
                                    ]
                                  }
                                }
                              },
                              "navigationEndpoint": {
                                "browseEndpoint": {
                                  "browseId": "VLPL_RELATED_1",
                                  "browseEndpointContextSupportedConfigs": {
                                    "browseEndpointContextMusicConfig": {
                                      "pageType": "MUSIC_PAGE_TYPE_PLAYLIST"
                                    }
                                  }
                                }
                              }
                            }
                          }
                        ]
                      }
                    }
                  ],
                  "continuations": [
                    {
                      "nextContinuationData": {
                        "continuation": "NEXT_CONT_TOKEN"
                      }
                    }
                  ]
                }
              }
            }
            """.trimIndent()

        val response = json.decodeFromString<BrowseResponse>(raw)
        assertTrue(response.hasReloadParams())
        assertEquals("RELOAD_TOKEN_1", response.getReloadParams())
        assertEquals("NEXT_CONT_TOKEN", response.getPlaylistExtrasNextContinuation())

        val suggestions = response.getSuggestionSongItems()
        assertEquals(1, suggestions.size)
        assertEquals("vid123", suggestions[0].id)
        assertEquals("Mr. Blue Sky", suggestions[0].title)
        assertEquals("Electric Light Orchestra", suggestions[0].artists.firstOrNull()?.name)

        val related = response.getRelatedPlaylistItems()
        assertEquals(1, related.size)
        assertEquals("PL_RELATED_1", related[0].id)
        assertEquals("40s larp", related[0].title)
        assertEquals("Jett Pendergrass", related[0].author?.name)
        assertEquals("175 visualizações", related[0].songCountText)
        assertEquals("https://img.example.com/pl.jpg", related[0].thumbnail)
    }

    @Test
    fun emptyBrowseResponseReturnsNoSuggestionsOrRelatedPlaylists() {
        val raw = """{"responseContext":{"serviceTrackingParams":[]}}"""
        val response = json.decodeFromString<BrowseResponse>(raw)
        assertNull(response.getReloadParams())
        assertTrue(response.getSuggestionSongItems().isEmpty())
        assertTrue(response.getRelatedPlaylistItems().isEmpty())
    }

    @Test
    fun createPlaylistBodyDefaultsToNullDescriptionAndRespectsPrivacy() {
        val body =
            CreatePlaylistBody(
                context =
                    Context(
                        client =
                            Context.Client(
                                clientName = "WEB_REMIX",
                                clientVersion = "1.2024",
                                userAgent = "test",
                                gl = "US",
                                hl = "en",
                                visitorData = null,
                                osVersion = null,
                            ),
                    ),
                title = "Minha Playlist",
                privacyStatus = CreatePlaylistBody.PrivacyStatus.PUBLIC,
            )
        assertNull(body.description)
        assertEquals("PUBLIC", body.privacyStatus)
    }
}
