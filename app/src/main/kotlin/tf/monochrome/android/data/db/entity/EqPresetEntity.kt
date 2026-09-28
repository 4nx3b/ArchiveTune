// EqPresetEntity — extracted from Tryptify's data/db/entity/Entities.kt
// (https://github.com/tryptz/Tryptify) during the port into ArchiveTune.
// Storage note: in ArchiveTune this entity is persisted by the file-backed
// DAO (FileBackedEqPresetDao) rather than Room, so the Room annotations are
// inert here; the field layout is kept identical to Tryptify's.

package tf.monochrome.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "eq_presets")
data class EqPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val bandsJson: String = "[]",  // Serialized List<EqBand>
    // Right-channel bands for per-ear calibration; null = mono preset (the
    // left list drives both ears). Nullable so every pre-v11 row is a valid
    // mono preset with no data rewrite.
    val bandsRJson: String? = null,
    val preamp: Float = 0f,
    val targetId: String = "",
    val targetName: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val eqType: Int = 0  // 0 = AutoEQ, 1 = Parametric
)
