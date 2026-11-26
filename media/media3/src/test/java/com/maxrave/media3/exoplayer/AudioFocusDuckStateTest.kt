package com.maxrave.media3.exoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFocusDuckStateTest {
    @Test
    fun gainAfterDuckClearsDuckAndResumesWhenRequested() {
        val state = AudioFocusDuckState()
        state.onFocusRequested(true)

        state.onLossTransientCanDuck()
        assertTrue(state.isDucked)
        assertTrue(state.hasAudioFocus)

        val gain = state.onGain()
        assertTrue(gain.wasDucked)
        assertFalse(gain.shouldResume)
        assertFalse(state.isDucked)
    }

    @Test
    fun transientLossClearsDuckAndForcesFocusReRequest() {
        val state = AudioFocusDuckState()
        state.onFocusRequested(true)
        state.onLossTransientCanDuck()
        assertTrue(state.isDucked)

        val wasDucked = state.onLossTransient(wasPlayingOrPreparing = true)
        assertTrue(wasDucked)
        assertFalse(state.isDucked)
        assertFalse(state.hasAudioFocus)
        assertTrue(state.resumeOnFocusGain)

        val gain = state.onGain()
        assertFalse(gain.wasDucked)
        assertTrue(gain.shouldResume)
        assertFalse(state.resumeOnFocusGain)
    }

    @Test
    fun userActionClearsStuckDuckWhenGainNeverArrives() {
        val state = AudioFocusDuckState()
        state.onFocusRequested(true)
        state.onLossTransientCanDuck()
        assertTrue(state.isDucked)

        val mustRerequest = state.recoverOnUserAction()
        assertTrue(mustRerequest)
        assertFalse(state.isDucked)

        // Subsequent user action when already un-ducked is a no-op.
        assertFalse(state.recoverOnUserAction())
    }

    @Test
    fun watchdogTimeoutClearsOnlyMatchingActiveGeneration() {
        val state = AudioFocusDuckState()
        state.onFocusRequested(true)

        val gen1 = state.onLossTransientCanDuck()
        // New focus gain invalidates old watchdog generation.
        state.onGain()
        assertFalse(state.recoverOnWatchdogTimeout(gen1, isStillActive = true))

        val gen2 = state.onLossTransientCanDuck()
        // Inactive playback does not consume the watchdog state prematurely.
        assertFalse(state.recoverOnWatchdogTimeout(gen2, isStillActive = false))
        assertTrue(state.isDucked)

        // Matching generation while playing restores volume.
        assertTrue(state.recoverOnWatchdogTimeout(gen2, isStillActive = true))
        assertFalse(state.isDucked)
        assertEquals(gen2 + 1L, state.duckGeneration)
    }
}
