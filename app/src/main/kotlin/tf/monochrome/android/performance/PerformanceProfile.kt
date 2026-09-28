/*
 * PerformanceProfile shim for the ported Tryptify SpectrumAnalyzerTap.
 * Tryptify tiers the analyzer's frame cadence by device class; the ArchiveTune
 * port always runs the HIGH tier (60 fps spectrum) which is the behavior a
 * flagship-class phone saw upstream.
 */

package tf.monochrome.android.performance

data class PerformanceProfile(
    val spectrumFps: Int = 60,
) {
    companion object {
        val HIGH = PerformanceProfile(spectrumFps = 60)
    }
}
