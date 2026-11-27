package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Media3 [AudioProcessor] that applies a real-time Biquad low-pass or high-pass filter
 * to the audio stream. Used for DJ-style crossfade transitions.
 *
 * Key design:
 * - [enabled], [cutoffFrequencyHz], and [filterType] can be changed at runtime (thread-safe).
 * - When [enabled] is false, [isActive] returns false and ExoPlayer skips this processor
 *   entirely — zero CPU overhead during normal playback.
 * - Coefficient recalculation is lazy: only when cutoff or filter type changes.
 * - Supports PCM 16-bit audio (mono and stereo).
 */
@UnstableApi
class CrossfadeFilterAudioProcessor : BaseAudioProcessor() {

    private data class FilterConfig(
        val enabled: Boolean = false,
        val filterType: BiquadFilter.FilterType = BiquadFilter.FilterType.LOW_PASS,
        val cutoffFrequencyHz: Float = DEFAULT_CUTOFF_HZ,
    )

    @Volatile
    private var config: FilterConfig = FilterConfig()

    /**
     * Atomically update enabled state, filter type, and cutoff frequency in a single write
     * so the audio thread never observes a torn combination across fields.
     */
    fun configure(
        enabled: Boolean,
        filterType: BiquadFilter.FilterType,
        cutoffFrequencyHz: Float,
    ) {
        config = FilterConfig(enabled = enabled, filterType = filterType, cutoffFrequencyHz = cutoffFrequencyHz)
    }

    /**
     * Whether the filter is active. When false, audio ramps cleanly back to dry passthrough.
     */
    var enabled: Boolean
        get() = config.enabled
        set(value) {
            val current = config
            if (current.enabled != value) {
                config = current.copy(enabled = value)
            }
        }

    /**
     * Current cutoff frequency in Hz. Updated during crossfade animation.
     */
    var cutoffFrequencyHz: Float
        get() = config.cutoffFrequencyHz
        set(value) {
            val current = config
            if (current.cutoffFrequencyHz != value) {
                config = current.copy(cutoffFrequencyHz = value)
            }
        }

    /**
     * Filter type: LOW_PASS for outgoing track, HIGH_PASS for incoming track.
     */
    var filterType: BiquadFilter.FilterType
        get() = config.filterType
        set(value) {
            val current = config
            if (current.filterType != value) {
                config = current.copy(filterType = value)
            }
        }

    private val filter = BiquadFilter()

    // Audio-thread-only tracking of the last applied filter parameters and dry/wet ramp.
    private var appliedFilterType: BiquadFilter.FilterType? = null
    private var appliedCutoffHz: Float = -1f
    private var appliedSampleRate: Int = 0
    private var wetMix: Double = 0.0
    private var wetRampStep: Double = 1.0 / MIN_RAMP_SAMPLES

