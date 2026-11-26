package com.classeaudio.app

import io.livekit.android.audio.AudioProcessorInterface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small, allocation-free capture gain stage for LiveKit Android 2.29.0.
 *
 * The pinned WebRTC-SDK 144.7559.14 callback exposes a direct native-order Float32
 * buffer in WebRTC's FloatS16 scale (approximately -32768..32768), not PCM16 and
 * not normalized [-1, 1]. The bridge passes channel 0 as a non-interleaved
 * full-band array containing `numFrames * numBands` float samples. If the buffer shape is unexpected,
 * the processor leaves the frame untouched rather than risking an
 * out-of-bounds read on WebRTC's real-time audio thread.
 */
internal class MicrophoneGainProcessor(initialPercent: Int = 100) : AudioProcessorInterface {
    private val gainPercent = AtomicInteger(initialPercent.coerceIn(MIN_GAIN_PERCENT, MAX_GAIN_PERCENT))

    fun setGainPercent(percent: Int) {
        gainPercent.set(percent.coerceIn(MIN_GAIN_PERCENT, MAX_GAIN_PERCENT))
    }

    override fun isEnabled(): Boolean = gainPercent.get() != 100

    override fun getName(): String = "classeaudio_microphone_gain"

    override fun initializeAudioProcessing(sampleRateHz: Int, numChannels: Int) = Unit

    override fun resetAudioProcessing(newRate: Int) = Unit

    override fun processAudio(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        val percent = gainPercent.get()
        if (percent == 100 || numBands <= 0 || numFrames <= 0 || !buffer.isDirect) return

        val samples = numFrames.toLong() * numBands.toLong()
        val requiredBytes = samples * BYTES_PER_FLOAT
        if (requiredBytes > buffer.remaining().toLong()) return

        buffer.order(ByteOrder.nativeOrder())
        val gain = percent / 100f
        var byteOffset = buffer.position()
        var sampleIndex = 0L
        while (sampleIndex < samples) {
            val sample = buffer.getFloat(byteOffset) * gain
            val clipped = when {
                sample > MAX_FLOAT_S16 -> MAX_FLOAT_S16
                sample < MIN_FLOAT_S16 -> MIN_FLOAT_S16
                else -> sample
            }
            buffer.putFloat(byteOffset, clipped)
            byteOffset += BYTES_PER_FLOAT
            sampleIndex++
        }
    }

    private companion object {
        const val MIN_GAIN_PERCENT = 50
        const val MAX_GAIN_PERCENT = 150
        const val BYTES_PER_FLOAT = 4
        const val MIN_FLOAT_S16 = -32768f
        const val MAX_FLOAT_S16 = 32767f
    }
}
