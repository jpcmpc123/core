package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.common.ITAG
import com.maxrave.kotlinytmusicscraper.models.YouTubeClient
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

internal fun List<Pair<Int, String>>.hasRequiredItags(): Boolean {
    val itags = this.mapTo(HashSet()) { it.first }
    val hasAudio = ITAG.AUDIO_PLAYABLE.any { it in itags }
    val hasVideo = ITAG.VIDEO.any { it in itags }
    return hasAudio && hasVideo
}

private fun buildHealthCheckClient(proxy: Proxy?): OkHttpClient =
    extractorHttpClient(proxy)
        .newBuilder()
        .connectTimeout(3500, TimeUnit.MILLISECONDS)
        .readTimeout(3500, TimeUnit.MILLISECONDS)
        .callTimeout(4500, TimeUnit.MILLISECONDS)
        .build()

@Volatile
private var streamHealthCheckClient: OkHttpClient = buildHealthCheckClient(null)

internal fun updateStreamHealthCheckProxy(proxy: Proxy?) {
    streamHealthCheckClient = buildHealthCheckClient(proxy)
}

/**
 * Pick one random URL whose itag belongs to [ITAG.AUDIO] or [ITAG.VIDEO] and HEAD it. Returns
 * [StreamProbeResult] differentiating genuine HTTP rejections from transient 4G timeouts.
 */
internal fun List<Pair<Int, String>>.headCheckTarget(): Pair<Int, String>? =
    ITAG.AUDIO_PLAYABLE.firstNotNullOfOrNull { target -> firstOrNull { it.first == target } }
        ?: firstOrNull { it.first in ITAG.VIDEO }

internal fun List<Pair<Int, String>>.probeRandomStream(trackRegistry: Boolean = true): StreamProbeResult {
    val candidate = headCheckTarget() ?: return StreamProbeResult.Rejected(0)
    if (trackRegistry && ExtractorCallRegistry.isCurrentCancelled()) return StreamProbeResult.Cancelled
    val token = if (trackRegistry) ExtractorCallRegistry.activeToken() else null
    val request =
        okhttp3.Request
            .Builder()
            .head()
            .url(candidate.second)
            .build()
    val call = streamHealthCheckClient.newCall(request)
    if (token != null && !ExtractorCallRegistry.registerCall(token, call)) {
        return StreamProbeResult.Cancelled
    }
    return try {
        call.execute().use { response ->
            if (response.code in 200..299) {
                StreamProbeResult.Reachable
            } else {
                StreamProbeResult.Rejected(response.code)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        if (trackRegistry && ExtractorCallRegistry.isCurrentCancelled()) {
            StreamProbeResult.Cancelled
        } else {
            StreamProbeResult.TransportError(
                reason = e::class.simpleName ?: "IOException",
                isOffline = DirectAudioFastPathState.isOfflineNetworkError(e),
            )
        }
    } finally {
        if (token != null) ExtractorCallRegistry.unregisterCall(token, call)
    }
}

internal fun List<Pair<Int, String>>.headCheckRandomStream(trackRegistry: Boolean = true): Boolean =
    probeRandomStream(trackRegistry) is StreamProbeResult.Reachable

/** Whether the list carries at least one audio stream URL, even with no video stream at all. */
internal fun List<Pair<Int, String>>.hasAudioItag(): Boolean = any { it.first in ITAG.AUDIO_PLAYABLE }

internal fun probeStreamUrl(url: String): StreamProbeResult =
    try {
        val request =
            okhttp3.Request
                .Builder()
                .head()
                .url(url)
                .build()
        streamHealthCheckClient.newCall(request).execute().use { response ->
            if (response.code in 200..299) {
                StreamProbeResult.Reachable
            } else {
                StreamProbeResult.Rejected(response.code)
            }
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        StreamProbeResult.TransportError(
            reason = e::class.simpleName ?: "IOException",
            isOffline = DirectAudioFastPathState.isOfflineNetworkError(e),
        )
    }

internal fun isStreamUrlReachable(url: String): Boolean = probeStreamUrl(url) is StreamProbeResult.Reachable

class BraveNewPipeDownloaderImpl(
    proxy: Proxy?,
) : Downloader() {
    @Volatile
    private var client = extractorHttpClient(proxy)

    fun updateProxy(proxy: Proxy?) {
        client = extractorHttpClient(proxy)
    }

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: Request): Response {
        val token = ExtractorCallRegistry.activeToken()
        if (ExtractorCallRegistry.isCurrentCancelled()) {
            throw IOException("Extraction cancelled")
        }
        val httpMethod = request.httpMethod()
        val url = request.url()
        val headers = request.headers()
        val dataToSend = request.dataToSend()

        val requestBuilder =
            okhttp3.Request
                .Builder()
                .method(httpMethod, dataToSend?.toRequestBody())
                .url(url)
                .addHeader("User-Agent", YouTubeClient.USER_AGENT_WEB)

        headers.forEach { (headerName, headerValueList) ->
            if (headerValueList.size > 1) {
                requestBuilder.removeHeader(headerName)
                headerValueList.forEach { headerValue ->
                    requestBuilder.addHeader(headerName, headerValue)
                }
            } else if (headerValueList.size == 1) {
                requestBuilder.header(headerName, headerValueList[0])
            }
        }

        val call = client.newCall(requestBuilder.build())
        if (!ExtractorCallRegistry.registerCall(token, call)) {
            throw IOException("Extraction cancelled")
        }
        val response =
            try {
                call.execute()
            } finally {
                ExtractorCallRegistry.unregisterCall(token, call)
            }

        if (response.code == 429) {
            response.close()

            throw ReCaptchaException("reCaptcha Challenge requested", url)
        }

        val responseBodyToReturn = response.body?.string()

        val latestUrl = response.request.url.toString()
        return Response(response.code, response.message, response.headers.toMultimap(), responseBodyToReturn, latestUrl)
    }
}