/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * SoundCloud web sign-in, ported from MetroFusePlus (GPL-3.0,
 * github.com/MetroFusePlus/MetroFusePlus) — SoundCloudLoginScreen.kt — as a
 * bottom sheet: loads soundcloud.com/signin, then sweeps cookies, localStorage,
 * sessionStorage and the runtime SDK object for the OAuth token.
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.soundcloud.SoundCloudAudioProvider
import moe.rukamori.archivetune.utils.soundcloud.mergeSoundCloudAuthInputs
import moe.rukamori.archivetune.ui.component.KeepStatusBarHiddenInDialog
import timber.log.Timber

private const val SoundCloudLoginUrl = "https://soundcloud.com/signin"

private val SoundCloudCookieUrls =
    listOf(
        "https://soundcloud.com",
        "https://www.soundcloud.com",
        "https://m.soundcloud.com",
        "https://secure.soundcloud.com",
        "https://api-v2.soundcloud.com",
    )

private val SoundCloudAuthCaptureDelaysMs = listOf(0L, 300L, 800L, 1_600L, 3_000L, 5_000L)

private const val SoundCloudStorageReadScript = """
    (function() {
        var rows = [];
        function dumpStorage(label, storage) {
            try {
                for (var i = 0; i < storage.length; i++) {
                    var key = storage.key(i);
                    var value = storage.getItem(key);
                    if (key && value) {
                        rows.push(label + ':' + key + '=' + value);
                    }
                }
            } catch (error) {}
        }
        dumpStorage('localStorage', window.localStorage);
        dumpStorage('sessionStorage', window.sessionStorage);
        try {
            var sc = window.SC;
            if (sc && sc.connector && typeof sc.connector.oauth_token === 'string') {
                rows.push('runtime:oauth_token=' + sc.connector.oauth_token);
            }
        } catch (error) {}
        try {
            var accessToken = window.__sc_token;
            if (typeof accessToken === 'string') rows.push('runtime:access_token=' + accessToken);
        } catch (error) {}
        return rows.join('\n');
    })()
"""

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SoundCloudLoginSheet(
    onDismiss: () -> Unit,
    onTokenCaptured: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captured by remember { mutableStateOf(false) }
    val handler = remember { Handler(Looper.getMainLooper()) }

    fun tryCapture(token: String?) {
        if (captured || token.isNullOrBlank()) return
        captured = true
        CookieManager.getInstance().flush()
        onTokenCaptured(token)
    }

    fun captureNow(view: WebView?) {
        view ?: return
        val cookieInputs = SoundCloudCookieUrls
            .mapNotNull { CookieManager.getInstance().getCookie(it) }
        mergeSoundCloudAuthInputs(cookieInputs)?.let(::tryCapture)
        view.evaluateJavascript(SoundCloudStorageReadScript) { result ->
            val storage = decodeSoundCloudJsString(result)
            if (storage.isNullOrBlank()) return@evaluateJavascript
            mergeSoundCloudAuthInputs(cookieInputs + storage)?.let(::tryCapture)
        }
    }

    fun finish() {
        captureNow(webView)
        onDismiss()
    }

    BackHandler(enabled = true, onBack = ::finish)

    DisposableEffect(Unit) {
        onDispose {
            handler.removeCallbacksAndMessages(null)
            runCatching {
                webView?.stopLoading()
                webView?.loadUrl("about:blank")
                webView?.destroy()
            }
            webView = null
        }
    }

    ModalBottomSheet(
        modifier = Modifier.fillMaxHeight(),
        onDismissRequest = ::finish,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        KeepStatusBarHiddenInDialog()
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.soundcloud_web_login),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.spotify_waiting_for_login),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            AndroidView(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(MaterialTheme.shapes.large),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = SoundCloudAudioProvider.BROWSER_USER_AGENT
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageStarted(
                                    view: WebView,
                                    url: String?,
                                    favicon: android.graphics.Bitmap?,
                                ) {
                                    super.onPageStarted(view, url, favicon)
                                    extractSoundCloudTokenFromUrl(url)?.let(::tryCapture)
                                    handler.postDelayed({ captureNow(view) }, 150L)
                                }

                                override fun onPageFinished(
                                    view: WebView,
                                    url: String?,
                                ) {
                                    super.onPageFinished(view, url)
                                    CookieManager.getInstance().flush()
                                    extractSoundCloudTokenFromUrl(url)?.let(::tryCapture)
                                    SoundCloudAuthCaptureDelaysMs.forEach { delay ->
                                        handler.postDelayed({ captureNow(view) }, delay)
                                    }
                                }
                            }
                        loadUrl(SoundCloudLoginUrl)
                        webView = this
                    }
                },
                update = {},
            )
        }
    }
}

private fun extractSoundCloudTokenFromUrl(url: String?): String? {
    if (url.isNullOrBlank()) return null
    val fragment = url.substringAfter('#', missingDelimiterValue = "")
    val candidates = buildList {
        add(url)
        if (fragment.isNotBlank()) {
            add("oauth_token=$fragment")
            add("access_token=$fragment")
        }
    }
    return mergeSoundCloudAuthInputs(candidates)
}

private fun decodeSoundCloudJsString(result: String?): String? =
    result
        ?.trim()
        ?.takeIf { it.isNotEmpty() && it != "null" }
        ?.trim('"')
        ?.replace("\\\"", "\"")
        ?.replace("\\\\", "\\")
        ?.replace("\\n", "\n")
        ?.replace("\\r", "\r")
        ?.replace("\\u003d", "=")
        ?.replace("\\u003D", "=")
        ?.replace("\\u003b", ";")
        ?.replace("\\u003B", ";")
        ?.takeIf { it.isNotBlank() }
        ?.also { Timber.tag("SoundCloudLogin").d("Captured SoundCloud storage payload") }
