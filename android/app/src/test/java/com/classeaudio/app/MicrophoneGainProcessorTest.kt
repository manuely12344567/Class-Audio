package com.classeaudio.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class MicrophoneGainProcessorTest {
    @Test
    fun appliesRuntimeGainToNativeFloatS16Samples() {
        val processor = MicrophoneGainProcessor(initialPercent = 100)
        val buffer = floatFrame(1000f, -2000f)

        processor.setGainPercent(125)
        processor.processAudio(numBands = 1, numFrames = 2, buffer = buffer)

        assertEquals(1250f, buffer.getFloat(0), 0.01f)
        assertEquals(-2500f, buffer.getFloat(4), 0.01f)
    }

    @Test
    fun clipsAtTheWebRtcFloatS16Limits() {
        val processor = MicrophoneGainProcessor(initialPercent = 150)
        val buffer = floatFrame(30000f, -30000f)

        processor.processAudio(numBands = 1, numFrames = 2, buffer = buffer)

        assertEquals(32767f, buffer.getFloat(0), 0f)
        assertEquals(-32768f, buffer.getFloat(4), 0f)
    }

    @Test
    fun processesEveryBandForEveryFrame() {
        val processor = MicrophoneGainProcessor(initialPercent = 125)
        val buffer = floatFrame(100f, -200f, 300f, -400f, 500f, -600f)

        processor.processAudio(numBands = 3, numFrames = 2, buffer = buffer)

        assertEquals(125f, buffer.getFloat(0), 0.01f)
        assertEquals(-250f, buffer.getFloat(4), 0.01f)
        assertEquals(375f, buffer.getFloat(8), 0.01f)
        assertEquals(-500f, buffer.getFloat(12), 0.01f)
        assertEquals(625f, buffer.getFloat(16), 0.01f)
        assertEquals(-750f, buffer.getFloat(20), 0.01f)
    }

    @Test
    fun leavesSamplesUnchangedAtUnityGain() {
        val processor = MicrophoneGainProcessor(initialPercent = 100)
        val buffer = floatFrame(1234.5f, -567.25f)

        processor.processAudio(numBands = 1, numFrames = 2, buffer = buffer)

        assertEquals(1234.5f, buffer.getFloat(0), 0f)
        assertEquals(-567.25f, buffer.getFloat(4), 0f)
    }

    @Test
    fun safelyLeavesAnUnexpectedlyShortBufferUntouched() {
        val processor = MicrophoneGainProcessor(initialPercent = 150)
        val buffer = floatFrame(900f)

        processor.processAudio(numBands = 3, numFrames = 2, buffer = buffer)

        assertEquals(900f, buffer.getFloat(0), 0f)
    }

    private fun floatFrame(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                samples.forEachIndexed { index, sample -> putFloat(index * Float.SIZE_BYTES, sample) }
            }
}
