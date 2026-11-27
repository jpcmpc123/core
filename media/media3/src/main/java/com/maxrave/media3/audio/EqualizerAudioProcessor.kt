package com.maxrave.media3.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.data.player.EQUALIZER_ISO_BANDS_HZ
import com.maxrave.domain.data.player.EqualizerConfig
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The ten band centres, in Hz (exposed for compatibility with callers/tests referencing this module).
 */
val EQUALIZER_BANDS_HZ: IntArray = EQUALIZER_ISO_BANDS_HZ

/**
 * Media3 [AudioProcessor] applying pure linear ReplayGain normalization and the studio equalizer
 * DSP chain ([EqualizerKernel]):
 * - supports both `C.ENCODING_PCM_16BIT` and `C.ENCODING_PCM_FLOAT` inputs
 * - when [promoteToFloat] is enabled, promotes PCM 16-bit inputs cleanly to `C.ENCODING_PCM_FLOAT`
 *   so downstream processors and Android's float AudioTrack preserve full 24-bit+ headroom with
 *   zero intermediate 16-bit quantization
 * - per-buffer reference-checked [EqualizerConfig] supplier and per-player ReplayGain supplier
 *   for instant glitch-free updates and independent crossfade loudness
 */
@UnstableApi
class EqualizerAudioProcessor(
    private val configSupplier: () -> EqualizerConfig,
    private val normalizationGainDbSupplier: () -> Float = { 0f },
    private val promoteToFloat: Boolean = false,
) : BaseAudioProcessor() {
    private var inputEncoding = C.ENCODING_INVALID
    private var outputEncoding = C.ENCODING_INVALID
    private var sampleRate = 0
    private var channelCount = 0

    private val kernel = EqualizerKernel()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        inputEncoding = inputAudioFormat.encoding
        outputEncoding = if (promoteToFloat) C.ENCODING_PCM_FLOAT else inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        kernel.configure(sampleRate = sampleRate, channelCount = channelCount)
        return AudioProcessor.AudioFormat(sampleRate, channelCount, outputEncoding)
    }

    override fun onFlush() {
        kernel.flush()
    }

    override fun onReset() {
        kernel.reset()
        inputEncoding = C.ENCODING_INVALID
        outputEncoding = C.ENCODING_INVALID
        sampleRate = 0
        channelCount = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        kernel.syncNormalizationGainDb(normalizationGainDbSupplier())
        kernel.syncConfig(configSupplier())

        val wideningToFloat = inputEncoding == C.ENCODING_PCM_16BIT && outputEncoding == C.ENCODING_PCM_FLOAT
        val outputBytes = if (wideningToFloat) remaining * 2 else remaining
        val output = replaceOutputBuffer(outputBytes)

        if (kernel.canDirectBypass() || sampleRate <= 0 || channelCount <= 0) {
            if (wideningToFloat) {
                inputBuffer.order(ByteOrder.nativeOrder())
                output.order(ByteOrder.nativeOrder())
                while (inputBuffer.remaining() >= 2) {
                    output.putFloat((inputBuffer.short.toDouble() / PCM16_SCALE).toFloat())
                }
                while (inputBuffer.hasRemaining()) {
                    inputBuffer.get()
                }
            } else {
                output.put(inputBuffer)
            }
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        when {
            inputEncoding == C.ENCODING_PCM_16BIT && outputEncoding == C.ENCODING_PCM_FLOAT -> {
                processPcm16ToFloatBuffer(inputBuffer, output)
            }
            inputEncoding == C.ENCODING_PCM_16BIT -> {
                processPcm16Buffer(inputBuffer, output)
            }
            inputEncoding == C.ENCODING_PCM_FLOAT -> {
                processPcmFloatBuffer(inputBuffer, output)
            }
            else -> output.put(inputBuffer)
        }

        kernel.finishBufferPass()
        output.flip()
    }

    private fun processPcm16ToFloatBuffer(
        inputBuffer: ByteBuffer,
        output: ByteBuffer,
    ) {
        if (channelCount == 2) {
            while (inputBuffer.remaining() >= 4) {
                val dryL = inputBuffer.short.toDouble() / PCM16_SCALE
                val dryR = inputBuffer.short.toDouble() / PCM16_SCALE
                kernel.stepRampsAndProcessStereo(dryL, dryR)
                output.putFloat(kernel.outFrameL.toFloat())
                output.putFloat(kernel.outFrameR.toFloat())
            }
        } else {
            var channel = 0
            while (inputBuffer.remaining() >= 2) {
                val dry = inputBuffer.short.toDouble() / PCM16_SCALE
                val processed = kernel.stepRampsAndProcessSingleChannel(dry, channel)
                output.putFloat(processed.toFloat())
                channel++
                if (channel == channelCount) channel = 0
            }
        }
        while (inputBuffer.hasRemaining()) {
            inputBuffer.get()
        }
    }

    private fun processPcm16Buffer(
        inputBuffer: ByteBuffer,
        output: ByteBuffer,
    ) {
        if (channelCount == 2) {
            while (inputBuffer.remaining() >= 4) {
                val dryL = inputBuffer.short.toDouble() / PCM16_SCALE
                val dryR = inputBuffer.short.toDouble() / PCM16_SCALE
                kernel.stepRampsAndProcessStereo(dryL, dryR)
                output.putShort(kernel.quantizeSampleToPcm16(kernel.outFrameL))
                output.putShort(kernel.quantizeSampleToPcm16(kernel.outFrameR))
            }
        } else {
            var channel = 0
            while (inputBuffer.remaining() >= 2) {
                val dry = inputBuffer.short.toDouble() / PCM16_SCALE
                val processed = kernel.stepRampsAndProcessSingleChannel(dry, channel)
                output.putShort(kernel.quantizeSampleToPcm16(processed))
                channel++
                if (channel == channelCount) channel = 0
            }
        }
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }
    }

    private fun processPcmFloatBuffer(
        inputBuffer: ByteBuffer,
        output: ByteBuffer,
    ) {
        if (channelCount == 2) {
            while (inputBuffer.remaining() >= 8) {
                val dryL = inputBuffer.float.toDouble()
                val dryR = inputBuffer.float.toDouble()
                kernel.stepRampsAndProcessStereo(dryL, dryR)
                output.putFloat(kernel.outFrameL.toFloat())
                output.putFloat(kernel.outFrameR.toFloat())
            }
        } else {
            var channel = 0
            while (inputBuffer.remaining() >= 4) {
                val dry = inputBuffer.float.toDouble()
                val processed = kernel.stepRampsAndProcessSingleChannel(dry, channel)
                output.putFloat(processed.toFloat())
                channel++
                if (channel == channelCount) channel = 0
            }
        }
        while (inputBuffer.hasRemaining()) {
            output.put(inputBuffer.get())
        }
    }

    private companion object {
        private const val PCM16_SCALE = 32_768.0
    }
}

