package com.maxrave.kotlinytmusicscraper.extractor

import kotlin.math.abs

private val XTAGS = Regex("[?&]xtags=([^&]+)")
private val CLEN = Regex("[?&]clen=(\\d+)")
private val DUR = Regex("[?&]dur=([0-9]+(?:\\.[0-9]+)?)")

/**
 * A video with several audio tracks (dubbed podcasts, MrBeast-style channels) lists every one of
 * them under the SAME itag, ordered by language code — so matching by itag alone plays whichever
 * language sorts first. Measured on `0e3GPea1Tyg`: 25 tracks per itag, Arabic first, the original
 * English at position 23. Each googlevideo URL names its own track in the `xtags` query value, e.g.
 * `acont=original:drc=1:lang=en-US` or `acont=dubbed-auto:lang=vi`.
 *
 * Stable-sorts the (itag, url) pairs so that, per itag, the first match is a track in
 * [preferredLanguage], then the original, then everything else. A single-track video carries no
 * `xtags` at all, ranks as original and keeps its order — songs are unaffected.
 */
internal fun parseXtags(url: String): Map<String, String> =
    XTAGS
        .find(url)
        ?.groupValues
        ?.get(1)
        ?.replace("%3D", "=", ignoreCase = true)
        ?.replace("%3A", ":", ignoreCase = true)
        ?.split(':')
        ?.associate { it.substringBefore('=') to it.substringAfter('=', "") }
        .orEmpty()

/**
 * Returns true when the googlevideo stream URL carries a Dynamic Range Compression marker (`drc=1`
 * or `drc=true` inside `xtags`), which YouTube serves alongside the unaltered master under the
 * same `itag`.
 */
fun isDrcAudioUrl(url: String): Boolean {
    val drc = parseXtags(url)["drc"] ?: return false
    return drc == "1" || drc.equals("true", ignoreCase = true)
}

internal fun List<Pair<Int, String>>.orderByAudioTrack(preferredLanguage: String?): List<Pair<Int, String>> =
    sortedBy { (_, url) -> audioTrackRank(url, preferredLanguage) }

fun audioTrackRank(
    url: String,
    preferredLanguage: String?,
    isDrcFlag: Boolean = false,
): Int {
    val tags = parseXtags(url)
    val acont = tags["acont"]
    val lang = tags["lang"]?.substringBefore('-')
    val drcVal = tags["drc"]
    val isDrc = isDrcFlag || drcVal == "1" || drcVal.equals("true", ignoreCase = true)
    val baseRank =
        when {
            // "descriptive" is narration for blind viewers — never what a language preference means.
            !preferredLanguage.isNullOrBlank() &&
                acont != "descriptive" &&
                lang.equals(preferredLanguage.substringBefore('-'), ignoreCase = true) -> 0
            acont == null || acont == "original" -> 1
            else -> 2
        }
    return baseRank * 2 + if (isDrc) 1 else 0
}

/**
 * Byte length of the exact file [url] points at. The stream is requested with `range=0-<length>`,
 * so the length must come from the chosen URL — the player response lists every language under one
 * itag, and each dub has its own size.
 */
internal fun contentLengthOf(url: String): Long? = CLEN.find(url)?.groupValues?.get(1)?.toLongOrNull()

/**
 * Duration in seconds carried by the `dur=` query parameter of a googlevideo stream URL, or null
 * if absent.
 */
fun durationSecondsOf(url: String): Double? = DUR.find(url)?.groupValues?.get(1)?.toDoubleOrNull()

/**
 * Returns true when [url] has no `dur=` query parameter, [expectedSeconds] is unset/non-positive,
 * or the stream's declared `dur=` matches [expectedSeconds] within `max(2s, 1.5%)`.
 */
fun isUrlDurationCompatible(
    url: String,
    expectedSeconds: Int?,
): Boolean {
    if (expectedSeconds == null || expectedSeconds <= 0) return true
    val streamDuration = durationSecondsOf(url) ?: return true
    val tolerance = maxOf(2.0, expectedSeconds * 0.015)
    return abs(streamDuration - expectedSeconds) <= tolerance
}
