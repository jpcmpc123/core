package com.maxrave.kotlinytmusicscraper

import com.maxrave.kotlinytmusicscraper.models.response.YouTubeMusicOfficialLyrics
import com.maxrave.kotlinytmusicscraper.models.response.parseAndroidMusicOfficialLyrics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class YouTubeMusicTimedLyricsTest {
    @Test
    fun parsesAndroidMusicTimedLyricsModelCueRanges() {
        val raw =
            """
            {
              "contents": {
                "elementRenderer": {
                  "newElement": {
                    "type": {
                      "componentType": {
                        "model": {
                          "timedLyricsModel": {
                            "lyricsData": {
                              "sourceMessage": "Source: Musixmatch",
                              "timedLyricsData": [
                                {
                                  "lyricLine": "I've been tryna call",
                                  "cueRange": {
                                    "startTimeMilliseconds": "27240",
                                    "endTimeMilliseconds": "29900",
                                    "metadata": { "id": "1" }
                                  }
                                },
                                {
                                  "lyricLine": "I've been on my own for long enough",
                                  "cueRange": {
                                    "startTimeMilliseconds": "30020",
                                    "endTimeMilliseconds": "32700",
                                    "metadata": { "id": "2" }
                                  }
                                }
                              ]
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()

        val parsed = assertIs<YouTubeMusicOfficialLyrics.Timed>(parseAndroidMusicOfficialLyrics(raw))
        assertEquals("Source: Musixmatch", parsed.sourceMessage)
        assertEquals(2, parsed.lines.size)
        assertEquals(27240L, parsed.lines[0].startTimeMs)
        assertEquals(29900L, parsed.lines[0].endTimeMs)
        assertEquals("I've been tryna call", parsed.lines[0].text)
    }

    @Test
    fun fallsBackToPlainWhenCueRangesAreMissingAndNullWhenMessageRenderer() {
        val noCueRaw =
            """
            {
              "contents": {
                "elementRenderer": {
                  "newElement": {
                    "type": {
                      "componentType": {
                        "model": {
                          "timedLyricsModel": {
                            "lyricsData": {
                              "sourceMessage": "Source: LyricFind",
                              "timedLyricsData": [
                                { "lyricLine": "Line one" },
                                { "lyricLine": "Line two" }
                              ]
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()

        val plain = assertIs<YouTubeMusicOfficialLyrics.Plain>(parseAndroidMusicOfficialLyrics(noCueRaw))
        assertEquals("Line one\nLine two", plain.text)
        assertEquals("Source: LyricFind", plain.sourceMessage)

        val unavailableRaw =
            """
            {
              "contents": {
                "elementRenderer": {
                  "newElement": {
                    "type": {
                      "componentType": {
                        "model": {
                          "musicMessageModel": {
                            "text": "Lyrics not available at this time."
                          }
                        }
                      }
                    }
                  }
                }
              }
            }
            """.trimIndent()
        assertNull(parseAndroidMusicOfficialLyrics(unavailableRaw))
    }
}
