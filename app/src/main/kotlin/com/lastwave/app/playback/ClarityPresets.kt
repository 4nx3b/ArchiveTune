package com.lastwave.app.playback

/**
 * Output preset descriptor for the native Studio Master Clarity chain.
 *
 * @param index selector passed to the native preset entry point (0..3).
 * @param key stable identifier for persistence.
 * @param displayName short human-readable label.
 * @param description documents how the preset deviates from [REFERENCE].
 * @param trimsDb per-stage trims in dB, stage order 0..7: sub-bass high-pass,
 * bass foundation (72 Hz), low-mid separation (280 Hz), boxiness control
 * (750 Hz), presence detail (3.4 kHz), air shelf (10.5 kHz), mono-bass
 * high-pass (side channel), exciter high-pass.
 */
data class ClarityPreset(
    val index: Int,
    val key: String,
    val displayName: String,
    val description: String,
    val trimsDb: FloatArray,
)

/**
 * Clarity output presets as data. Trim values mirror the native preset
 * tables in DspProcessor.cpp; keep both sides in sync when changing them.
 */
object ClarityPresets {
    const val TRIM_COUNT = 8

    const val REFERENCE_INDEX = 0
    const val SPEAKER_INDEX = 1
    const val HEADPHONE_INDEX = 2
    const val DAC_INDEX = 3

    /** Today's shipping curve: all trims 0 dB, bit-identical output. */
    val REFERENCE = ClarityPreset(
        index = REFERENCE_INDEX,
        key = "reference",
        displayName = "Reference",
        description = "Unmodified shipping curve; every stage trim is 0 dB.",
        trimsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    )

    /**
     * Small drivers distort on sub-bass lift and exaggerate the top octave:
     * reduced 72 Hz foundation (-1.5 dB) and slightly gentler air (-0.5 dB).
     */
    val SPEAKER = ClarityPreset(
        index = SPEAKER_INDEX,
        key = "speaker",
        displayName = "Speaker",
        description = "Reduced sub-bass lift (-1.5 dB) and gentler air (-0.5 dB) for small drivers.",
        trimsDb = floatArrayOf(0f, -1.5f, 0f, 0f, 0f, -0.5f, 0f, 0f),
    )

    /**
     * Close-coupled drivers exaggerate bass and upper-mid presence:
     * lighter 72 Hz foundation (-0.5 dB) and softer presence (-1.0 dB).
     */
    val HEADPHONE = ClarityPreset(
        index = HEADPHONE_INDEX,
        key = "headphone",
        displayName = "Headphone",
        description = "Lighter bass (-0.5 dB) and softer presence (-1.0 dB) for close-coupled drivers.",
        trimsDb = floatArrayOf(0f, -0.5f, 0f, 0f, -1.0f, 0f, 0f, 0f),
    )

    /**
     * Revealing downstream chains expose top-octave harshness:
     * gentler air shelf (-1.5 dB) and calmer exciter (-1.0 dB).
     */
    val DAC = ClarityPreset(
        index = DAC_INDEX,
        key = "dac",
        displayName = "DAC",
        description = "Gentler air (-1.5 dB shelf, -1.0 dB exciter) for revealing chains.",
        trimsDb = floatArrayOf(0f, 0f, 0f, 0f, 0f, -1.5f, 0f, -1.0f),
    )

    val ALL: List<ClarityPreset> = listOf(REFERENCE, SPEAKER, HEADPHONE, DAC)

    fun fromIndex(index: Int): ClarityPreset =
        ALL.firstOrNull { it.index == index } ?: REFERENCE
}
