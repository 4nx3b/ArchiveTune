package tf.monochrome.android.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import tf.monochrome.android.data.api.HeadphoneAutoEqApi
import tf.monochrome.android.data.api.SquiglinkApi
import tf.monochrome.android.domain.model.AutoEqMeasurement
import tf.monochrome.android.domain.model.Headphone
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HeadphoneRepository @Inject constructor(
    private val autoEqApi: HeadphoneAutoEqApi,
    private val squiglinkApi: SquiglinkApi,
) {
    fun getAllHeadphones(): Flow<List<Headphone>> = flow {
        try {
            val auto = autoEqApi.fetchHeadphones().getOrDefault(emptyList())
            val squig = squiglinkApi.fetchHeadphones().getOrDefault(emptyList())
            emit(mergeByName(auto + squig))
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    suspend fun fetchMeasurementText(measurement: AutoEqMeasurement): String? = when (measurement.target) {
        "squiglink" -> squiglinkApi.fetchMeasurementText(measurement.host, measurement.fileName)

        else -> autoEqApi.fetchMeasurementByPath(measurement.path, measurement.fileName).getOrNull()
    }

    suspend fun fetchMeasurementChannelText(measurement: AutoEqMeasurement, channel: String): String? =
        when (measurement.target) {
            "squiglink" -> squiglinkApi.fetchMeasurementChannelText(
                measurement.host, measurement.fileName, channel,
            )
            else -> null
        }

    suspend fun fetchMeasurementChannel(
        measurement: AutoEqMeasurement,
        channel: String,
    ): Pair<String, String>? = when (measurement.target) {
        "squiglink" -> squiglinkApi.fetchMeasurementChannel(
            measurement.host, measurement.fileName, channel,
        )
        else -> null
    }

    suspend fun fetchMeasurementSampleText(measurement: AutoEqMeasurement, sample: String): String? =
        when (measurement.target) {
            "squiglink" -> squiglinkApi.fetchMeasurementSampleText(
                measurement.host, measurement.fileName, sample,
            )
            else -> null
        }

    suspend fun listMeasurementSamples(measurement: AutoEqMeasurement, channelPrefix: String): List<String> =
        when (measurement.target) {
            "squiglink" -> squiglinkApi.listSamples(
                measurement.host, measurement.fileName, channelPrefix,
            )
            else -> emptyList()
        }

    private fun mergeByName(all: List<Headphone>): List<Headphone> =
        all.groupBy { it.name }.map { (name, group) ->
            Headphone(
                id = group.first().id,
                name = name,
                type = group.first().type,
                measurements = group.flatMap { it.measurements },
            )
        }.sortedBy { it.name.lowercase() }

    fun searchHeadphones(query: String): Flow<List<Headphone>> = flow {
        try {
            if (query.isBlank()) {
                emit(emptyList())
                return@flow
            }

            val result = autoEqApi.searchHeadphones(query)
            result.onSuccess { headphones ->
                emit(headphones)
            }.onFailure {
                emit(emptyList())
            }
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    fun getHeadphonesByType(type: String): Flow<List<Headphone>> = flow {
        try {
            val result = autoEqApi.getHeadphonesByType(type)
            result.onSuccess { headphones ->
                emit(headphones)
            }.onFailure {
                emit(emptyList())
            }
        } catch (_: Exception) {
            emit(emptyList())
        }
    }

    fun loadHeadphoneMeasurement(
        headphoneId: String,
        headphoneName: String = ""
    ): Flow<Result<String>> = flow {
        try {
            val result = autoEqApi.fetchHeadphoneMeasurement(headphoneId, headphoneName)
            emit(result)
        } catch (e: Exception) {
            emit(Result.failure(e))
        }
    }

    fun refreshCache() {
        autoEqApi.clearCache()
        squiglinkApi.clearCache()
    }
}
