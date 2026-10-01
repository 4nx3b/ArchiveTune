package tf.monochrome.android.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import tf.monochrome.android.data.db.entity.EqPresetEntity

@Dao
interface EqPresetDao {
    @Query("SELECT * FROM eq_presets WHERE eqType = 0 ORDER BY isCustom DESC, updatedAt DESC")
    fun getAllPresets(): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE isCustom = 1 AND eqType = 0 ORDER BY updatedAt DESC")
    fun getCustomPresets(): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE isCustom = 0 AND eqType = 0 ORDER BY name ASC")
    fun getBuiltInPresets(): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE id = :presetId")
    suspend fun getPresetById(presetId: String): EqPresetEntity?

    @Query("SELECT * FROM eq_presets WHERE id = :presetId")
    fun getPresetByIdFlow(presetId: String): Flow<EqPresetEntity?>

    @Query("SELECT * FROM eq_presets ORDER BY updatedAt DESC")
    suspend fun getAllPresetsSnapshot(): List<EqPresetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPreset(preset: EqPresetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPresets(presets: List<EqPresetEntity>)

    @Update
    suspend fun updatePreset(preset: EqPresetEntity)

    @Query("DELETE FROM eq_presets WHERE id = :presetId")
    suspend fun deletePreset(presetId: String)

    @Query("DELETE FROM eq_presets WHERE isCustom = 1 AND eqType = 0")
    suspend fun deleteAllCustomPresets()

    @Query("SELECT EXISTS(SELECT 1 FROM eq_presets WHERE id = :presetId)")
    suspend fun presetExists(presetId: String): Boolean

    @Query("SELECT COUNT(*) FROM eq_presets WHERE eqType = 0")
    fun getPresetCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM eq_presets WHERE isCustom = 1 AND eqType = 0")
    fun getCustomPresetCount(): Flow<Int>

    @Query("SELECT * FROM eq_presets WHERE eqType = 0 AND name LIKE '%' || :searchQuery || '%' ORDER BY isCustom DESC, updatedAt DESC")
    fun searchPresets(searchQuery: String): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE eqType = 0 AND targetId = :targetId ORDER BY isCustom DESC, updatedAt DESC")
    fun getPresetsByTarget(targetId: String): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE eqType = 1 ORDER BY isCustom DESC, updatedAt DESC")
    fun getAllParametricPresets(): Flow<List<EqPresetEntity>>

    @Query("SELECT * FROM eq_presets WHERE isCustom = 1 AND eqType = 1 ORDER BY updatedAt DESC")
    fun getCustomParametricPresets(): Flow<List<EqPresetEntity>>

    @Query("SELECT COUNT(*) FROM eq_presets WHERE isCustom = 1 AND eqType = 1")
    fun getCustomParametricPresetCount(): Flow<Int>

    @Query("SELECT * FROM eq_presets WHERE eqType = 1 AND name LIKE '%' || :searchQuery || '%' ORDER BY isCustom DESC, updatedAt DESC")
    fun searchParametricPresets(searchQuery: String): Flow<List<EqPresetEntity>>
}
