package moe.rukamori.archivetune.playback.dsp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import tf.monochrome.android.audio.usb.BypassDiagnostics

/**
 * Cross-thread truth for the live audio chain readouts (live chain pill in the
 * track-info details tab).
 *
 * The @Volatile fields are written/read on the audio renderer thread; the
 * snapshot-state mirrors are written right after every @Volatile write so any
 * Compose consumer observing them recomposes on the SAME frame instead of
 * waiting for the old 1s poll. Snapshot writes from background threads are
 * safe: the global snapshot schedules the recomposition on the main thread.
 */
object EngineRuntime {
    @Volatile
    var activeEngine: AudioEngineRouterProcessor.Engine = AudioEngineRouterProcessor.Engine.NONE

    @Volatile
    var outputFloat: Boolean = false

    @Volatile
    var usbExclusiveActive: Boolean = false

    @Volatile
    var tryptifyUsbStream: BypassDiagnostics? = null

    @Volatile
    var lastwaveUsbRateHz: Int = 0

    @Volatile
    var lastwaveUsbBitsPerSample: Int = 0

    @Volatile
    var tryptifyUsbPinActive: Boolean = false

    @Volatile
    var lastwaveMixerBitPerfectActive: Boolean = false

    @Volatile
    var bitPerfectSinkRouteActive: Boolean = false

    @Volatile
    var sinkDecodedEncoding: Int = C.ENCODING_PCM_16BIT

    // ---- Snapshot-state mirrors (read by Compose consumers) ----

    /** The engine the router actually engaged for the live stream. */
    var activeEngineState by mutableStateOf(AudioEngineRouterProcessor.Engine.NONE)
        private set

    /** The engine the user selected (preference), before the router latches it. */
    var wantedEngineState by mutableStateOf(AudioEngineRouterProcessor.Engine.NONE)
        private set

    /** True when the Tryptify native library is loaded and usable. */
    var tryptifyAvailableState by mutableStateOf(false)
        private set

    /** True when the LastWave native engine handle is alive. */
    var lastwaveAvailableState by mutableStateOf(false)
        private set

    /** Whether the router declares float output on the wire. */
    var outputFloatState by mutableStateOf(false)
        private set

    /** Bumped whenever any mirrored fact changes - a cheap full-invalidations. */
    var revision by mutableStateOf(0)
        private set

    fun revisionBump() {
        revision++
    }

    fun publishActiveEngine(engine: AudioEngineRouterProcessor.Engine) {
        activeEngine = engine
        activeEngineState = engine
        revision++
    }

    fun publishWantedEngine(engine: AudioEngineRouterProcessor.Engine) {
        wantedEngineState = engine
        revision++
    }

    fun publishEngineAvailability(tryptify: Boolean, lastwave: Boolean) {
        tryptifyAvailableState = tryptify
        lastwaveAvailableState = lastwave
        revision++
    }

    fun publishOutputFloat(float: Boolean) {
        outputFloat = float
        outputFloatState = float
        revision++
    }

    fun publishUsbExclusive(active: Boolean) {
        usbExclusiveActive = active
        revision++
    }

    fun publishTryptifyUsbPin(active: Boolean) {
        tryptifyUsbPinActive = active
        revision++
    }

    fun publishLastwaveMixerBitPerfect(active: Boolean) {
        lastwaveMixerBitPerfectActive = active
        revision++
    }

    fun publishSinkRoute(floatRoute: Boolean, decodedEncoding: Int) {
        bitPerfectSinkRouteActive = floatRoute
        sinkDecodedEncoding = decodedEncoding
        revision++
    }

    fun publishUsbWire(rateHz: Int, bits: Int) {
        lastwaveUsbRateHz = rateHz
        lastwaveUsbBitsPerSample = bits
        revision++
    }
}