    private var encoding = C.ENCODING_INVALID
    private var sampleRate = 0
    private var channelCount = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        val rampSamples = (sampleRate * DRY_WET_RAMP_SECONDS).toInt().coerceAtLeast(MIN_RAMP_SAMPLES)
        wetRampStep = 1.0 / rampSamples
        appliedSampleRate = 0
        return inputAudioFormat
    }

    override fun isActive(): Boolean = super.isActive()

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val snapshot = config
        val targetWet = if (snapshot.enabled) 1.0 else 0.0

        // Fast zero-overhead passthrough when fully disabled and ramp-out finished.
        if (sampleRate == 0 || (!snapshot.enabled && wetMix <= 0.0)) {
            if (appliedFilterType != null) {
                filter.reset()
                appliedFilterType = null
            }
            val output = replaceOutputBuffer(remaining)
            copyBuffer(inputBuffer, output, remaining)
            output.flip()
            return
        }

        // Freshly engaging the filter from dry passthrough: clear any stale delay history first.
        if (snapshot.enabled && wetMix <= 0.0) {
            filter.reset()
        }

        // Recalculate coefficients on the audio thread only when cutoff, type, or sample rate changed.
        if (appliedFilterType != snapshot.filterType ||
            appliedCutoffHz != snapshot.cutoffFrequencyHz ||
            appliedSampleRate != sampleRate
        ) {
            filter.updateCoefficients(
                cutoffHz = snapshot.cutoffFrequencyHz,
                sampleRate = sampleRate,
                type = snapshot.filterType,
            )
            appliedFilterType = snapshot.filterType
            appliedCutoffHz = snapshot.cutoffFrequencyHz
            appliedSampleRate = sampleRate
        }

        val output = replaceOutputBuffer(remaining)
        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                when (channelCount) {
                    1 -> processMonoFloatBlock(inputBuffer, output, targetWet)
                    2 -> processStereoFloatBlock(inputBuffer, output, targetWet)
                    else -> copyBuffer(inputBuffer, output, remaining)
                }
            }
            C.ENCODING_PCM_16BIT -> {
                when (channelCount) {
                    1 -> processMonoBlock(inputBuffer, output, targetWet)
                    2 -> processStereoBlock(inputBuffer, output, targetWet)
                    else -> copyBuffer(inputBuffer, output, remaining)
                }
            }
            else -> copyBuffer(inputBuffer, output, remaining)
        }

        if (!snapshot.enabled && wetMix <= 0.0) {
            filter.reset()
            appliedFilterType = null
        }

        output.flip()
    }

    private fun copyBuffer(src: ByteBuffer, dst: ByteBuffer, size: Int) {
        if (src === dst) {
            dst.position(0)
            dst.limit(size)
            return
        }
        if (src.remaining() == size && dst.remaining() >= size) {
            dst.put(src)
        } else {
            val pos = src.position()
            for (i in 0 until size) {
                dst.put(src.get(pos + i))
            }
            src.position(pos + size)
        }
    }

    private fun stepWetMix(targetWet: Double): Double {
        if (wetMix < targetWet) {
            wetMix = (wetMix + wetRampStep).coerceAtMost(targetWet)
        } else if (wetMix > targetWet) {
            wetMix = (wetMix - wetRampStep).coerceAtLeast(targetWet)
        }
        return wetMix
    }

    private fun processMonoBlock(input: ByteBuffer, output: ByteBuffer, targetWet: Double) {
        while (input.remaining() >= 2) {
            val dry = input.short.toDouble() / Short.MAX_VALUE
            val wet = filter.processSampleMono(dry)
            val mix = stepWetMix(targetWet)
            val out = if (mix >= 1.0) wet else dry * (1.0 - mix) + wet * mix
            output.putShort((out.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    private fun processStereoBlock(input: ByteBuffer, output: ByteBuffer, targetWet: Double) {
        while (input.remaining() >= 4) {
            val dryL = input.short.toDouble() / Short.MAX_VALUE
            val dryR = input.short.toDouble() / Short.MAX_VALUE
            val wetL = filter.processStereoLeft(dryL)
            val wetR = filter.processStereoRight(dryR)
            val mix = stepWetMix(targetWet)
            val outL = if (mix >= 1.0) wetL else dryL * (1.0 - mix) + wetL * mix
            val outR = if (mix >= 1.0) wetR else dryR * (1.0 - mix) + wetR * mix
            output.putShort((outL.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
            output.putShort((outR.coerceIn(-1.0, 1.0) * Short.MAX_VALUE).toInt().toShort())
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    private fun processMonoFloatBlock(input: ByteBuffer, output: ByteBuffer, targetWet: Double) {
        while (input.remaining() >= 4) {
            val dry = input.float.toDouble()
            val wet = filter.processSampleMono(dry)
            val mix = stepWetMix(targetWet)
            val out = if (mix >= 1.0) wet else dry * (1.0 - mix) + wet * mix
            output.putFloat(out.toFloat())
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    private fun processStereoFloatBlock(input: ByteBuffer, output: ByteBuffer, targetWet: Double) {
        while (input.remaining() >= 8) {
            val dryL = input.float.toDouble()
            val dryR = input.float.toDouble()
            val wetL = filter.processStereoLeft(dryL)
            val wetR = filter.processStereoRight(dryR)
            val mix = stepWetMix(targetWet)
            val outL = if (mix >= 1.0) wetL else dryL * (1.0 - mix) + wetL * mix
            val outR = if (mix >= 1.0) wetR else dryR * (1.0 - mix) + wetR * mix
            output.putFloat(outL.toFloat())
            output.putFloat(outR.toFloat())
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
    }

    override fun onFlush() {
        super.onFlush()
        wetMix = if (config.enabled) 1.0 else 0.0
        filter.reset()
    }

    override fun onReset() {
        super.onReset()
        config = FilterConfig()
        appliedFilterType = null
        appliedCutoffHz = -1f
        appliedSampleRate = 0
        wetMix = 0.0
        encoding = C.ENCODING_INVALID
        sampleRate = 0
        channelCount = 0
        filter.reset()
    }

    companion object {
        private const val DEFAULT_CUTOFF_HZ = 20000f
        private const val DRY_WET_RAMP_SECONDS = 0.010 // ~10 ms (~480 samples @ 48 kHz)
        private const val MIN_RAMP_SAMPLES = 64
    }
}
