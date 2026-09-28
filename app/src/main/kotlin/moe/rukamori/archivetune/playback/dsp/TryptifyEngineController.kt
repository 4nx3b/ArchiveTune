@file:OptIn(androidx.media3.common.util.UnstableApi::class)

/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 *
 * Service-side glue for the ported Tryptify engine, ported from Tryptify's
 * PlaybackService wiring (https://github.com/tryptz/Tryptify):
 *  - collects the AutoEQ preferences (bands L/R, stereo mode, preamp, tone
 *    shelves, system-wide flag) and pushes them into AutoEqProcessor;
 *  - collects the Parametric EQ preferences and pushes them into
 *    ParametricEqProcessor;
 *  - restores the mixing-console state whenever the native engine is
 *    (re)created (DspEngineManager's engineReady contract);
 *  - turns the mixer master toggle on when the engine is engaged so the
 *    C++ chain actually processes (its default is bypassed).
 */

package moe.rukamori.archivetune.playback.dsp

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import tf.monochrome.android.audio.dsp.DspEngineManager
import tf.monochrome.android.audio.dsp.MixBusProcessor
import tf.monochrome.android.audio.eq.AutoEqProcessor
import tf.monochrome.android.audio.eq.ParametricEqProcessor
import tf.monochrome.android.data.preferences.PreferencesManager
import tf.monochrome.android.domain.model.EqBand
import tf.monochrome.android.domain.model.ToneControls

class TryptifyEngineController(
    context: Context,
    private val scope: CoroutineScope,
    val mixBus: MixBusProcessor,
    val autoEq: AutoEqProcessor,
    val paramEq: ParametricEqProcessor,
    val preferences: PreferencesManager,
    val dspManager: DspEngineManager,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }

    private var restoreJob: Job? = null
    private var engineEverReady = false

    fun start() {
        // EQ + tone + system-wide combined state -> AutoEqProcessor (the
        // double-correction guard: when system-wide AutoEQ is ON the in-app
        // correction is bypassed because the global output mix already
        // corrects this app's audio too).
        combine(
            preferences.eqEnabled,
            preferences.eqBandsJson,
            preferences.eqBandsRJson,
            preferences.eqStereoMode,
            preferences.eqPreamp,
            preferences.systemToneControls,
            preferences.systemWideAutoEqEnabled,
        ) { enabled, bandsJson, bandsRJson, stereo, preamp, tone, systemWide ->
            EqApply(enabled, bandsJson, bandsRJson, stereo, preamp, tone, systemWide)
        }.distinctUntilChanged()
            .onEach { applyEqSettings(it) }
            .launchIn(scope)

        // Parametric EQ state -> ParametricEqProcessor
        combine(
            preferences.paramEqEnabled,
            preferences.paramEqBandsJson,
            preferences.paramEqPreamp,
        ) { enabled, bandsJson, preamp ->
            Triple(enabled, bandsJson, preamp)
        }.distinctUntilChanged()
            .onEach { (enabled, bandsJson, preamp) ->
                applyParametricEqSettings(enabled, bandsJson, preamp)
            }
            .launchIn(scope)

        // Mixer state lifecycle: restore once the native engine exists, and
        // re-apply (preferring the live in-memory state) on every rebuild.
        mixBus.engineReady
            .onEach { ready ->
                if (!ready) return@onEach
                if (!engineEverReady) {
                    engineEverReady = true
                    restoreJob?.cancel()
                    restoreJob = scope.launch {
                        runCatching { dspManager.restoreState() }
                            .onFailure { Log.w(TAG, "DSP state restore failed", it) }
                    }
                } else {
                    restoreJob?.cancel()
                    restoreJob = scope.launch {
                        runCatching { dspManager.reapplyAfterEngineRecreated() }
                            .onFailure { Log.w(TAG, "DSP state reapply failed", it) }
                    }
                }
            }
            .launchIn(scope)
    }

    /**
     * The engine's master switch: engaging the Tryptify engine turns the
     * Tryptify mixer master toggle on so the C++ chain processes (its
     * default state is bypassed); disengaging leaves the internal state
     * untouched — the router simply stops routing audio through it.
     */
    fun setEngineActive(active: Boolean) {
        if (active) {
            scope.launch {
                if (!preferences.dspEnabled.first()) {
                    preferences.setDspEnabled(true)
                }
            }
        }
    }

    private fun applyEqSettings(cfg: EqApply) {
        runCatching {
            if (cfg.systemWide) {
                // The system-wide effect already corrects this app's audio;
                // in-app AutoEQ bypassed to avoid a double correction.
                autoEq.applyBands(emptyList(), 0f, false)
                return
            }
            fun decode(bandsJson: String?): List<EqBand> =
                if (cfg.enabled && !bandsJson.isNullOrEmpty()) {
                    json.decodeFromString(bandsJson)
                } else {
                    emptyList()
                }
            val autoL = decode(cfg.bandsJson)
            // 2-channel mode gives the right ear its own curve; with the
            // switch off (or no R curve saved yet) the left list drives both.
            val autoR = if (cfg.stereo) decode(cfg.bandsRJson).ifEmpty { autoL } else autoL
            // Tone shelves are ear-agnostic: appended to both channels so
            // bass/treble stay centred regardless of the calibration split.
            val toneBands = cfg.tone.toBands()
            val bandsL = autoL + toneBands
            val bandsR = autoR + toneBands
            val preamp = if (cfg.enabled) cfg.preamp else 0.0
            val active = bandsL.any { it.enabled } || bandsR.any { it.enabled }
            autoEq.applyBands(bandsL, bandsR, preamp.toFloat(), active)
        }
    }

    private fun applyParametricEqSettings(enabled: Boolean, bandsJson: String?, preamp: Double) {
        runCatching {
            val bands = if (!bandsJson.isNullOrEmpty()) {
                json.decodeFromString<List<EqBand>>(bandsJson)
            } else {
                emptyList()
            }
            paramEq.applyBands(bands, preamp.toFloat(), enabled)
        }
    }

    private data class EqApply(
        val enabled: Boolean,
        val bandsJson: String?,
        val bandsRJson: String?,
        val stereo: Boolean,
        val preamp: Double,
        val tone: ToneControls,
        val systemWide: Boolean,
    )

    private companion object {
        const val TAG = "TryptifyEngine"
    }
}
