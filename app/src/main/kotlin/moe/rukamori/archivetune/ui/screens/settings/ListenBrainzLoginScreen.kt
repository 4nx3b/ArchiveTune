/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * ListenBrainz web login, modelled on the Libre.fm/Last.fm webauth screens.
 *
 * Two modes, chosen by whether an OAuth client is configured:
 *  - OAUTH (LISTENBRAINZ_CLIENT_ID/SECRET in local.properties or env): the
 *    MusicBrainz OAuth2 code flow — authorize → archivetune:// callback →
 *    code exchange → the access token IS the ListenBrainz user token.
 *  - AUTO TOKEN (the default, no client registered): the WebView opens
 *    https://listenbrainz.org/settings/ — ListenBrainz answers with a 302 to
 *    its real sign-in page (/login/musicbrainz/?next=/settings/; the old
 *    /login/ path is NOT a route of LB's React router and rendered as the
 *    site's own 404 page). The user signs in with their MusicBrainz account,
 *    lands back on /settings/ still signed in, and the page is scraped for
 *    the token automatically: every LB page embeds a
 *    <script id="global-react-props"> JSON blob whose current_user object
 *    carries auth_token (see listenbrainz-server's
 *    webserver/utils.py::get_global_props), and the settings page itself
 *    renders the token into <input id="auth-token">. Either source is enough
 *    — the token is validated against /1/validate-token before it is saved,
 *    so a bad extraction can never "succeed".
 *  A paste-token footer remains as the manual escape hatch.
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.BuildConfig
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.ListenBrainzEnabledKey
import moe.rukamori.archivetune.constants.ListenBrainzTokenKey
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val LISTENBRAINZ_LOGIN_ROUTE = "settings/listenbrainz/login"

private const val OAUTH_CALLBACK_URI = "archivetune://listenbrainz-auth-callback"
private const val OAUTH_AUTHORIZE_URL = "https://musicbrainz.org/oauth2/authorize"
private const val OAUTH_TOKEN_URL = "https://musicbrainz.org/oauth/token"
private const val LISTENBRAINZ_VALIDATE_URL = "https://api.listenbrainz.org/1/validate-token"
private const val LISTENBRAINZ_SETTINGS_URL = "https://listenbrainz.org/settings/"

private val client by lazy {
    OkHttpClient
        .Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
}

private fun oauthConfigured(): Boolean =
    BuildConfig.LISTENBRAINZ_CLIENT_ID.isNotBlank() && BuildConfig.LISTENBRAINZ_CLIENT_SECRET.isNotBlank()

private fun authorizeUrl(): String =
    "$OAUTH_AUTHORIZE_URL" +
        "?response_type=code" +
        "&client_id=${Uri.encode(BuildConfig.LISTENBRAINZ_CLIENT_ID)}" +
        "&redirect_uri=${Uri.encode(OAUTH_CALLBACK_URI)}" +
        "&scope=profile" +
        "&duration=permanent"

/** Returns the validated user name for the token, or null when invalid. */
private fun validateToken(token: String): String? {
    val request =
        Request
            .Builder()
            .url(LISTENBRAINZ_VALIDATE_URL)
            .header("Authorization", "Token $token")
            .get()
            .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val json = JSONObject(response.body?.string().orEmpty())
            if (json.optInt("code") == 200) json.optString("user_name").takeIf { it.isNotBlank() } else null
        }
    }.getOrNull()
}

/** OAuth2 authorization-code → access token (which is the LB user token). */
private fun exchangeCodeForToken(code: String): String? {
    val body =
        FormBody
            .Builder()
            .add("grant_type", "authorization_code")
            .add("client_id", BuildConfig.LISTENBRAINZ_CLIENT_ID)
            .add("client_secret", BuildConfig.LISTENBRAINZ_CLIENT_SECRET)
            .add("code", code)
            .add("redirect_uri", OAUTH_CALLBACK_URI)
            .build()
    val request =
        Request
            .Builder()
            .url(OAUTH_TOKEN_URL)
            .post(body)
            .build()
    return runCatching {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            JSONObject(response.body?.string().orEmpty()).optString("access_token").takeIf { it.isNotBlank() }
        }
    }.getOrNull()
}

/**
 * Runs inside the logged-in listenbrainz.org page and returns the user token
 * as a plain string (or null). evaluateJavascript JSON-encodes the result, so
 * the Kotlin side decodes it with a JSONTokener.
 *
 * Strategy 1: the global-react-props JSON blob every LB page embeds — its
 * current_user.auth_token is the token (server-rendered by
 * webserver/utils.py). Strategy 2: the settings page's own
 * <input id="auth-token"> value.
 */
