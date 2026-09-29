

package tf.monochrome.android.data.db.dao

import android.content.Context
import android.util.Log
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tf.monochrome.android.data.db.entity.EqPresetEntity
import tf.monochrome.android.data.db.entity.MixPresetEntity

private class JsonStore<T>(
    private val file: File,

    private val serializer: (List<T>) -> String,
    private val deserializer: (String) -> List<T>,
) {
    private val state = MutableStateFlow(load())
    val flow: Flow<List<T>> = state

    private fun load(): List<T> =
        runCatching {
            if (file.exists()) deserializer(file.readText()) else emptyList()
        }.getOrElse {
            Log.w("TryptifyDao", "failed to read ${file.name}, starting empty", it)
            emptyList()
        }

    fun snapshot(): List<T> = state.value

    suspend fun mutate(block: (MutableList<T>) -> Unit) {
        synchronized(this) {
            val next = state.value.toMutableList()
            block(next)
            val json = serializer(next.toList())
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
            state.value = next.toList()
        }
    }
}

class FileBackedEqPresetDao(
    context: Context,
) : EqPresetDao {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = JsonStore(
        File(context.applicationContext.filesDir, "tryptify_eq_presets.json"),
        serializer = { json.encodeToString(it) },
        deserializer = { raw ->
            json.decodeFromString<List<EqPresetEntity>>(raw)
        },
    )

    override fun getAllPresets(): Flow<List<EqPresetEntity>> =
        store.flow.map { list -> list.filter { it.eqType == 0 }.sortedWith(compareByDescending<EqPresetEntity> { it.isCustom }.thenByDescending { it.updatedAt }) }

    override fun getCustomPresets(): Flow<List<EqPresetEntity>> =
        store.flow.map { list -> list.filter { it.isCustom && it.eqType == 0 }.sortedByDescending { it.updatedAt } }

    override fun getBuiltInPresets(): Flow<List<EqPresetEntity>> =
        store.flow.map { list -> list.filter { !it.isCustom && it.eqType == 0 }.sortedBy { it.name } }

    override suspend fun getPresetById(presetId: String): EqPresetEntity? =
        store.snapshot().firstOrNull { it.id == presetId }

    override fun getPresetByIdFlow(presetId: String): Flow<EqPresetEntity?> =
        store.flow.map { list -> list.firstOrNull { it.id == presetId } }

    override suspend fun getAllPresetsSnapshot(): List<EqPresetEntity> =
        store.snapshot().sortedByDescending { it.updatedAt }

    override suspend fun insertPreset(preset: EqPresetEntity) {
        store.mutate { list -> list.removeAll { it.id == preset.id }; list.add(preset) }
    }

    override suspend fun insertPresets(presets: List<EqPresetEntity>) {
        store.mutate { list ->
            list.removeAll { existing -> presets.any { it.id == existing.id } }
            list.addAll(presets)
        }
    }

    override suspend fun updatePreset(preset: EqPresetEntity) {
        store.mutate { list ->
            list.removeAll { it.id == preset.id }
            list.add(preset)
        }
    }

    override suspend fun deletePreset(presetId: String) {
        store.mutate { list -> list.removeAll { it.id == presetId } }
    }

    override suspend fun deleteAllCustomPresets() {
        store.mutate { list -> list.removeAll { it.isCustom && it.eqType == 0 } }
    }

    override suspend fun presetExists(presetId: String): Boolean =
        store.snapshot().any { it.id == presetId }

    override fun getPresetCount(): Flow<Int> =
        store.flow.map { list -> list.count { it.eqType == 0 } }

    override fun getCustomPresetCount(): Flow<Int> =
        store.flow.map { list -> list.count { it.isCustom && it.eqType == 0 } }

    override fun searchPresets(searchQuery: String): Flow<List<EqPresetEntity>> =
        store.flow.map { list ->
            list.filter { it.eqType == 0 && it.name.contains(searchQuery, ignoreCase = true) }
                .sortedWith(compareByDescending<EqPresetEntity> { it.isCustom }.thenByDescending { it.updatedAt })
        }

    override fun getPresetsByTarget(targetId: String): Flow<List<EqPresetEntity>> =
        store.flow.map { list ->
            list.filter { it.eqType == 0 && it.targetId == targetId }
                .sortedWith(compareByDescending<EqPresetEntity> { it.isCustom }.thenByDescending { it.updatedAt })
        }

    override fun getAllParametricPresets(): Flow<List<EqPresetEntity>> =
        store.flow.map { list -> list.filter { it.eqType == 1 }.sortedWith(compareByDescending<EqPresetEntity> { it.isCustom }.thenByDescending { it.updatedAt }) }

    override fun getCustomParametricPresets(): Flow<List<EqPresetEntity>> =
        store.flow.map { list -> list.filter { it.isCustom && it.eqType == 1 }.sortedByDescending { it.updatedAt } }

    override fun getCustomParametricPresetCount(): Flow<Int> =
        store.flow.map { list -> list.count { it.isCustom && it.eqType == 1 } }

    override fun searchParametricPresets(searchQuery: String): Flow<List<EqPresetEntity>> =
        store.flow.map { list ->
            list.filter { it.eqType == 1 && it.name.contains(searchQuery, ignoreCase = true) }
                .sortedWith(compareByDescending<EqPresetEntity> { it.isCustom }.thenByDescending { it.updatedAt })
        }
}

class FileBackedMixPresetDao(
    context: Context,
) : MixPresetDao {
    private val json = Json { ignoreUnknownKeys = true }
    private val store = JsonStore(
        File(context.applicationContext.filesDir, "tryptify_mix_presets.json"),
        serializer = { json.encodeToString(it) },
        deserializer = { raw ->
            json.decodeFromString<List<MixPresetEntity>>(raw)
        },
    )

    override fun getAllPresets(): Flow<List<MixPresetEntity>> =
        store.flow.map { list -> list.sortedByDescending { it.updatedAt } }

    override suspend fun getPresetById(id: Long): MixPresetEntity? =
        store.snapshot().firstOrNull { it.id == id }

    override suspend fun getAllPresetsSnapshot(): List<MixPresetEntity> =
        store.snapshot().sortedByDescending { it.updatedAt }

    override suspend fun getByCreatedAt(createdAt: Long): MixPresetEntity? =
        store.snapshot().firstOrNull { it.createdAt == createdAt }

    override suspend fun upsert(preset: MixPresetEntity): Long {
        store.mutate { list ->
            val existingIndex = list.indexOfFirst { it.id == preset.id && preset.id != 0L }
            if (existingIndex >= 0) {
                list[existingIndex] = preset
            } else {
                val withId = if (preset.id == 0L) preset.copy(id = nextId(list)) else preset
                list.add(withId)
            }
        }
        return store.snapshot().firstOrNull { it.name == preset.name && it.createdAt == preset.createdAt }?.id ?: 0L
    }

    override suspend fun insertIfNotExists(preset: MixPresetEntity): Long {
        val existing = store.snapshot().firstOrNull { it.id == preset.id }
        if (existing != null) return existing.id
        upsert(preset)
        return preset.id
    }

    override suspend fun delete(id: Long) {
        store.mutate { list -> list.removeAll { it.id == id } }
    }

    override fun getPresetCount(): Flow<Int> = store.flow.map { it.size }

    private fun nextId(list: List<MixPresetEntity>): Long =
        (list.maxOfOrNull { it.id } ?: 0L) + 1L
}
