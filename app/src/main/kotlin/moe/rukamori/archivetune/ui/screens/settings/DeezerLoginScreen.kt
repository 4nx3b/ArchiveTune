/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based Deezer sign-in. Deezer has no OAuth flow we can use, so the credential is the `arl`
 * session cookie the site sets on a signed-in browser. Mirrors the [TidalLoginScreen] WebView
 * pattern and persists the cookie to DataStore.
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DeezerAccountNameKey
import moe.rukamori.archivetune.constants.DeezerAccountPremiumKey
import moe.rukamori.archivetune.constants.DeezerArlKey
import moe.rukamori.archivetune.constants.DeezerEnabledKey
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean

const val DEEZER_LOGIN_ROUTE = "settings/deezer/login"

private const val LOGIN_URL = "https://www.deezer.com/login"

private const val COOKIE_ORIGIN = "https://www.deezer.com"

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DeezerLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val handled = remember { AtomicBoolean(false) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun readArl(): String? =
        CookieManager
            .getInstance()
            .getCookie(COOKIE_ORIGIN)
            ?.split(';')
            ?.firstNotNullOfOrNull { part ->
                val (name, value) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return@firstNotNullOfOrNull null
                value.trim().takeIf { name.trim().equals("arl", ignoreCase = true) && it.isNotEmpty() }
            }

    fun finishLogin(arl: String) {
        scope.launch {

            val info = withContext(Dispatchers.IO) { DeezerAudioProvider.verifyArl(arl) }
            if (info == null) {

                handled.set(false)
                return@launch
            }
            context.dataStore.edit { prefs ->
                prefs[DeezerArlKey] = arl
                prefs[DeezerAccountNameKey] = info.name
                prefs[DeezerAccountPremiumKey] = info.lossless

                prefs[DeezerEnabledKey] = true
            }

            DeezerAudioProvider.setManualArl(arl, info.lossless)
            toast(context.getString(R.string.deezer_login_success, info.name))
            navController.navigateUp()
        }
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.deezer_login),
        subtitle = stringResource(R.string.auth_webview_deezer_subtitle),
        factory = { ctx ->
            WebView(ctx).apply {
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {

                            val arl = readArl() ?: return
                            if (!handled.compareAndSet(false, true)) return
                            finishLogin(arl)
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
