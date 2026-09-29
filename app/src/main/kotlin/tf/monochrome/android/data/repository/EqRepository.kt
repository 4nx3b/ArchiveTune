package tf.monochrome.android.data.repository

import android.util.Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.eq.FrequencyTargets
import tf.monochrome.android.data.db.dao.EqPresetDao
import tf.monochrome.android.data.db.entity.EqPresetEntity
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.EqPreset
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EqRepository @Inject constructor(
    private val eqPresetDao: EqPresetDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getAllPresets(): Flow<List<EqPreset>> = eqPresetDao.getAllPresets().map { dbPresets ->
        val customPresets = dbPresets.map { it.toDomain() }
        val allPresets = getDefaultPresets() + customPresets
        allPresets.sortedWith(compareBy({ !it.isCustom }, { -it.updatedAt }))
    }

    fun getCustomPresets(): Flow<List<EqPreset>> = eqPresetDao.getCustomPresets().map { dbPresets ->
        dbPresets.map { it.toDomain() }
            .sortedByDescending { it.updatedAt }
    }

    fun getBuiltInPresets(): Flow<List<EqPreset>> =
        kotlinx.coroutines.flow.flowOf(
            getDefaultPresets().sortedBy { it.name }
        )

    suspend fun getPresetById(presetId: String): EqPreset? {
        val dbPreset = eqPresetDao.getPresetById(presetId)
        if (dbPreset != null) {
            return dbPreset.toDomain()
        }

        return getDefaultPresets().find { it.id == presetId }
    }

    fun getPresetByIdFlow(presetId: String): Flow<EqPreset?> = eqPresetDao.getPresetByIdFlow(presetId).map { dbPreset ->
        dbPreset?.toDomain() ?: getDefaultPresets().find { it.id == presetId }
    }

    suspend fun savePreset(preset: EqPreset) {
        val entity = preset.toEntity()
        eqPresetDao.insertPreset(entity)

    }

    suspend fun updatePreset(preset: EqPreset) {
        val entity = preset.toEntity()
        eqPresetDao.updatePreset(entity)
    }

    suspend fun deletePreset(presetId: String) {
        eqPresetDao.deletePreset(presetId)

    }

    fun searchPresets(query: String): Flow<List<EqPreset>> = eqPresetDao.searchPresets(query).map { dbResults ->
        val customPresets = dbResults.map { it.toDomain() }
        val builtIn = getDefaultPresets().filter { preset ->
            preset.name.contains(query, ignoreCase = true) ||
                    preset.description.contains(query, ignoreCase = true)
        }
        (customPresets + builtIn).sortedWith(compareBy({ !it.isCustom }, { -it.updatedAt }))
    }

    fun getPresetsByTarget(targetId: String): Flow<List<EqPreset>> = eqPresetDao.getPresetsByTarget(targetId).map { dbPresets ->
        val customPresets = dbPresets.map { it.toDomain() }
        val builtIn = getDefaultPresets().filter { it.targetId == targetId }
        (builtIn + customPresets).sortedWith(compareBy({ !it.isCustom }, { -it.updatedAt }))
    }

    fun getCustomPresetCount(): Flow<Int> = eqPresetDao.getCustomPresetCount()

    suspend fun createAutoEqPreset(
        name: String,
        bands: List<EqBand>,
        preamp: Float = 0f,
        targetId: String = "harman_oe_2018",
        headphoneName: String = ""
    ): EqPreset {
        val preset = EqPreset(
            id = "custom_autoeq_${System.currentTimeMillis()}",
            name = name,
            description = "AutoEQ calculated for $headphoneName",
            bands = bands,
            preamp = preamp,
            targetId = targetId,
            targetName = FrequencyTargets.getTargetById(targetId)?.label ?: "Unknown",
            isCustom = true,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        savePreset(preset)
        return preset
    }

    private fun getDefaultPresets(): List<EqPreset> {
        return emptyList()

    }

    private fun EqPresetEntity.toDomain(): EqPreset {
        var corrupted = false
        val bands = try {
            json.decodeFromString<List<EqBand>>(bandsJson)
        } catch (e: Exception) {
            Log.e(
                "EqRepository",
                "Failed to decode bands for preset '$id' (${e.javaClass.simpleName}: ${e.message})"
            )
            corrupted = true
            emptyList()
        }

        val bandsR = bandsRJson?.let { rJson ->
            try {
                json.decodeFromString<List<EqBand>>(rJson)
            } catch (e: Exception) {
                Log.e(
                    "EqRepository",
                    "Failed to decode R bands for preset '$id' (${e.javaClass.simpleName}: ${e.message})"
                )
                corrupted = true
                null
            }
        }
        return EqPreset(
            id = id,
            name = name,
            description = description,
            bands = bands,
            bandsR = bandsR,
            preamp = preamp,
            targetId = targetId,
            targetName = targetName,
            isCustom = isCustom,
            createdAt = createdAt,
            updatedAt = updatedAt,
            isCorrupted = corrupted
        )
    }

    private fun EqPreset.toEntity(): EqPresetEntity {
        return EqPresetEntity(
            id = id,
            name = name,
            description = description,
            bandsJson = try {
                json.encodeToString(bands)
            } catch (e: Exception) {
                "[]"
            },
            bandsRJson = bandsR?.let {
                try {
                    json.encodeToString(it)
                } catch (e: Exception) {
                    null
                }
            },
            preamp = preamp,
            targetId = targetId,
            targetName = targetName,
            isCustom = isCustom,
            createdAt = createdAt,
            updatedAt = System.currentTimeMillis(),
            eqType = 0
        )
    }
}
