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
 *  - FALLBACK (no client registered yet): the WebView opens the ListenBrainz
 *    login page; the footer lets the user copy their user token out of the
 *    site's settings and finish with one tap (validated against
 *    /1/validate-token). Not single-tap, but it keeps the whole flow inside
 *    the same sign-in sheet instead of a blind paste dialog.
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val LISTENBRAINZ_LOGIN_ROUTE = "settings/listenbrainz/login"

private const val OAUTH_CALLBACK_URI = "archivetune://listenbrainz-auth-callback"
private const val OAUTH_AUTHORIZE_URL = "https://musicbrainz.org/oauth/authorize"
private const val OAUTH_TOKEN_URL = "https://musicbrainz.org/oauth/token"
private const val LISTENBRAINZ_VALIDATE_URL = "https://api.listenbrainz.org/1/validate-token"
private const val LISTENBRAINZ_LOGIN_URL = "https://listenbrainz.org/login/"

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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ListenBrainzLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val handled = remember { AtomicBoolean(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun finishLogin(token: String) {
        scope.launch {
            val userName = withContext(Dispatchers.IO) { validateToken(token) }
            if (userName == null) {
                withContext(Dispatchers.Main) {
                    toast(context.getString(R.string.listenbrainz_login_failed))
                }
                return@launch
            }
            context.dataStore.edit { prefs ->
                prefs[ListenBrainzTokenKey] = token
                prefs[ListenBrainzEnabledKey] = true
            }
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

    // Fallback mode's finisher: the token the user copied out of the site.
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
                stringResource(R.string.listenbrainz_login_subtitle_manual)
            },
        footer =
            if (!oauthConfigured()) {
                {
                    Button(
                        onClick = ::finishFromClipboard,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                    ) {
                        Text(stringResource(R.string.listenbrainz_login_paste_token))
                    }
                }
            } else {
                null
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
                }
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    loadUrl(if (oauthConfigured()) authorizeUrl() else LISTENBRAINZ_LOGIN_URL)
                }
            }
        },
    )
}
