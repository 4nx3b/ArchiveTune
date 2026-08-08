/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.dabmusic

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
 * 1. The dialog opens a near-fullscreen WebView with JS enabled.
 * 2. The WebView is configured with the SAME [DabMusicAudioProvider.USER_AGENT] used by OkHttp —
 *    this is critical: Cloudflare binds `cf_clearance` to (IP, User-Agent), so if the WebView
 *    solves the challenge with the default Android System WebView UA and OkHttp then sends the
 *    cookie with a desktop Chrome UA, Cloudflare rejects it. Keeping both clients on the same
 *    UA is what makes the captured cookie valid for OkHttp.
 * 3. The WebView loads `https://dabmusic.xyz/`.
 * 4. Cloudflare serves the interstitial; the WebView's JS engine solves the challenge
 *    automatically (or, for interactive Turnstile widgets, the user taps the checkbox in the
 *    visible WebView).
 * 5. Cloudflare redirects to the real dabmusic.xyz homepage and sets `cf_clearance` via
 *    `Set-Cookie`. The WebView's [CookieManager] stores it.
 * 6. A polling loop checks [CookieManager.getCookie] every 500ms for the `cf_clearance` entry —
 *    we don't rely solely on `onPageFinished` because Cloudflare may set the cookie via JS
 *    without a page load, or fire `onPageFinished` on the interstitial itself.
 * 7. When the cookie appears (or after a 60s safety timeout), we read it from
 *    [CookieManager.getCookie] and call [onCfClearanceCaptured].
 * 8. The caller persists the cookie in [moe.rukamori.archivetune.constants.DabMusicCfClearanceKey]
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
 * @param baseUrl The base URL to load (must be the dabmusic.xyz homepage, NOT an /api endpoint —
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
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Normalize the URL we pass to CookieManager.getCookie — that API wants a full URL with
    // scheme + host (no path), otherwise it returns null. The user's base URL preference might
    // be "https://dabmusic.xyz" (no trailing slash) or "https://dabmusic.xyz/" — both should
    // resolve to the same cookie jar, but we canonicalize here to be safe.
    val cookieUrl = remember(baseUrl) {
        val raw = baseUrl.trim().trimEnd('/')
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
    }

    fun extractCfClearance(): String? {
        // CookieManager.getCookie returns "name=value; name2=value2" for the given URL, or null.
        // We split on ";" and look for cf_clearance=. The cookie value itself can contain
        // URL-safe characters but never ";" or ",", so this parse is safe.
        val cookies = CookieManager.getInstance().getCookie(cookieUrl) ?: return null
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

    // Poll the WebView's CookieManager for the cf_clearance cookie every 500ms. We do NOT rely
    // solely on WebViewClient.onPageFinished because:
    //   - Cloudflare's interstitial itself fires onPageFinished (so the first call sees no
    //     cf_clearance and we'd dismiss too early if we trusted it),
    //   - the cookie may be set via JS without a navigation,
    //   - CookieManager.flush() is asynchronous — the cookie may not be visible immediately
    //     after onPageFinished returns.
    // Polling catches all of these cases. The polling loop stops as soon as the cookie appears
    // (which calls finishCapture → onDismiss → this composable leaves composition → coroutine
    // is cancelled).
    LaunchedEffect(cookieUrl) {
        // Give the WebView a moment to start loading before we begin polling.
        delay(1_000)
        while (!captureHandled.get()) {
            val cf = extractCfClearance()
            if (!cf.isNullOrBlank()) {
                statusMessage = context.getString(R.string.dabmusic_cf_bypass_success)
                delay(300) // small grace period so CookieManager.flush() lands
                finishCapture()
                break
            }
            delay(500)
        }
    }

    // Safety timeout: if Cloudflare's challenge doesn't resolve within 60s (e.g. the user's
    // network is very slow, or an interactive Turnstile is waiting for a tap the user hasn't
    // noticed), give up and let the user retry. 60s is generous — Cloudflare's managed
    // challenge typically resolves in <5s on residential mobile IPs.
    LaunchedEffect(Unit) {
        delay(60_000)
        if (!captureHandled.get()) {
            statusMessage = context.getString(R.string.dabmusic_cf_bypass_failed)
            delay(1_500)
            finishCapture()
        }
    }

    // Clean up the WebView when the dialog leaves composition. Without this, the WebView keeps
    // running its JS engine and timers in the background (and leaks the activity on configuration
    // changes).
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.apply {
                stopLoading()
                removeAllViews()
                destroy()
            }
            webViewRef = null
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
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
        ) {
            // The WebView — fill the entire dialog so Cloudflare's interactive Turnstile widget
            // (if it appears) is visible AND clickable. The previous version used
            // wrapContentHeight(CenterVertically) which collapsed the WebView to 0 height on
            // first composition, hiding the challenge entirely.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
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
                            // The cf_clearance cookie is bound to (IP, User-Agent). If the
                            // WebView solves the challenge with Android System WebView's default
                            // UA and OkHttp later sends the cookie with a different UA, Cloudflare
                            // rejects the cookie. So we force the WebView to use the SAME UA as
                            // DabMusicAudioProvider's OkHttp client.
                            userAgentString = DabMusicAudioProvider.USER_AGENT
                            // Cloudflare's challenge sometimes checks MediaSource / WebRTC
                            // availability as part of its bot fingerprinting — keeping these on
                            // matches a real browser.
                            mediaPlaybackRequiresUserGesture = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                            // Allow mixed content because Cloudflare's interstitial may load
                            // scripts from challenges.cloudflare.com over HTTPS, but the
                            // redirect target on dabmusic.xyz should also be HTTPS — keep this
                            // off (the default) for safety, but set explicitly so we know.
                            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        }
                        // Accept cookies so Cloudflare can set cf_clearance.
                        CookieManager.getInstance().setAcceptCookie(true)
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                        webViewClient =
                            object : WebViewClient() {
                                override fun onPageFinished(view: WebView, url: String?) {
                                    super.onPageFinished(view, url)
                                    // Don't act on onPageFinished — the polling loop in the
                                    // LaunchedEffect above is the source of truth. We just nudge
                                    // CookieManager to flush its in-memory jar to persistent
                                    // storage so the polling loop sees the cookie sooner.
                                    CookieManager.getInstance().flush()
                                    // Update the status line so the user sees something is
                                    // happening.
                                    if (!captureHandled.get()) {
                                        statusMessage =
                                            context.getString(R.string.dabmusic_cf_bypass_loading)
                                    }
                                }
                            }
                        loadUrl(cookieUrl)
                        webViewRef = this
                    }
                },
            )

            // Status overlay at the top — small, semi-transparent, doesn't block the WebView
            // (Cloudflare's Turnstile widget is usually centered, so a top strip is safe).
            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                    Text(
                        text = stringResource(R.string.dabmusic_cf_bypass_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                    )
                }
            }

            // Loading spinner in the center for the initial page load — disappears once the
            // WebView paints (we can't easily detect that, so we just hide it after 3s).
            var showSpinner by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(3_000)
                showSpinner = false
            }
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier =
                        Modifier
                            .align(Alignment.Center)
                            .size(32.dp),
                    strokeWidth = 3.dp,
                    color = Color.White,
                )
            }
        }
    }
}
