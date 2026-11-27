package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Float-safe wrapper around Media3's [SilenceSkippingAudioProcessor].
 *
 * Media3's built-in [SilenceSkippingAudioProcessor] only accepts `C.ENCODING_PCM_16BIT` and throws
 * [AudioProcessor.UnhandledAudioFormatException] when fed `C.ENCODING_PCM_FLOAT`. Wrapping it lets
 * us enable float audio output (`setEnableAudioFloatOutput(true)`) without ever crashing on native
 * float streams (e.g., FLAC/Opus float decoders) while keeping Skip Silence 100% functional:
 * - When disabled (the common case): [onConfigure] returns [AudioProcessor.AudioFormat.NOT_SET], so
 *   Media3 drops this processor from the active pipeline with zero overhead and zero conversion.
 * - When enabled on `PCM_16BIT`: delegates directly to [SilenceSkippingAudioProcessor].
 * - When enabled on `PCM_FLOAT`: bridges float32 -> int16 -> delegate -> float32 cleanly.
 */
@UnstableApi
class FloatSafeSilenceSkippingAudioProcessor(
    private val delegate: SilenceSkippingAudioProcessor =
        SilenceSkippingAudioProcessor(
            2_000_000,
            20_000f / 2_000_000f,
            2_000_000,
            0,
            256,
        ),
) : BaseAudioProcessor() {
    @Volatile
    private var enabled = false
    private var inputIsFloat = false
    private var scratchPcm16: ByteBuffer = AudioProcessor.EMPTY_BUFFER

    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        delegate.setEnabled(enabled)
    }

    val skippedFrames: Long
        get() = delegate.skippedFrames

    override fun isActive(): Boolean = enabled && super.isActive()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inputIsFloat = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (!enabled) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        val delegateFormat =
            if (inputIsFloat) {
                AudioProcessor.AudioFormat(
                    inputAudioFormat.sampleRate,
                    inputAudioFormat.channelCount,
                    C.ENCODING_PCM_16BIT,
                )
            } else {
                inputAudioFormat
            }
        delegate.configure(delegateFormat)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        if (enabled) {
            delegate.flush(streamMetadata)
        }
    }

    override fun onReset() {
        delegate.reset()
        inputIsFloat = false
        scratchPcm16 = AudioProcessor.EMPTY_BUFFER
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        if (!enabled || !delegate.isActive) {
            val output = replaceOutputBuffer(remaining)
            output.put(inputBuffer)
            output.flip()
            return
        }

        if (!inputIsFloat) {
            delegate.queueInput(inputBuffer)
            drainDelegatePcm16Direct()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        val sampleCount = remaining / 4
        val pcm16Bytes = sampleCount * 2
        if (scratchPcm16.capacity() < pcm16Bytes) {
            scratchPcm16 = ByteBuffer.allocateDirect(pcm16Bytes).order(ByteOrder.nativeOrder())
        } else {
            scratchPcm16.clear()
        }
        for (i in 0 until sampleCount) {
            val s = (inputBuffer.float.coerceIn(-1f, 1f) * PCM16_MAX).roundToInt().coerceIn(-32_768, 32_767).toShort()
            scratchPcm16.putShort(s)
        }
        while (inputBuffer.hasRemaining()) {
            inputBuffer.get()
        }
        scratchPcm16.flip()

        delegate.queueInput(scratchPcm16)
        drainDelegateAsFloat()
    }

    override fun onQueueEndOfStream() {
        if (!enabled || !delegate.isActive) return
        delegate.queueEndOfStream()
        if (inputIsFloat) {
            drainDelegateAsFloat()
        } else {
            drainDelegatePcm16Direct()
        }
    }

    private fun drainDelegatePcm16Direct() {
        val delegateOut = delegate.output
        val outBytes = delegateOut.remaining()
        if (outBytes == 0) {
            replaceOutputBuffer(0).flip()
            return
        }
        val output = replaceOutputBuffer(outBytes)
        output.put(delegateOut)
        output.flip()
    }

    private fun drainDelegateAsFloat() {
        val delegateOut = delegate.output
        val outBytes = delegateOut.remaining()
        if (outBytes == 0) {
            replaceOutputBuffer(0).flip()
            return
        }
        delegateOut.order(ByteOrder.nativeOrder())
        val outSamples = outBytes / 2
        val output = replaceOutputBuffer(outSamples * 4)
        output.order(ByteOrder.nativeOrder())
        for (i in 0 until outSamples) {
            output.putFloat((delegateOut.short.toDouble() / PCM16_SCALE).toFloat())
        }
        while (delegateOut.hasRemaining()) {
            delegateOut.get()
        }
        output.flip()
    }

    private companion object {
        private const val PCM16_SCALE = 32_768.0
        private const val PCM16_MAX = 32_767.0f
    }
}
