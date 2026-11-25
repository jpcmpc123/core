package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Deterministic, cancelable lyrics orchestrator:
 * - Optional cache-first SWR emission for instant display and offline resilience.
 * - Tries preferred provider first with bounded timeout and parallel hedged fallback.
 * - Ranks quality (RICH_SYNCED > LINE_SYNCED > UNSYNCED), upgrading in background without
 *   ever downgrading or overwriting user-authored lyrics.
 * - Distinguishes definitive 404/empty exhaustion from transient network failures.
 */
class LyricsResolver(
    private val readCache: suspend (videoId: String) -> Lyrics?,
    private val fetchSource: suspend (source: LyricsSource, request: LyricsLookupRequest) -> ProviderFetchOutcome,
    private val providerTimeoutMs: Long = DEFAULT_PROVIDER_TIMEOUT_MS,
    private val hedgeDelayMs: Long = DEFAULT_HEDGE_DELAY_MS,
) {
    fun resolve(request: LyricsLookupRequest): Flow<LyricsResolutionEvent> =
        channelFlow {
            var currentBestLyrics: Lyrics? = null
            var currentBestLevel = LyricsQuality.LEVEL_INVALID
            var currentBestOverflow = false
            var currentBestSource: LyricsSource? = null
            var currentFromCache = false
            var cachedSignature: Int? = null
            var simpMusicNotFoundObserved = false
            var anyTransientFailure = false
            var cacheInvalidated = false

            var verifiedBestLyrics: Lyrics? = null
            var letrasJudgeChecked = false
            var ytmOfficialJudgeChecked = false
            var cachedLetrasOutcome: ProviderFetchOutcome? = null
            var cachedYtmOfficialOutcome: ProviderFetchOutcome? = null
            var pendingBlindCandidate: LyricsResolution? = null
            var pendingBlindLevel = LyricsQuality.LEVEL_INVALID
            var pendingBlindOverflow = false
            var onlineAcceptedAny = false
            val finishedSources = HashSet<LyricsSource>(8)

            val titleInstrumental = LyricsInstrumentalDetector.isExplicitlyInstrumentalTitle(request.title)
            if (request.preferCache) {
                val cached = readCache(request.videoId)
                val cachedLevel = LyricsQuality.level(cached)
                if (cached != null && cachedLevel > LyricsQuality.LEVEL_INVALID) {
                    val signature = LyricsQuality.contentSignature(cached)
                    val isUserProtected = signature in request.userProtectedSignatures
                    if (!isUserProtected && titleInstrumental) {
                        send(
                            LyricsResolutionEvent.Exhausted(
                                videoId = request.videoId,
                                definitiveNotFound = true,
                                confirmedInstrumental = true,
                            ),
                        )
                        return@channelFlow
                    }
                    val cachedContradicts =
                        !isUserProtected &&
                            (
                                LyricsIdentityGuard.contradictsTrack(cached, request.title, request.artist) ||
                                    LyricsContentQualityGuard.hasInvalidOrMismatchedSyncedTiming(cached, request.durationSeconds)
                            )
                    if (cachedContradicts) {
                        cacheInvalidated = true
                    } else {
                        val cachedOverflow = LyricsQuality.hasSyncedTimestampOverflow(cached, request.durationSeconds)
                        cachedSignature = signature
                        currentBestLyrics = cached
                        currentBestLevel = cachedLevel
                        currentBestOverflow = cachedOverflow
                        currentBestSource = LyricsSource.OFFLINE
                        currentFromCache = true
                        send(
                            LyricsResolutionEvent.Found(
                                LyricsResolution(
                                    videoId = request.videoId,
                                    lyrics = cached,
                                    source = LyricsSource.OFFLINE,
                                    fromCache = true,
                                    isUserProtected = isUserProtected,
                                ),
                            ),
                        )
                        if (isUserProtected ||
                            (!cachedOverflow && !request.revalidateStaleCache && (cachedLevel >= LyricsQuality.LEVEL_RICH_SYNCED || !request.allowUpgradeScan))
                        ) {
                            return@channelFlow
                        }
                    }
                }
            }
            if (titleInstrumental) {
                send(
                    LyricsResolutionEvent.Exhausted(
                        videoId = request.videoId,
                        definitiveNotFound = true,
                        confirmedInstrumental = true,
                    ),
                )
                return@channelFlow
            }

            val sources = orderedSources(request.preferredSource, request.allowSpotify, request.preferYtmOfficialFirst)
            if (sources.isEmpty()) {
                if (currentBestLyrics == null) {
                    send(LyricsResolutionEvent.Exhausted(request.videoId, definitiveNotFound = true))
                }
                return@channelFlow
            }

            val verificationSources = sources.filter { it.isCatalogVerified() }.toSet()

            fun hasPendingVerificationSource(): Boolean = verificationSources.any { it !in finishedSources }

            fun canAcceptBlindCandidate(candidate: Lyrics): Boolean {
                val verified = verifiedBestLyrics ?: return true
                return LyricsIdentityGuard.checkTextualConsensus(verified, candidate) != TextualConsensus.CONFLICT
            }

            suspend fun emitCandidate(
                resolution: LyricsResolution,
                level: Int,
                overflow: Boolean,
                isFromCache: Boolean = false,
            ) {
                val cleanedResolution =
                    if (!isFromCache && !resolution.source.isCatalogVerified() && verifiedBestLyrics != null) {
                        val stripped =
                            LyricsHeaderSanitizer.stripLeadingNonSungLinesAgainstReference(
                                candidate = resolution.lyrics,
                                reference = verifiedBestLyrics,
                            )
                        if (stripped !== resolution.lyrics) resolution.copy(lyrics = stripped) else resolution
                    } else {
                        resolution
                    }
                if (!isFromCache) onlineAcceptedAny = true
                currentBestLyrics = cleanedResolution.lyrics
                currentBestLevel = level
                currentBestOverflow = overflow
                currentBestSource = cleanedResolution.source
                currentFromCache = isFromCache
                send(LyricsResolutionEvent.Found(cleanedResolution))
            }

            suspend fun ensureFallbackJudgesChecked() {
                if (!ytmOfficialJudgeChecked) {
                    ytmOfficialJudgeChecked = true
                    if (LyricsSource.YTM_OFFICIAL !in finishedSources) {
                        val ytmOutcome =
                            try {
                                withTimeoutOrNull(providerTimeoutMs) {
                                    fetchSource(LyricsSource.YTM_OFFICIAL, request)
                                } ?: ProviderFetchOutcome.TransientFailure("Timeout after ${providerTimeoutMs}ms")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                ProviderFetchOutcome.TransientFailure(t.message)
                            }
                        cachedYtmOfficialOutcome = ytmOutcome
                        finishedSources.add(LyricsSource.YTM_OFFICIAL)
                        if (ytmOutcome is ProviderFetchOutcome.Success &&
                            LyricsQuality.level(ytmOutcome.lyrics) > LyricsQuality.LEVEL_INVALID &&
                            !LyricsIdentityGuard.contradictsTrack(ytmOutcome.lyrics, request.title, request.artist) &&
                            verifiedBestLyrics == null
                        ) {
                            verifiedBestLyrics = ytmOutcome.lyrics
                        }
                    }
                }
                if (verifiedBestLyrics == null && !letrasJudgeChecked) {
                    letrasJudgeChecked = true
                    if (LyricsSource.LETRAS !in finishedSources) {
                        val outcome =
                            try {
                                withTimeoutOrNull(providerTimeoutMs) {
                                    fetchSource(LyricsSource.LETRAS, request)
                                } ?: ProviderFetchOutcome.TransientFailure("Timeout after ${providerTimeoutMs}ms")
                            } catch (e: CancellationException) {
                                throw e
                            } catch (t: Throwable) {
                                ProviderFetchOutcome.TransientFailure(t.message)
                            }
                        cachedLetrasOutcome = outcome
                        finishedSources.add(LyricsSource.LETRAS)
                        if (outcome is ProviderFetchOutcome.Success &&
                            LyricsQuality.level(outcome.lyrics) > LyricsQuality.LEVEL_INVALID &&
                            !LyricsIdentityGuard.contradictsTrack(outcome.lyrics, request.title, request.artist) &&
                            verifiedBestLyrics == null
                        ) {
                            verifiedBestLyrics = outcome.lyrics
                        }
                    }
                }
            }

            fun bestFallbackJudgeResolution(): Pair<LyricsSource, Lyrics>? {
                val ytmOutcome = cachedYtmOfficialOutcome
                if (ytmOutcome is ProviderFetchOutcome.Success &&
                    LyricsQuality.level(ytmOutcome.lyrics) > LyricsQuality.LEVEL_INVALID &&
                    !LyricsIdentityGuard.contradictsTrack(ytmOutcome.lyrics, request.title, request.artist)
                ) {
                    return LyricsSource.YTM_OFFICIAL to ytmOutcome.lyrics
                }
                val letrasOutcome = cachedLetrasOutcome
                if (letrasOutcome is ProviderFetchOutcome.Success &&
                    LyricsQuality.level(letrasOutcome.lyrics) > LyricsQuality.LEVEL_INVALID &&
                    !LyricsIdentityGuard.contradictsTrack(letrasOutcome.lyrics, request.title, request.artist)
                ) {
                    return LyricsSource.LETRAS to letrasOutcome.lyrics
                }
                return null
            }

            var pendingUnsyncedCandidate: LyricsResolution? = null
            var pendingUnsyncedFromVerified = false

            fun hasPendingSyncedSource(): Boolean =
                sources.any { candidateSource ->
                    candidateSource != LyricsSource.YTM_OFFICIAL &&
                        candidateSource != LyricsSource.YOUTUBE &&
                        candidateSource !in finishedSources
                }

            fun recordPendingUnsynced(
                resolution: LyricsResolution,
                fromVerified: Boolean,
            ) {
                if (pendingUnsyncedCandidate == null ||
                    (fromVerified && !pendingUnsyncedFromVerified) ||
                    resolution.source == LyricsSource.YTM_OFFICIAL
                ) {
                    pendingUnsyncedCandidate = resolution
                    pendingUnsyncedFromVerified = fromVerified
                }
            }

            suspend fun tryFlushPendingBlindCandidate() {
                val pending = pendingBlindCandidate ?: return
                if (verifiedBestLyrics == null && hasPendingVerificationSource()) return
                if (verifiedBestLyrics == null && (!ytmOfficialJudgeChecked || !letrasJudgeChecked)) {
                    ensureFallbackJudgesChecked()
                }
                if (!canAcceptBlindCandidate(pending.lyrics)) {
                    pendingBlindCandidate = null
                    pendingBlindLevel = LyricsQuality.LEVEL_INVALID
                    pendingBlindOverflow = false
                    val fallbackPair = bestFallbackJudgeResolution()
                    if (fallbackPair != null) {
                        val (judgeSource, judgeLyrics) = fallbackPair
                        val activeSource = currentBestSource
                        val currentConflicts =
                            currentBestLyrics != null &&
                                activeSource != null &&
                                !activeSource.isCatalogVerified() &&
                                LyricsIdentityGuard.checkTextualConsensus(judgeLyrics, currentBestLyrics) == TextualConsensus.CONFLICT
                        if (currentBestLyrics == null || currentConflicts || (currentFromCache && request.revalidateStaleCache && !onlineAcceptedAny)) {
                            emitCandidate(
                                resolution =
                                    LyricsResolution(
                                        videoId = request.videoId,
                                        lyrics = judgeLyrics,
                                        source = judgeSource,
                                        fromCache = false,
                                        simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                    ),
                                level = LyricsQuality.level(judgeLyrics),
                                overflow = false,
                            )
                        }
                    }
                    return
                }

                if (pendingBlindLevel == LyricsQuality.LEVEL_UNSYNCED && hasPendingSyncedSource()) {
                    recordPendingUnsynced(
                        resolution = pending.copy(simpMusicNotFoundObserved = simpMusicNotFoundObserved),
                        fromVerified = pending.source.isCatalogVerified(),
                    )
                    pendingBlindCandidate = null
                    pendingBlindLevel = LyricsQuality.LEVEL_INVALID
                    pendingBlindOverflow = false
                    return
                }

                val isStaleMigrationReplace = currentFromCache && request.revalidateStaleCache && !onlineAcceptedAny
                val isBetterThanCurrent =
                    isStaleMigrationReplace ||
                        pendingBlindLevel > currentBestLevel ||
                        (currentBestLyrics != null && pendingBlindLevel == currentBestLevel && currentBestOverflow && !pendingBlindOverflow) ||
                        currentBestLyrics == null
                if (isBetterThanCurrent) {
                    emitCandidate(
                        resolution = pending.copy(simpMusicNotFoundObserved = simpMusicNotFoundObserved),
                        level = pendingBlindLevel,
                        overflow = pendingBlindOverflow,
                    )
                }
                pendingBlindCandidate = null
                pendingBlindLevel = LyricsQuality.LEVEL_INVALID
                pendingBlindOverflow = false
            }

            val primary = sources.first()
            val others = sources.drop(1)
            val completionChannel = Channel<Pair<LyricsSource, ProviderFetchOutcome>>(sources.size)
            val launchMutex = Mutex()
            val launchedOtherJobs = ArrayList<Job>(others.size)
            var othersLaunched = false
            var pendingCount = 1

            fun launchSource(source: LyricsSource): Job =
                launch(start = CoroutineStart.DEFAULT) {
                    val outcome =
                        try {
                            withTimeoutOrNull(providerTimeoutMs) {
                                fetchSource(source, request)
                            } ?: ProviderFetchOutcome.TransientFailure("Timeout after ${providerTimeoutMs}ms")
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            ProviderFetchOutcome.TransientFailure(t.message)
                        }
                    completionChannel.trySend(source to outcome)
                }

            suspend fun ensureOthersLaunched() {
                launchMutex.withLock {
                    if (othersLaunched || others.isEmpty()) return
                    othersLaunched = true
                    pendingCount += others.size
                    others.forEach { fallbackSource ->
                        launchedOtherJobs.add(launchSource(fallbackSource))
                    }
                }
            }

            val primaryJob = launchSource(primary)
            val hedgeJob =
                if (others.isNotEmpty()) {
                    launch {
                        delay(hedgeDelayMs)
                        ensureOthersLaunched()
                    }
                } else {
                    null
                }

            var handled = 0
            while (true) {
                val expected = launchMutex.withLock { pendingCount }
                if (handled >= expected) break
                val (source, outcome) = completionChannel.receive()
                handled++
                finishedSources.add(source)
                if (source == LyricsSource.YTM_OFFICIAL) {
                    ytmOfficialJudgeChecked = true
                    cachedYtmOfficialOutcome = outcome
                } else if (source == LyricsSource.LETRAS) {
                    letrasJudgeChecked = true
                    cachedLetrasOutcome = outcome
                }

                when (outcome) {
                    is ProviderFetchOutcome.ConfirmedInstrumental -> {
                        hedgeJob?.cancel()
                        primaryJob.cancel()
                        launchedOtherJobs.forEach { it.cancel() }
                        send(
                            LyricsResolutionEvent.Exhausted(
                                videoId = request.videoId,
                                definitiveNotFound = true,
                                confirmedInstrumental = true,
                            ),
                        )
                        return@channelFlow
                    }
                    is ProviderFetchOutcome.DefinitiveNotFound -> {
                        if (source == LyricsSource.SIMPMUSIC) {
                            simpMusicNotFoundObserved = true
                        }
                    }
                    is ProviderFetchOutcome.TransientFailure -> {
                        anyTransientFailure = true
                    }
                    is ProviderFetchOutcome.Success -> {
                        val candidateLevel = LyricsQuality.level(outcome.lyrics)
                        val contradicts =
                            LyricsIdentityGuard.contradictsTrack(
                                lyrics = outcome.lyrics,
                                expectedTitle = request.title,
                                expectedArtist = request.artist,
                            )
                        val brokenTiming =
                            LyricsContentQualityGuard.hasInvalidOrMismatchedSyncedTiming(
                                lyrics = outcome.lyrics,
                                durationSeconds = request.durationSeconds,
                            )
                        val needsConsensus = source.isBlindMetadataSource() || outcome.requiresVerification
                        val conflictsWithVerified =
                            !source.isCatalogVerified() &&
                                !needsConsensus &&
                                verifiedBestLyrics != null &&
                                LyricsIdentityGuard.checkTextualConsensus(verifiedBestLyrics, outcome.lyrics) == TextualConsensus.CONFLICT
                        if (candidateLevel > LyricsQuality.LEVEL_INVALID && !contradicts && !brokenTiming && !conflictsWithVerified) {
                            val candidateOverflow =
                                LyricsQuality.hasSyncedTimestampOverflow(outcome.lyrics, request.durationSeconds)
                            if (source.isCatalogVerified() && verifiedBestLyrics == null) {
                                verifiedBestLyrics = outcome.lyrics
                            }

                            if (needsConsensus &&
                                verifiedBestLyrics == null &&
                                (hasPendingVerificationSource() || !ytmOfficialJudgeChecked || !letrasJudgeChecked)
                            ) {
                                // Defer blind/unverified provider emission until catalog-verified sources
                                // (or YTM_OFFICIAL / Letras judges) confirm the lyrics belong to the same song.
                                val shouldHold =
                                    pendingBlindCandidate == null ||
                                        candidateLevel > pendingBlindLevel ||
                                        (candidateLevel == pendingBlindLevel && pendingBlindOverflow && !candidateOverflow)
                                if (shouldHold) {
                                    pendingBlindCandidate =
                                        LyricsResolution(
                                            videoId = request.videoId,
                                            lyrics = outcome.lyrics,
                                            source = source,
                                            fromCache = false,
                                            youtubeTranslatedLyrics = outcome.translatedLyrics,
                                            simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                            attribution = outcome.attribution,
                                        )
                                    pendingBlindLevel = candidateLevel
                                    pendingBlindOverflow = candidateOverflow
                                }
                            } else if (!needsConsensus || canAcceptBlindCandidate(outcome.lyrics)) {
                                if (candidateLevel == LyricsQuality.LEVEL_UNSYNCED && hasPendingSyncedSource()) {
                                    recordPendingUnsynced(
                                        resolution =
                                            LyricsResolution(
                                                videoId = request.videoId,
                                                lyrics = outcome.lyrics,
                                                source = source,
                                                fromCache = false,
                                                youtubeTranslatedLyrics = outcome.translatedLyrics,
                                                simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                                attribution = outcome.attribution,
                                            ),
                                        fromVerified = source.isCatalogVerified(),
                                    )
                                } else {
                                    val signature = LyricsQuality.contentSignature(outcome.lyrics)
                                    val isStaleMigrationReplace =
                                        currentFromCache &&
                                            request.revalidateStaleCache &&
                                            !onlineAcceptedAny &&
                                            (!candidateOverflow || currentBestOverflow)
                                    val isSameTierCacheRefresh =
                                        currentFromCache &&
                                            source == request.preferredSource &&
                                            candidateLevel == currentBestLevel &&
                                            signature != cachedSignature &&
                                            (!candidateOverflow || currentBestOverflow)
                                    val isTimingFix =
                                        currentBestLyrics != null &&
                                            candidateLevel == currentBestLevel &&
                                            currentBestOverflow &&
                                            !candidateOverflow
                                    val isUpgrade = candidateLevel > currentBestLevel
                                    val activeSource = currentBestSource
                                    val isIdentityCorrection =
                                        source.isCatalogVerified() &&
                                            currentBestLyrics != null &&
                                            activeSource != null &&
                                            !activeSource.isCatalogVerified() &&
                                            LyricsIdentityGuard.checkTextualConsensus(outcome.lyrics, currentBestLyrics) == TextualConsensus.CONFLICT
                                    if (isUpgrade || isTimingFix || isSameTierCacheRefresh || isIdentityCorrection || isStaleMigrationReplace) {
                                        emitCandidate(
                                            resolution =
                                                LyricsResolution(
                                                    videoId = request.videoId,
                                                    lyrics = outcome.lyrics,
                                                    source = source,
                                                    fromCache = false,
                                                    youtubeTranslatedLyrics = outcome.translatedLyrics,
                                                    simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                                                    attribution = outcome.attribution,
                                                ),
                                            level = candidateLevel,
                                            overflow = candidateOverflow,
                                        )
                                    }
                                }
                            }
                        } else {
                            // Unusable, broken-timed, or contradictory payload treated as missing so fallback continues cleanly.
                            if (source == LyricsSource.SIMPMUSIC) simpMusicNotFoundObserved = true
                        }
                    }
                }

                tryFlushPendingBlindCandidate()

                if (currentBestLevel >= LyricsQuality.LEVEL_RICH_SYNCED &&
                    !currentBestOverflow &&
                    (!currentFromCache || !request.revalidateStaleCache || onlineAcceptedAny)
                ) {
                    hedgeJob?.cancel()
                    primaryJob.cancel()
                    launchedOtherJobs.forEach { it.cancel() }
                    break
                }

                // If primary finished (with not-found, < RICH_SYNCED, or overflowing timestamps),
                // or a blind/unverified candidate needs verification, or we have no synced lyrics yet: launch others immediately.
                if (source == primary ||
                    currentBestLyrics == null ||
                    pendingBlindCandidate != null ||
                    pendingUnsyncedCandidate != null ||
                    (currentFromCache && request.revalidateStaleCache && !onlineAcceptedAny)
                ) {
                    val allowMoreSources =
                        currentBestLyrics == null ||
                            request.allowUpgradeScan ||
                            request.revalidateStaleCache ||
                            currentBestOverflow ||
                            (pendingBlindCandidate != null && hasPendingVerificationSource()) ||
                            (pendingUnsyncedCandidate != null && hasPendingSyncedSource())
                    if (allowMoreSources) {
                        hedgeJob?.cancel()
                        ensureOthersLaunched()
                    } else if (source == primary) {
                        hedgeJob?.cancel()
                        launchedOtherJobs.forEach { it.cancel() }
                        break
                    }
                }
            }

            tryFlushPendingBlindCandidate()

            // Official static lyrics fallback (YouTube Music Musixmatch/LyricFind -> held online UNSYNCED -> Letras.mus.br):
            // queried/flushed only when no online synced provider matched cleanly (including Live/Remix tracks
            // where stale cache must be superseded by accurate unsynced lyrics).
            if (currentBestLyrics == null || (currentFromCache && request.revalidateStaleCache && !onlineAcceptedAny)) {
                if (!ytmOfficialJudgeChecked || !letrasJudgeChecked) {
                    ensureFallbackJudgesChecked()
                }
                val fallbackPair = bestFallbackJudgeResolution()
                if (fallbackPair != null) {
                    val (judgeSource, judgeLyrics) = fallbackPair
                    emitCandidate(
                        resolution =
                            LyricsResolution(
                                videoId = request.videoId,
                                lyrics = judgeLyrics,
                                source = judgeSource,
                                fromCache = false,
                                simpMusicNotFoundObserved = simpMusicNotFoundObserved,
                            ),
                        level = LyricsQuality.level(judgeLyrics),
                        overflow = false,
                    )
                } else {
                    val heldUnsynced = pendingUnsyncedCandidate
                    if (heldUnsynced != null) {
                        emitCandidate(
                            resolution = heldUnsynced.copy(simpMusicNotFoundObserved = simpMusicNotFoundObserved),
                            level = LyricsQuality.level(heldUnsynced.lyrics),
                            overflow = false,
                        )
                    }
                }
            }

            if (currentBestLyrics == null) {
                send(
                    LyricsResolutionEvent.Exhausted(
                        videoId = request.videoId,
                        definitiveNotFound = !anyTransientFailure,
                        cacheInvalidated = cacheInvalidated,
                    ),
                )
            }
        }

    companion object {
        const val DEFAULT_PROVIDER_TIMEOUT_MS = 8_000L
        const val DEFAULT_HEDGE_DELAY_MS = 1_800L

        fun orderedSources(
            preferred: LyricsSource,
            allowSpotify: Boolean,
            preferYtmOfficialFirst: Boolean = false,
        ): List<LyricsSource> {
            val baseOrder =
                listOf(
                    LyricsSource.LRCLIB,
                    LyricsSource.UNISON,
                    LyricsSource.NETEASE,
                    LyricsSource.SPOTIFY,
                    LyricsSource.BETTER_LYRICS,
                    LyricsSource.SIMPMUSIC,
                    LyricsSource.YTM_OFFICIAL,
                    LyricsSource.YOUTUBE,
                )
            return buildList {
                if (preferYtmOfficialFirst && preferred == LyricsSource.LRCLIB) {
                    add(LyricsSource.YTM_OFFICIAL)
                } else if (preferred != LyricsSource.OFFLINE &&
                    preferred != LyricsSource.LETRAS &&
                    (preferred != LyricsSource.SPOTIFY || allowSpotify)
                ) {
                    add(preferred)
                }
                for (source in baseOrder) {
                    if (source !in this && (source != LyricsSource.SPOTIFY || allowSpotify)) {
                        add(source)
                    }
                }
            }
        }
    }
}
