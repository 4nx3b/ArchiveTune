/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.navigation.NavHostController
import moe.rukamori.archivetune.LocalPlayerConnection
import moe.rukamori.archivetune.R

/**
 * Hosts a full-screen YouTube IFrame Player that plays the music video for the
 * given [videoId]. The audio player is paused while this screen is visible so
 * the user only hears the video's audio — matching the "Video" toggle behavior
 * in YouTube Music.
 *
 * The screen does NOT auto-resume audio playback on exit; the user can press
 * play in the mini-player / player sheet when they return.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun VideoPlayerScreen(
    navController: NavHostController,
    videoId: String,
    title: String? = null,
) {
    val context = LocalContext.current
    val playerConnection = LocalPlayerConnection.current

    // Pause the audio player while the video is playing to avoid double audio.
    // We do NOT auto-resume on dispose — that would surprise the user with
    // sudden audio playback after they explicitly left the video view.
    DisposableEffect(Unit) {
        playerConnection?.player?.playWhenReady = false
        onDispose {
            // No-op: user controls when audio resumes.
        }
    }

    var isLoading by remember { mutableStateOf(true) }
    var hasError by remember { mutableStateOf(false) }

    // Bridge so the IFrame API can signal errors / ready state back to Kotlin.
    val bridge =
        remember(videoId) {
            object {
                @JavascriptInterface
                fun onReady() {
                    isLoading = false
                }

                @JavascriptInterface
                fun onError(errorCode: Int) {
                    hasError = true
                    isLoading = false
                }
            }
        }

    BackHandler { navController.popBackStack() }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    // The IFrame player posts messages to a named JS bridge; we expose
                    // it as `AndroidBridge` so the embedded HTML can call back.
                    addJavascriptInterface(bridge, "AndroidBridge")
                    webChromeClient =
                        object : WebChromeClient() {
                            override fun onProgressChanged(
                                view: WebView?,
                                newProgress: Int,
                            ) {
                                if (newProgress >= 80) isLoading = false
                            }
                        }
                    webViewClient =
                        object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = true // Block navigation away from the embedded player
                        }
                    loadDataWithBaseURL(
                        "https://www.youtube.com",
                        buildPlayerHtml(videoId),
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            update = { webView ->
                // No-op: videoId is fixed for the lifetime of this screen.
                // Re-loading on every recomposition would restart the video.
            },
            onRelease = { webView ->
                webView.removeJavascriptInterface("AndroidBridge")
                webView.stopLoading()
                webView.onPause()
                webView.destroy()
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Top bar overlay (transparent so it doesn't block the video).
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IconButton(onClick = { navController.popBackStack() }) {
                Icon(
                    painter = painterResource(R.drawable.arrow_back),
                    contentDescription = null,
                    tint = Color.White,
                )
            }
            Text(
                text = title ?: stringResource(R.string.video_player_title),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$videoId")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                runCatching { context.startActivity(intent) }
            }) {
                Icon(
                    painter = painterResource(R.drawable.slow_motion_video),
                    contentDescription = stringResource(R.string.video_open_in_youtube),
                    tint = Color.White,
                )
            }
        }

        if (isLoading) {
            CircularProgressIndicator(
                modifier =
                    Modifier
                        .align(Alignment.Center)
                        .size(48.dp),
                color = Color.White,
            )
        }

        if (hasError) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.video_load_failed),
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

private fun buildPlayerHtml(videoId: String): String =
    """
    <!DOCTYPE html>
    <html>
    <head>
        <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
        <style>
            html, body { margin: 0; padding: 0; height: 100%; width: 100%; background: #000; overflow: hidden; }
            #player { width: 100vw; height: 100vh; }
        </style>
    </head>
    <body>
        <div id="player"></div>
        <script src="https://www.youtube.com/iframe_api"></script>
        <script>
            (function() {
                var apiReady = false;
                var domReady = false;
                var attemptedStart = false;

                function tryStart() {
                    if (!apiReady || !domReady || attemptedStart) return;
                    attemptedStart = true;
                    new YT.Player('player', {
                        videoId: '$videoId',
                        playerVars: {
                            'autoplay': 1,
                            'controls': 1,
                            'rel': 0,
                            'modestbranding': 1,
                            'playsinline': 1,
                            'fs': 1,
                            'origin': 'https://www.youtube.com'
                        },
                        events: {
                            'onReady': function(e) {
                                e.target.playVideo();
                                if (window.AndroidBridge) { window.AndroidBridge.onReady(); }
                            },
                            'onError': function(e) {
                                if (window.AndroidBridge) { window.AndroidBridge.onError(e.data); }
                            }
                        }
                    });
                }

                window.onYouTubeIframeAPIReady = function() {
                    apiReady = true;
                    tryStart();
                };

                document.addEventListener('DOMContentLoaded', function() {
                    domReady = true;
                    tryStart();
                });
            })();
        </script>
    </body>
    </html>
    """.trimIndent()
