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
 * Media3 [AudioProcessor] applying the studio equalizer DSP chain ([EqualizerKernel]):
 * - supports both `C.ENCODING_PCM_16BIT` and `C.ENCODING_PCM_FLOAT`
 * - per-buffer reference-checked [EqualizerConfig] supplier for instant glitch-free updates
 * - 10 ms anti-click ramp, Subsonic HPF, AutoEq layer, 10-band EQ, Bass/Air shelves,
 *   Fletcher-Munson loudness, Mid/Side Stereo Width with LR2 Bass Mono, True-Peak soft-knee
 *   limiter, and TPDF dither on PCM 16-bit output
 */
@UnstableApi
class EqualizerAudioProcessor(
    private val configSupplier: () -> EqualizerConfig,
) : BaseAudioProcessor() {
    private var encoding = C.ENCODING_INVALID
    private var sampleRate = 0
    private var channelCount = 0

    private val kernel = EqualizerKernel()

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT
        ) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        encoding = inputAudioFormat.encoding
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        kernel.configure(sampleRate = sampleRate, channelCount = channelCount)
        return inputAudioFormat
    }

    override fun onFlush() {
        kernel.flush()
    }

    override fun onReset() {
        kernel.reset()
        encoding = C.ENCODING_INVALID
        sampleRate = 0
        channelCount = 0
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val output = replaceOutputBuffer(remaining)
        kernel.syncConfig(configSupplier())

        if (kernel.canDirectBypass() || sampleRate <= 0 || channelCount <= 0) {
            output.put(inputBuffer)
            output.flip()
            return
        }

        inputBuffer.order(ByteOrder.nativeOrder())
        output.order(ByteOrder.nativeOrder())

        when (encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16Buffer(inputBuffer, output)
            C.ENCODING_PCM_FLOAT -> processPcmFloatBuffer(inputBuffer, output)
            else -> output.put(inputBuffer)
        }

        kernel.finishBufferPass()
        output.flip()
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
