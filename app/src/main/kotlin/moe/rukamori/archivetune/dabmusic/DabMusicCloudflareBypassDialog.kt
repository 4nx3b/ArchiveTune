/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.dabmusic

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import moe.rukamori.archivetune.R
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An in-app WebView dialog that solves dabmusic.xyz's Cloudflare "managed challenge" and
 * captures the resulting `cf_clearance` cookie.
 *
 * ## Why this exists
 *
 * dabmusic.xyz is fronted by Cloudflare. The WAF rule whitelists `POST /api/auth/login` (so the
 * user can authenticate from [DabMusicAudioProvider.login]) but applies a "managed challenge"
 * to `GET /api/search` and `GET /api/stream`. On challenged requests Cloudflare returns HTTP 200
 * with `Transfer-Encoding: chunked` and drip-feeds the interstitial HTML body over 30+ seconds,
 * which causes OkHttp's `readTimeout` to fire as a `SocketTimeoutException` — exactly the
 * failure mode the user sees in their logs.
 *
 * Cloudflare's challenge is JavaScript-based: the interstitial page computes a token, submits it
 * back to Cloudflare, and on success Cloudflare issues a `cf_clearance` cookie that whitelists
 * the client for ~30 minutes. Browsers solve this transparently; OkHttp cannot.
 *
 * ## How it works
 *
 * 1. The dialog opens a fullscreen WebView with JS enabled.
 * 2. The WebView loads `https://dabmusic.xyz/`.
 * 3. Cloudflare serves the interstitial; the WebView's JS engine solves the challenge.
 * 4. Cloudflare redirects to the real dabmusic.xyz homepage and sets `cf_clearance` via
 *    `Set-Cookie`. The WebView's [CookieManager] stores it.
 * 5. On page finish (or after a 30s safety timeout), we read `cf_clearance` from
 *    [CookieManager.getCookie] and call [onCfClearanceCaptured].
 * 6. The caller persists the cookie in [moe.rukamori.archivetune.constants.DabMusicCfClearanceKey]
 *    and pushes it into [DabMusicAudioProvider.setCfClearance] — every subsequent OkHttp request
 *    includes `cf_clearance=...` in its `Cookie` header and Cloudflare passes it through.
 *
 * The cookie is short-lived (~30 min). When it expires, the user reopens this dialog.
 *
 * ## Privacy
 *
 * The WebView runs entirely on-device. No cookies, tokens, or browsing data leave the device
 * except to dabmusic.xyz itself. The cf_clearance cookie is shared between the WebView and
 * OkHttp (via the persisted preference), but it is never sent to any third party.
 *
 * @param baseUrl The base URL to load (must be the dabmusic.xyz homepage, NOT an /api/* path —
 *   the challenge is issued on the marketing site, not the API).
 * @param onDismiss Called when the user closes the dialog or the capture completes/fails.
 * @param onCfClearanceCaptured Called on the main thread with the captured cf_clearance cookie
 *   value. The caller is responsible for persisting it and pushing it into
 *   [DabMusicAudioProvider].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DabMusicCloudflareBypassDialog(
    baseUrl: String,
    onDismiss: () -> Unit,
    onCfClearanceCaptured: (cfClearance: String) -> Unit,
) {
    val context = LocalContext.current
    val captureHandled = remember { AtomicBoolean(false) }
    var statusMessage by remember {
        mutableStateOf(context.getString(R.string.dabmusic_cf_bypass_loading))
    }

    fun extractCfClearance(): String? {
        // CookieManager.getCookie returns "name=value; name2=value2" for the given URL, or null.
        // We split on "; " and look for cf_clearance=. The cookie value itself can contain
        // URL-safe characters but never ";" or ",", so this parse is safe.
        val cookies = CookieManager.getInstance().getCookie(baseUrl) ?: return null
        return cookies
            .split(";")
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { raw ->
                val eq = raw.indexOf('=')
                if (eq <= 0) return@firstNotNullOfOrNull null
                val name = raw.substring(0, eq).trim()
                val value = raw.substring(eq + 1).trim()
                if (name.equals("cf_clearance", ignoreCase = true) && value.isNotEmpty()) value else null
            }
    }

    fun finishCapture() {
        if (!captureHandled.compareAndSet(false, true)) return
        val cfClearance = extractCfClearance()
        if (cfClearance.isNullOrBlank()) {
            Toast.makeText(
                context,
                context.getString(R.string.dabmusic_cf_bypass_failed),
                Toast.LENGTH_LONG,
            ).show()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.dabmusic_cf_bypass_success),
                Toast.LENGTH_SHORT,
            ).show()
            onCfClearanceCaptured(cfClearance)
        }
        onDismiss()
    }

    // Safety timeout: if Cloudflare's challenge doesn't resolve within 30s (e.g. the user's
    // network is very slow or Cloudflare is misbehaving), give up and let the user retry.
    LaunchedEffect(Unit) {
        delay(30_000)
        if (!captureHandled.get()) {
            statusMessage = context.getString(R.string.dabmusic_cf_bypass_failed)
            delay(1_500)
            finishCapture()
        }
    }

    Dialog(
        onDismissRequest = { finishCapture() },
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = false,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(600.dp)
                    .padding(8.dp),
        ) {
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(8.dp),
            )
            Text(
                text = stringResource(R.string.dabmusic_cf_bypass_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .padding(8.dp),
            )
            AndroidView(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 32.dp, bottom = 48.dp)
                        .wrapContentHeight(Alignment.CenterVertically),
                factory = { ctx ->
                    WebView(ctx).apply {
                        @SuppressLint("SetJavaScriptEnabled")
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            // Cloudflare's challenge uses JavaScript + cookies; both must be on.
                            javaScriptCanOpenWindowsAutomatically = true
                            setSupportZoom(false)
                            displayZoomControls = false
                        }
                        // Accept cookies so Cloudflare can set cf_clearance.
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    // The challenge page itself fires onPageFinished too — we
                                    // only care about the post-redirect homepage load. The
                                    // homepage URL is the bare base URL (no path) or "/".
                                    val host = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore('/')
                                    if (url != null && url.contains(host) && !url.contains("challenge", ignoreCase = true)) {
                                        // Cloudflare sets cf_clearance via Set-Cookie during the
                                        // redirect; CookieManager flushes asynchronously, so we
                                        // poll for the cookie to appear.
                                        view.postDelayed({
                                            if (!captureHandled.get()) {
                                                statusMessage =
                                                    context.getString(R.string.dabmusic_cf_bypass_success)
                                                finishCapture()
                                            }
                                        }, 800)
                                    }
                                }
                            }
                        loadUrl(baseUrl)
                    }
                },
            )
        }
    }
}
