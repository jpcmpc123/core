package com.maxrave.kotlinytmusicscraper.extractor

/**
 * Typed result of a lightweight stream URL HEAD probe so callers can tell a genuine HTTP
 * rejection (`403`, `404`, etc.) apart from a transient 4G timeout, DNS failure, or
 * cancellation.
 */
sealed interface StreamProbeResult {
    data object Reachable : StreamProbeResult

    data class Rejected(
        val httpCode: Int,
    ) : StreamProbeResult

    data class TransportError(
        val reason: String,
        val isOffline: Boolean,
    ) : StreamProbeResult

    data object Cancelled : StreamProbeResult
}
