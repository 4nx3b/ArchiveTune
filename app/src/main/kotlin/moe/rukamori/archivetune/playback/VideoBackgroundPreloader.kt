/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.constants.MaxVideoCacheSizeKey
import moe.rukamori.archivetune.constants.VideoQualityPreferredHeightKey
import moe.rukamori.archivetune.di.VideoCacheEntryPoint
import moe.rukamori.archivetune.models.MediaMetadata
import moe.rukamori.archivetune.ui.player.VideoQualityPreference
import moe.rukamori.archivetune.ui.player.resolveVideoStreamUrl
import moe.rukamori.archivetune.ui.player.videoStreamHttpClient
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.isLocalMediaId
import timber.log.Timber


object VideoBackgroundPreloader {
    private const val TAG = "VideoPreload"

    private const val HOLD_TIMEOUT_MS = 12_000L

    private const val POLL_INTERVAL_MS = 250L

    private const val UI_CLAIM_GRACE_MS = 3_000L

    private const val VIDEO_MIN_BUFFER_MS = 15_000
    private const val VIDEO_MAX_BUFFER_MS = 90_000
    private const val VIDEO_BUFFER_FOR_PLAYBACK_MS = 600
    private const val VIDEO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var appContext: Context? = null
    private var isUiForeground: () -> Boolean = { true }

    private var mainPlayer: Player? = null
    private var preloadJob: Job? = null
    private var preloadPlayer: ExoPlayer? = null
    private var preloadPlayerFactoryKey: Context? = null

    @Volatile
    private var heldMediaId: String? = null

    @Volatile
    private var wasPlayingBeforeHold = false

    fun initialize(
        context: Context,
        isUiForeground: () -> Boolean,
    ) {
        appContext = context.applicationContext
        this.isUiForeground = isUiForeground
    }


    fun onMediaItemChanged(
        player: Player,
        mediaId: String?,
        metadata: MediaMetadata?,
        videoPlaybackEnabled: Boolean,
        reason: Int,
    ) {
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) return

        mainPlayer = player
        val mediaIsVideo =
            !mediaId.isNullOrBlank() &&
                metadata?.isMusicVideo == true &&
                !mediaId.isLocalMediaId()

        if (!mediaIsVideo || !videoPlaybackEnabled) {
            releaseHold(resume = wasPlayingBeforeHold)
            cancelPreload()
            return
        }

        if (heldMediaId == mediaId) return // already holding for this item

        releaseHold(resume = false)
        cancelPreload()

        if (isUiForeground()) {
            return
        }

