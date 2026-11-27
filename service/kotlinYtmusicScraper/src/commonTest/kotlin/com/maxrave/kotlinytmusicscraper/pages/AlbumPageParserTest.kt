package com.maxrave.kotlinytmusicscraper.pages

import com.maxrave.kotlinytmusicscraper.models.response.BrowseResponse
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val albumJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

class AlbumPageParserTest {
    @Test
    fun classifySecondaryCarouselsSplitsOtherVersionsDeluxeFromRecommendations() {
        val raw =
            """
            {
              "responseContext": {},
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        { "musicShelfRenderer": { "contents": [] } },
                        {
                          "musicCarouselShelfRenderer": {
                            "itemSize": "COLLECTION_STYLE_ITEM_SIZE_MEDIUM",
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Outras versões" }] }
                              }
                            },
                            "contents": [
                              {
                                "musicTwoRowItemRenderer": {
                                  "title": { "runs": [{ "text": "Juno (Deluxe)" }] },
                                  "subtitle": {
                                    "runs": [
                                      { "text": "Álbum" },
                                      { "text": " • " },
                                      {
                                        "text": "Remi Wolf",
                                        "navigationEndpoint": {
                                          "browseEndpoint": { "browseId": "UC_REMI" }
                                        }
                                      }
                                    ]
                                  },
                                  "subtitleBadges": [
                                    {
                                      "musicInlineBadgeRenderer": {
                                        "icon": { "iconType": "MUSIC_EXPLICIT_BADGE" }
                                      }
                                    }
                                  ],
                                  "thumbnailRenderer": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": { "thumbnails": [{ "url": "https://img/deluxe.jpg", "width": 544, "height": 544 }] }
                                    }
                                  },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "MPREb_DELUXE" }
                                  }
                                }
                              }
                            ]
                          }
                        },
                        {
                          "musicCarouselShelfRenderer": {
                            "itemSize": "COLLECTION_STYLE_ITEM_SIZE_SMALL",
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Lançamentos para você" }] }
                              }
                            },
                            "contents": [
                              {
                                "musicTwoRowItemRenderer": {
                                  "title": { "runs": [{ "text": "RINSE&REPEAT" }] },
                                  "subtitle": {
                                    "runs": [
                                      { "text": "Single" },
                                      { "text": " • " },
                                      { "text": "Pebbles&TamTam" }
                                    ]
                                  },
                                  "thumbnailRenderer": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": { "thumbnails": [{ "url": "https://img/rinse.jpg", "width": 544, "height": 544 }] }
                                    }
                                  },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "MPREb_RINSE" }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()

        val response = albumJson.decodeFromString<BrowseResponse>(raw)
        val carousels =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.secondaryContents
                ?.sectionListRenderer
                ?.contents
                ?.mapNotNull { it.musicCarouselShelfRenderer }
                .orEmpty()

        val (otherVersions, similarAlbums) =
            AlbumPage.classifySecondaryCarousels(
                currentBrowseId = "MPREb_JUNO",
                albumTitle = "Juno",
                carousels = carousels,
            )

        assertEquals(1, otherVersions.size)
        assertEquals("Juno (Deluxe)", otherVersions.first().title)
        assertEquals("MPREb_DELUXE", otherVersions.first().browseId)
        assertTrue(otherVersions.first().explicit)
        assertEquals("Álbum • Remi Wolf", otherVersions.first().subtitle)

        assertEquals(1, similarAlbums.size)
        assertEquals("RINSE&REPEAT", similarAlbums.first().title)
        assertEquals("MPREb_RINSE", similarAlbums.first().browseId)
        assertTrue(similarAlbums.first().isSingle)
        assertFalse(similarAlbums.first().explicit)
    }

    @Test
    fun albumWithOnlyRecommendationsCarouselLeavesOtherVersionEmpty() {
        val raw =
            """
            {
              "responseContext": {},
              "contents": {
                "twoColumnBrowseResultsRenderer": {
                  "secondaryContents": {
                    "sectionListRenderer": {
                      "contents": [
                        { "musicShelfRenderer": { "contents": [] } },
                        {
                          "musicCarouselShelfRenderer": {
                            "itemSize": "COLLECTION_STYLE_ITEM_SIZE_SMALL",
                            "header": {
                              "musicCarouselShelfBasicHeaderRenderer": {
                                "title": { "runs": [{ "text": "Releases for you" }] }
                              }
                            },
                            "contents": [
                              {
                                "musicTwoRowItemRenderer": {
                                  "title": { "runs": [{ "text": "Bad Behavior" }] },
                                  "subtitle": { "runs": [{ "text": "Single • Remi Wolf" }] },
                                  "thumbnailRenderer": {
                                    "musicThumbnailRenderer": {
                                      "thumbnail": { "thumbnails": [{ "url": "https://img/bad.jpg", "width": 544, "height": 544 }] }
                                    }
                                  },
                                  "navigationEndpoint": {
                                    "browseEndpoint": { "browseId": "MPREb_BAD" }
                                  }
                                }
                              }
                            ]
                          }
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()

        val response = albumJson.decodeFromString<BrowseResponse>(raw)
        val carousels =
            response.contents
                ?.twoColumnBrowseResultsRenderer
                ?.secondaryContents
                ?.sectionListRenderer
                ?.contents
                ?.mapNotNull { it.musicCarouselShelfRenderer }
                .orEmpty()

        val (otherVersions, similarAlbums) =
            AlbumPage.classifySecondaryCarousels(
                currentBrowseId = "MPREb_ORIG",
                albumTitle = "Juno",
                carousels = carousels,
            )

        assertTrue(otherVersions.isEmpty())
        assertEquals(1, similarAlbums.size)
        assertEquals("Bad Behavior", similarAlbums.first().title)
    }

    @Test
    fun baseTitleNormalizationMatchesDeluxeExpandedAndRemasteredEditions() {
        assertEquals(
            AlbumPage.normalizeBaseAlbumTitle("Juno"),
            AlbumPage.normalizeBaseAlbumTitle("Juno (Deluxe Edition)"),
        )
        assertEquals(
            AlbumPage.normalizeBaseAlbumTitle("After Hours"),
            AlbumPage.normalizeBaseAlbumTitle("After Hours (Remastered) [Explicit]"),
        )
    }
}