private const val TOKEN_EXTRACTION_JS = """
(function() {
    function ok(t) { return t && typeof t === 'string' && t.trim().length >= 16 ? t.trim() : null; }
    try {
        var el = document.getElementById('global-react-props');
        if (el) {
            var raw = el.textContent || el.innerHTML || '';
            var props = JSON.parse(raw);
            var t = props && props.current_user && props.current_user.auth_token;
            var found = ok(t);
            if (found) return found;
        }
    } catch (e) { /* fall through */ }
    try {
        var input = document.getElementById('auth-token');
        if (input && input.value) {
            var v = ok(input.value);
            if (v) return v;
        }
    } catch (e) { /* fall through */ }
    return null;
})()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ListenBrainzLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handled = remember { AtomicBoolean(false) }
    // Actionable auth feedback: when the server hard-rejects listens (the
    // classic case is an unverified MetaBrainz email) the banner tells the
    // user exactly what to fix instead of every listen failing silently.
    val authIssue by ListenBrainzManager.authIssueFlow.collectAsStateWithLifecycle()

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishLogin(token: String) {
        if (!handled.compareAndSet(false, true)) return
        scope.launch {
            val userName = withContext(Dispatchers.IO) { validateToken(token) }
            if (userName == null) {
                handled.set(false)
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.listenbrainz_login_failed))
                }
                return@launch
            }
            context.dataStore.edit { prefs ->
                prefs[ListenBrainzTokenKey] = token
                prefs[ListenBrainzEnabledKey] = true
            }
            // New credentials: any old 401 backoff is obsolete.
            ListenBrainzManager.resetAuthState()
            withContext(Dispatchers.Main) {
                toast(context.getString(R.string.listenbrainz_login_success, userName))
                navController.navigateUp()
            }
        }
    }

    fun handleRedirect(url: String?): Boolean {
        if (url == null || !url.startsWith(OAUTH_CALLBACK_URI)) return false
        if (!oauthConfigured()) return false
        if (!handled.compareAndSet(false, true)) return true
        val uri = runCatching { Uri.parse(url) }.getOrNull()
        val code = uri?.getQueryParameter("code")?.trim()
        if (code.isNullOrBlank()) {
            scope.launch {
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.lastfm_login_cancelled))
                    navController.navigateUp()
                }
            }
            return true
        }
        scope.launch {
            val token = withContext(Dispatchers.IO) { exchangeCodeForToken(code) }
            if (token != null) {
                handled.set(false)
                finishLogin(token)
            } else {
                handled.set(false)
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.listenbrainz_login_failed))
                    navController.navigateUp()
                }
            }
        }
        return true
    }

    /** Scrapes the signed-in page for the user token; no-op when logged out. */
    fun tryExtractToken(view: WebView, url: String?) {
        if (handled.get()) return
        val host = runCatching { Uri.parse(url ?: return).host }.getOrNull() ?: return
        if (host != "listenbrainz.org" && host != "www.listenbrainz.org") return
        view.evaluateJavascript(TOKEN_EXTRACTION_JS) { result ->
            if (handled.get() || result == null || result == "null") return@evaluateJavascript
            val token = runCatching { JSONTokener(result).nextValue() as? String }.getOrNull()
            if (!token.isNullOrBlank() && token.length >= 16) {
                finishLogin(token)
            }
        }
    }

    // Manual escape hatch: the token the user copied out of the site.
    fun finishFromClipboard() {
        scope.launch {
            val token =
                withContext(Dispatchers.IO) {
                    runCatching {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        clipboard?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                    }.getOrNull()
                }
            if (token.isNullOrBlank() || token.length < 16) {
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.listenbrainz_clipboard_empty))
                }
                return@launch
            }
            finishLogin(token)
        }
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.listenbrainz_login_title),
        subtitle =
            if (oauthConfigured()) {
                stringResource(R.string.listenbrainz_login_subtitle_oauth)
            } else {
                stringResource(R.string.listenbrainz_login_subtitle_auto)
            },
        banner =
            authIssue?.let { issue ->
                {
                    Surface(
                        color = Color(0x33F44336),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = issue,
                            color = Color(0xFFFFB4AB),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            },
        footer = {
            Button(
                onClick = ::finishFromClipboard,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
            ) {
                Text(stringResource(R.string.listenbrainz_login_paste_token))
            }
        },
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView,
                        url: String?,
                        favicon: Bitmap?,
                    ) {
                        handleRedirect(url)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        url: String?,
                    ): Boolean = handleRedirect(url)

                    override fun onPageFinished(
                        view: WebView,
                        url: String?,
                    ) {
                        // Auto flow: any signed-in listenbrainz.org page embeds
                        // the token; the login redirect chain ends back on
                        // /settings/, so this fires exactly once the user is
                        // signed in.
                        tryExtractToken(view, url)
                    }
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    loadUrl(if (oauthConfigured()) authorizeUrl() else LISTENBRAINZ_SETTINGS_URL)
                }
            }
        },
    )
}
