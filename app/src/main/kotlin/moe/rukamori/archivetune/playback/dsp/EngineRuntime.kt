package moe.rukamori.archivetune.playback.dsp

import tf.monochrome.android.audio.usb.BypassDiagnostics

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
    var rendererFloatDecode: Boolean = false
}
