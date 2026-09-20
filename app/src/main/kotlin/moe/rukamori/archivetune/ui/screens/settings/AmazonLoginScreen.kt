/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based Amazon Music sign-in. Mirrors DeezerLoginScreen's cookie-capture pattern: the
 * credential is the `at-main` (or its session-scoped sibling `sess-at-main`) cookie Amazon sets on
 * a signed-in browser, read through CookieManager because the cookie is HttpOnly.
 *
 * Unlike Deezer's `arl`, Amazon does not hand this cookie to anonymous visitors — it only appears
 * once sign-in completes — so presence is already closer to proof than Deezer's cookie is. There is
 * still no lightweight Amazon Music account endpoint this fork can safely call to confirm it: doing
 * that for real would mean shipping a genuine Amazon Music API client, and this source cannot play
 * anything back regardless (see AmazonSettings' notice — Amazon serves CENC-protected streams this
 * fork deliberately does not ship a decryption step for). So verification here is shape-only, the
 * same choice docs/source-logins.md documents for Apple Music's Music User Token: reject a value
 * that is obviously not a real cookie (blank, or far too short to be one) and accept the rest.
 *
 * SECURITY: the captured session is never logged and never rendered — only a generic display name
 * is stored for the settings row, matching how Deezer keeps the `arl` itself out of the UI.
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.AmazonAccountNameKey
import moe.rukamori.archivetune.constants.AmazonEnabledKey
import moe.rukamori.archivetune.constants.AmazonSessionKey
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean

const val AMAZON_LOGIN_ROUTE = "settings/amazon/login"

private const val LOGIN_URL = "https://music.amazon.com"

private const val COOKIE_ORIGIN = "https://www.amazon.com"

private val SESSION_COOKIE_NAMES = listOf("at-main", "sess-at-main")

private const val MIN_SESSION_LENGTH = 16

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AmazonLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val handled = remember { AtomicBoolean(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun readSession(): String? {
        val cookieHeader = CookieManager.getInstance().getCookie(COOKIE_ORIGIN) ?: return null
        val cookies =
            cookieHeader
                .split(';')
                .mapNotNull { part ->
                    val (name, value) =
                        part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                    name.trim() to value.trim()
                }
        return SESSION_COOKIE_NAMES.firstNotNullOfOrNull { wanted ->
            cookies.firstOrNull { (name, _) -> name.equals(wanted, ignoreCase = true) }?.second?.takeIf { it.isNotEmpty() }
        }
    }

    fun finishLogin(session: String) {
        if (session.length < MIN_SESSION_LENGTH) {

            handled.set(false)
            toast(context.getString(R.string.amazon_login_invalid_session))
            return
        }
        scope.launch {
            context.dataStore.edit { prefs ->
                prefs[AmazonSessionKey] = session

                prefs[AmazonAccountNameKey] = context.getString(R.string.amazon_account_name_generic)

                prefs[AmazonEnabledKey] = true
            }
            toast(context.getString(R.string.amazon_login_success))
            navController.navigateUp()
        }
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.amazon_login),
        subtitle = stringResource(R.string.auth_webview_amazon_subtitle),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {

                            val session = readSession() ?: return
                            if (!handled.compareAndSet(false, true)) return
                            finishLogin(session)
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
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    loadUrl(LOGIN_URL)
                }
            }
        },
    )
}
