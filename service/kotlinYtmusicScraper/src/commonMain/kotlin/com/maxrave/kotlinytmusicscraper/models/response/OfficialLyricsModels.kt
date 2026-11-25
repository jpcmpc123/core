package com.maxrave.kotlinytmusicscraper.models.response

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

data class YouTubeTimedLyricLine(
    val text: String,
    val startTimeMs: Long,
    val endTimeMs: Long,
)

sealed interface YouTubeMusicOfficialLyrics {
    data class Timed(
        val lines: List<YouTubeTimedLyricLine>,
        val sourceMessage: String? = null,
    ) : YouTubeMusicOfficialLyrics

    data class Plain(
        val text: String,
        val sourceMessage: String? = null,
    ) : YouTubeMusicOfficialLyrics
}

private val OFFICIAL_LYRICS_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

/**
 * Parses YouTube Music's mobile (`ANDROID_MUSIC`) `browse` payload for `MPLYt*` endpoints.
 * Returns [YouTubeMusicOfficialLyrics.Timed] when `timedLyricsData` carries cue ranges,
 * [YouTubeMusicOfficialLyrics.Plain] when `timedLyricsData` only carries bare lines,
 * or `null` when unavailable (e.g. `musicMessageModel` "Lyrics not available at this time.").
 */
fun parseAndroidMusicOfficialLyrics(rawJson: String): YouTubeMusicOfficialLyrics? {
    if (rawJson.isBlank()) return null
    val root =
        runCatching { OFFICIAL_LYRICS_JSON.parseToJsonElement(rawJson) }.getOrNull() as? JsonObject
            ?: return null
    val lyricsData =
        root["contents"]
            ?.let { it as? JsonObject }
            ?.get("elementRenderer")
            ?.let { it as? JsonObject }
            ?.get("newElement")
            ?.let { it as? JsonObject }
            ?.get("type")
            ?.let { it as? JsonObject }
            ?.get("componentType")
            ?.let { it as? JsonObject }
            ?.get("model")
            ?.let { it as? JsonObject }
            ?.get("timedLyricsModel")
            ?.let { it as? JsonObject }
            ?.get("lyricsData")
            ?.let { it as? JsonObject }
            ?: return null

    val timedArray = lyricsData["timedLyricsData"] as? JsonArray ?: return null
    if (timedArray.isEmpty()) return null
    val sourceMessage =
        (lyricsData["sourceMessage"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    val timedLines = ArrayList<YouTubeTimedLyricLine>(timedArray.size)
    val plainLines = ArrayList<String>(timedArray.size)
    var cuedLineCount = 0

    for (entry in timedArray) {
        val obj = entry as? JsonObject ?: continue
        val text = obj["lyricLine"]?.jsonPrimitive?.contentOrNull ?: continue
        plainLines.add(text)
        val cueRange = obj["cueRange"] as? JsonObject ?: continue
        val startMs = cueRange["startTimeMilliseconds"]?.jsonPrimitive?.longOrNull ?: continue
        if (startMs < 0L) continue
        val endMs = cueRange["endTimeMilliseconds"]?.jsonPrimitive?.longOrNull ?: startMs
        cuedLineCount++
        timedLines.add(
            YouTubeTimedLyricLine(
                text = text,
                startTimeMs = startMs,
                endTimeMs = endMs.coerceAtLeast(startMs),
            ),
        )
    }

    if (timedLines.size >= 2 &&
        cuedLineCount * 2 >= plainLines.size.coerceAtLeast(1) &&
        timedLines.map { it.startTimeMs }.distinct().size > 1
    ) {
        return YouTubeMusicOfficialLyrics.Timed(
            lines = timedLines.sortedBy { it.startTimeMs },
            sourceMessage = sourceMessage,
        )
    }

    val joined = plainLines.joinToString("\n").trim()
    if (joined.isEmpty()) return null
    return YouTubeMusicOfficialLyrics.Plain(
        text = joined,
        sourceMessage = sourceMessage,
    )
}
