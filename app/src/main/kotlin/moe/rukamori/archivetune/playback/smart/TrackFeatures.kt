/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Automix analysis pipeline ported from BitChord
 * (https://github.com/kushagrasinghx/BitChord), which derives it from
 * Orchard (https://github.com/SFG5453/Orchard). Orchard's original source
 * is licensed AGPL-3.0-or-later; per AGPLv3 section 13 this file is
 * combined into ArchiveTune -- a GPL-3.0-or-later work -- and remains
 * itself governed by the AGPLv3 as part of that combination.
 */

package moe.rukamori.archivetune.playback.smart
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object TrackFeatures {
    val available: Boolean = runCatching { System.loadLibrary("archivetune_analysis") }.isSuccess

    val sampleRate: Double by lazy { if (available) nativeSampleRate() else 11_025.0 }

    fun analyze(samples: FloatArray, durationSeconds: Double): Features? {
        if (!available || samples.isEmpty()) return null
        val json = runCatching { nativeAnalyze(samples, sampleRate, durationSeconds) }
            .onFailure { Log.w(TAG, "Native analysis failed", it) }
            .getOrNull() ?: return null
        return runCatching { parse(JSONObject(json)) }
            .onFailure { Log.w(TAG, "Could not parse analysis output", it) }
            .getOrNull()
    }

    fun resample(samples: FloatArray, inputRate: Double, outputRate: Double = sampleRate): FloatArray? {
        if (!available || samples.isEmpty() || inputRate <= 0 || outputRate <= 0) return null
        return nativeResample(samples, inputRate, outputRate).takeIf { it.isNotEmpty() }
    }

    data class Features(
        val duration: Double,
        val bpm: Double,
        val beatInterval: Double,
        val firstBeat: Double,
        val beatConfidence: Double,
        val key: String,
        val keyConfidence: Double,
        val audibleStartTime: Double,
        val pickupTime: Double,
        val introEndTime: Double,
        val outroStartTime: Double,
        val contentEndTime: Double,
        val mixInTime: Double,
        val mixOutTime: Double,
        val vocalProbability: Double,
        val downbeats: List<Double>,
        val phraseBoundaries: List<Double>,
        val vocalActivityMask: List<Double>,
        val energyCurve: List<EnergySample>,
        val lowEnergyCurve: List<EnergySample>,
        val mixInCandidates: List<MixCandidate>,
        val mixOutCandidates: List<MixCandidate>,
    )

    fun parse(root: JSONObject): Features = Features(
        duration = root.optDouble("duration", 0.0).orZero(),
        bpm = root.optDouble("bpm", 0.0).orZero(),
        beatInterval = root.optDouble("beatInterval", 0.0).orZero(),
        firstBeat = root.optDouble("firstBeat", 0.0).orZero(),
        beatConfidence = root.optDouble("beatConfidence", 0.0).orZero(),
        key = root.optString("key", ""),
        keyConfidence = root.optDouble("keyConfidence", 0.0).orZero(),
        audibleStartTime = root.optDouble("audibleStartTime", 0.0).orZero(),
        pickupTime = root.optDouble("pickupTime", 0.0).orZero(),
        introEndTime = root.optDouble("introEndTime", 0.0).orZero(),
        outroStartTime = root.optDouble("outroStartTime", 0.0).orZero(),
        contentEndTime = root.optDouble("contentEndTime", 0.0).orZero(),
        mixInTime = root.optDouble("mixInTime", 0.0).orZero(),
        mixOutTime = root.optDouble("mixOutTime", 0.0).orZero(),
        vocalProbability = root.optDouble("vocalProbability", 0.0).orZero(),
        downbeats = root.doubles("downbeats"),
        phraseBoundaries = root.doubles("phraseBoundaries"),
        vocalActivityMask = root.doubles("vocalActivityMask"),
        energyCurve = root.energyCurve("energyCurve"),
        lowEnergyCurve = root.energyCurve("lowEnergyCurve"),
        mixInCandidates = root.cuePoints("mixInCandidates"),
        mixOutCandidates = root.cuePoints("mixOutCandidates"),
    )

    private fun JSONObject.doubles(name: String): List<Double> {
        val array = optJSONArray(name) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                array.optDouble(index).takeIf { it.isFinite() }?.let(::add)
            }
        }
    }

    private fun JSONObject.energyCurve(name: String): List<EnergySample> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val point = array.optJSONObject(index) ?: continue
                val time = point.optDouble("t", Double.NaN)
                val energy = point.optDouble("e", Double.NaN)
                if (time.isFinite() && energy.isFinite()) add(EnergySample(time, energy))
            }
        }
    }

    private fun JSONObject.cuePoints(name: String): List<MixCandidate> {
        val array: JSONArray = optJSONArray(name) ?: return emptyList()
        return buildList(array.length()) {
            for (index in 0 until array.length()) {
                val point = array.optJSONObject(index) ?: continue
                val time = point.optDouble("t", Double.NaN)
                if (!time.isFinite()) continue
                add(
                    MixCandidate(
                        time = time,
                        score = point.optDouble("s", 0.0).orZero(),
                        type = point.optString("y", ""),
                    ),
                )
            }
        }
    }

    private const val TAG = "BitChordTrackFeatures"

    @JvmStatic private external fun nativeAnalyze(
        samples: FloatArray,
        sampleRate: Double,
        duration: Double,
    ): String

    @JvmStatic private external fun nativeSampleRate(): Double

    @JvmStatic private external fun nativeResample(
        samples: FloatArray,
        inputRate: Double,
        outputRate: Double,
    ): FloatArray
}
