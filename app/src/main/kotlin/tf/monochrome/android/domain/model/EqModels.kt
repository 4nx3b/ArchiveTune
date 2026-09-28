/*
 * EQ / AutoEQ domain models — extracted from Tryptify's domain/model/Models.kt
 * (https://github.com/tryptz/Tryptify) during the port into ArchiveTune so the
 * ported AutoEQ engine / processors / repositories keep their original types.
 */

package tf.monochrome.android.domain.model

import kotlinx.serialization.Serializable

enum class FilterType {
    PEAKING, LOWSHELF, HIGHSHELF
}

@Serializable
data class FrequencyPoint(
    val freq: Float,
    val gain: Float
)

@Serializable
data class EqBand(
    val id: Int,
    val type: FilterType = FilterType.PEAKING,
    val freq: Float,
    val gain: Float,
    val q: Float = 1.0f,
    val enabled: Boolean = true
)

@Serializable
data class EqPreset(
    val id: String,
    val name: String,
    val description: String = "",
    val bands: List<EqBand> = emptyList(),
    // Right-ear bands when the preset was saved in 2-channel mode; null = mono
    // (bands drives both ears).
    val bandsR: List<EqBand>? = null,
    val preamp: Float = 0f,
    val targetId: String = "",
    val targetName: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    // Set by EqRepository.toDomain when the stored bandsJson fails to decode.
    // Loading a corrupted preset would silently flatten the EQ; callers should
    // refuse to load it and surface the state instead.
    val isCorrupted: Boolean = false
)

@Serializable
data class EqTarget(
    val id: String,
    val label: String,
    val data: List<FrequencyPoint> = emptyList(),
    val filename: String = ""
)

@Serializable
data class Headphone(
    val id: String,
    val name: String,
    val type: String = "over-ear", // "over-ear", "in-ear", "earbud"
    val data: List<FrequencyPoint> = emptyList(),
    val measurements: List<AutoEqMeasurement> = emptyList()
)

data class AutoEqEntry(
    val name: String,
    val type: String,
    val measurements: List<AutoEqMeasurement> = emptyList()
)

@Serializable
data class AutoEqMeasurement(
    val source: String,
    val target: String,
    val path: String,
    val fileName: String,
    val rig: MeasurementRig = MeasurementRig.UNKNOWN,
    // Origin host for squig.link sources (e.g. "https://precog.squig.link");
    // empty for AutoEq sources where path is the GitHub repo subpath.
    val host: String = ""
)

/**
 * Acoustic measurement rig used to capture a headphone's frequency response.
 * Bucket label is what the UI shows in its filter chip; ordinal controls sort
 * order so industry-grade rigs (B&K 5128, GRAS) rise above community clones.
 */
@Serializable
enum class MeasurementRig(val label: String) {
    // Pinned first by ordinal so the rig filter chip row leads with the
    // user's own measurements before any remote source.
    UPLOADED("Uploaded"),
    BK_5128("B&K 5128"),
    BK_4620("B&K 4620"),
    HMS_II_3("HMS II.3"),
    GRAS_43AG_7("GRAS 43AG-7"),
    GRAS_43AC_10("GRAS 43AC-10"),
    GRAS_45CA_10("GRAS 45CA-10"),
    GRAS_RA0045("GRAS RA0045"),
    IEC_711_CLONE("IEC 711 clone"),
    MINIDSP_EARS("MiniDSP EARS"),
    UNKNOWN("Unknown")
}
