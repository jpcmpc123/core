package com.maxrave.media3.exoplayer

/**
 * Thread-safe audio focus & ducking state machine shared across AudioManager callbacks,
 * player playback lifecycle, and the safety watchdog.
 *
 * Ensures [isDucked] can never remain stuck at ducked volume across track changes, pause/play,
 * transient focus losses, or missing `AUDIOFOCUS_GAIN` callbacks from third-party apps.
 */
internal class AudioFocusDuckState {
    data class GainResult(
        val wasDucked: Boolean,
        val shouldResume: Boolean,
    )

    @Volatile
    var hasAudioFocus: Boolean = false
        private set

    @Volatile
    var resumeOnFocusGain: Boolean = false
        private set

    @Volatile
    var isDucked: Boolean = false
        private set

    @Volatile
    var duckGeneration: Long = 0L
        private set

    @Synchronized
    fun onGain(): GainResult {
        val wasDucked = isDucked
        val shouldResume = resumeOnFocusGain
        isDucked = false
        resumeOnFocusGain = false
        if (wasDucked) {
            duckGeneration++
        }
        return GainResult(wasDucked = wasDucked, shouldResume = shouldResume)
    }

    @Synchronized
    fun onLossPermanent(): Boolean {
        val wasDucked = isDucked
        isDucked = false
        resumeOnFocusGain = false
        hasAudioFocus = false
        if (wasDucked) {
            duckGeneration++
        }
        return wasDucked
    }

    @Synchronized
    fun onLossTransient(wasPlayingOrPreparing: Boolean): Boolean {
        val wasDucked = isDucked
        isDucked = false
        hasAudioFocus = false
        resumeOnFocusGain = wasPlayingOrPreparing
        if (wasDucked) {
            duckGeneration++
        }
        return wasDucked
    }

    @Synchronized
    fun onLossTransientCanDuck(): Long {
        isDucked = true
        return ++duckGeneration
    }

    /**
     * Called on explicit user playback actions (play, next, previous, track load with shouldPlay=true).
     * Clears any stuck [isDucked] state and returns true if focus must be re-requested cleanly.
     */
    @Synchronized
    fun recoverOnUserAction(): Boolean {
        resumeOnFocusGain = false
        if (!isDucked) return false
        isDucked = false
        duckGeneration++
        return true
    }

    @Synchronized
    fun clearResumeOnFocusGain() {
        resumeOnFocusGain = false
    }

    @Synchronized
    fun setResumeOnFocusGain(shouldResume: Boolean) {
        resumeOnFocusGain = shouldResume
    }

    @Synchronized
    fun recoverOnWatchdogTimeout(
        expectedGeneration: Long,
        isStillActive: Boolean,
    ): Boolean {
        if (!isDucked || duckGeneration != expectedGeneration || !isStillActive) return false
        isDucked = false
        duckGeneration++
        return true
    }

    @Synchronized
    fun onFocusRequested(granted: Boolean) {
        hasAudioFocus = granted
    }

    @Synchronized
    fun onAbandon(): Boolean {
        val held = hasAudioFocus
        isDucked = false
        hasAudioFocus = false
        resumeOnFocusGain = false
        duckGeneration++
        return held
    }
}
