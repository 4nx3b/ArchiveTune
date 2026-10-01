@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback.dsp

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.exoplayer.audio.SilenceSkippingAudioProcessor
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DspTailAudioProcessorChainTest {

    private class PassthroughProcessor : BaseAudioProcessor() {
        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat = inputAudioFormat

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            replaceOutputBuffer(remaining).put(inputBuffer).flip()
        }
    }

    @Test
    fun processorsOrder_preProcessorsThenSilenceThenSonicThenTail() {
        val silence = SilenceSkippingAudioProcessor()
        val sonic = SonicAudioProcessor()
        val haptics = PassthroughProcessor()
        val pan = PassthroughProcessor()
        val transition = PassthroughProcessor()
        val dspTail = PassthroughProcessor()

        val chain =
            DspTailAudioProcessorChain(
                silenceSkippingAudioProcessor = silence,
                sonicAudioProcessor = sonic,
                preProcessors = arrayOf(haptics, pan, transition),
                tailProcessor = dspTail,
            )

        val processors = chain.getAudioProcessors()
        assertEquals(6, processors.size)
        assertSame(haptics, processors[0])
        assertSame(pan, processors[1])
        assertSame(transition, processors[2])
        assertSame(silence, processors[3])
        assertSame(sonic, processors[4])
        // The encoding-flipping DSP tail must be the LAST processor in the
        // chain: anything scheduled after it would have to accept
        // ENCODING_PCM_FLOAT, and SilenceSkippingAudioProcessor never does.
        assertSame(dspTail, processors[5])
    }

    @Test
    fun skipSilenceWiresToTheInChainTrimmer() {
        val silence = SilenceSkippingAudioProcessor()
        val chain =
            DspTailAudioProcessorChain(
                silenceSkippingAudioProcessor = silence,
                sonicAudioProcessor = SonicAudioProcessor(),
                preProcessors = emptyArray(),
                tailProcessor = PassthroughProcessor(),
            )

        assertEquals(true, chain.applySkipSilenceEnabled(true))
        silence.configure(AudioProcessor.AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        assertTrue(silence.isActive())
    }

    @Test
    fun playbackParametersPassThroughUnchanged() {
        val chain =
            DspTailAudioProcessorChain(
                silenceSkippingAudioProcessor = SilenceSkippingAudioProcessor(),
                sonicAudioProcessor = SonicAudioProcessor(),
                preProcessors = emptyArray(),
                tailProcessor = PassthroughProcessor(),
            )

        val params = PlaybackParameters(1.25f)
        assertSame(params, chain.applyPlaybackParameters(params))
    }
}