        startHoldAndPreload(player, mediaId)
    }

    private fun startHoldAndPreload(
        player: Player,
        mediaId: String,
    ) {
        heldMediaId = mediaId
        wasPlayingBeforeHold = player.playWhenReady
        if (player.playWhenReady) {
            player.pause()
        }
        Timber.tag(TAG).d("Holding main audio for background video load of $mediaId (wasPlaying=$wasPlayingBeforeHold)")

        preloadJob =
            scope.launch {
                try {
                    val context = appContext ?: return@launch
                    val resolved =
                        withContext(Dispatchers.IO) {
                            val preferredHeight =
                                VideoQualityPreference.toPreferredHeight(
                                    context.dataStore.get(
                                        VideoQualityPreferredHeightKey,
                                        VideoQualityPreference.HIGH_QUALITY,
                                    ),
                                )
                            runCatching { resolveVideoStreamUrl(mediaId, preferredHeight) }
                                .getOrElse { error ->
                                    if (error is kotlinx.coroutines.CancellationException) throw error
                                    Timber.tag(TAG).w(error, "Background video resolution failed for $mediaId")
                                    null
                                }
                        }

                    if (resolved == null || heldMediaId != mediaId) {
                        releaseHold(resume = wasPlayingBeforeHold)
                        return@launch
                    }

                    val preloadExo = obtainPreloadPlayer(context)
                    val mimeType =
                        when {
                            resolved.streamUrl.contains("m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
                            else -> MimeTypes.VIDEO_MP4
                        }
                    preloadExo.stop()
                    preloadExo.setMediaItem(
                        MediaItem.Builder().setUri(resolved.streamUrl).setMimeType(mimeType).build(),
                    )
                    val mainPosition = player.currentPosition.coerceAtLeast(0L)
                    if (mainPosition > 0L) preloadExo.seekTo(mainPosition)
                    preloadExo.prepare()

                    val deadline = SystemClock.elapsedRealtime() + HOLD_TIMEOUT_MS
                    var foregroundSinceMs = 0L
                    loop@ while (true) {
                        if (heldMediaId != mediaId) {
                            return@launch
                        }
                        if (isUiForeground()) {
                            if (foregroundSinceMs == 0L) {
                                foregroundSinceMs = SystemClock.elapsedRealtime()
                                Timber.tag(TAG).d("UI foregrounded while preloading $mediaId — waiting for the claim")
                            }
                            if (SystemClock.elapsedRealtime() - foregroundSinceMs >= UI_CLAIM_GRACE_MS) {
                                Timber
                                    .tag(TAG)
                                    .w("UI never claimed the hold for $mediaId — resuming audio")
                                break@loop
                            }
                        } else {
                            foregroundSinceMs = 0L
                        }
                        if (preloadExo.playerError != null) {
                            Timber.tag(TAG).d("Background preload errored for $mediaId — releasing hold")
                            break@loop
                        }
                        if (preloadExo.playbackState == Player.STATE_READY) {
                            Timber.tag(TAG).i("Background video preloaded (READY) for $mediaId — releasing hold")
                            break@loop
                        }
                        if (SystemClock.elapsedRealtime() >= deadline) {
                            Timber.tag(TAG).w("Background preload timed out for $mediaId — releasing hold")
                            break@loop
                        }
                        delay(POLL_INTERVAL_MS)
                    }

                    releaseHold(resume = wasPlayingBeforeHold)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (error: Throwable) {
                    Timber.tag(TAG).w(error, "Background preload crashed for $mediaId — releasing hold")
                    releaseHold(resume = wasPlayingBeforeHold)
                }
            }
    }


    fun claimUiTakeover(mediaId: String): Boolean {
        val claimed = heldMediaId
        if (claimed == null || claimed != mediaId) return false
        val wasPlaying = wasPlayingBeforeHold
        heldMediaId = null
        wasPlayingBeforeHold = false
        cancelPreload()
        Timber.tag(TAG).d("UI claimed the background hold for $mediaId (wasPlaying=$wasPlaying)")
        return wasPlaying
    }

    private fun releaseHold(resume: Boolean) {
        val held = heldMediaId ?: return
        heldMediaId = null
        val player = mainPlayer
        Timber.tag(TAG).d("Releasing hold for $held (resume=$resume)")
        if (resume && player != null && player.playWhenReady.not() && player.currentMediaItem?.mediaId == held) {
            runCatching { player.play() }
        }
        wasPlayingBeforeHold = false
    }

    private fun cancelPreload() {
        preloadJob?.cancel()
        preloadJob = null
        val player = preloadPlayer ?: return
        preloadPlayer = null
        mainHandler.post {
            runCatching {
                player.stop()
                player.release()
            }
        }
    }

    private fun obtainPreloadPlayer(context: Context): ExoPlayer {
        preloadPlayer?.let { existing ->
            if (preloadPlayerFactoryKey === context) return existing
            runCatching { existing.release() }
        }
        val cacheEnabled = context.dataStore.get(MaxVideoCacheSizeKey, 512) != 0
        val videoCache =
            runCatching {
                EntryPointAccessors
                    .fromApplication(context, VideoCacheEntryPoint::class.java)
                    .videoCache()
            }.getOrNull()
        val upstreamFactory =
            DefaultDataSource.Factory(context, OkHttpDataSource.Factory(videoStreamHttpClient()))
        val dataSourceFactory =
            if (videoCache != null && cacheEnabled) {
                CacheDataSource.Factory()
                    .setCache(videoCache)
                    .setUpstreamDataSourceFactory(upstreamFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
            } else {
                upstreamFactory
            }
        val trackSelector =
            DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .setForceHighestSupportedBitrate(true)
                        .build(),
                )
            }
        val loadControl =
            DefaultLoadControl
                .Builder()
                .setBufferDurationsMs(
                    VIDEO_MIN_BUFFER_MS,
                    VIDEO_MAX_BUFFER_MS,
                    VIDEO_BUFFER_FOR_PLAYBACK_MS,
                    VIDEO_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS,
                ).setPrioritizeTimeOverSizeThresholds(true)
                .build()
        val player =
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .setRenderersFactory(DefaultRenderersFactory(context).setEnableDecoderFallback(true))
                .setTrackSelector(trackSelector)
                .setLoadControl(loadControl)
                .build()
        player.volume = 0f
        player.playWhenReady = false
        preloadPlayer = player
        preloadPlayerFactoryKey = context
        return player
    }


    fun onServiceDestroyed() {
        releaseHold(resume = false)
        cancelPreload()
        mainPlayer = null
    }
}
