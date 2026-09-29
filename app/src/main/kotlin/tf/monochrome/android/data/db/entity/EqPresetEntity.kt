

package tf.monochrome.android.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "eq_presets")
data class EqPresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val bandsJson: String = "[]",

    val bandsRJson: String? = null,
    val preamp: Float = 0f,
    val targetId: String = "",
    val targetName: String = "",
    val isCustom: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val eqType: Int = 0
)
