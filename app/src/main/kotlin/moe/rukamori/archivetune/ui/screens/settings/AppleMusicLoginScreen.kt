/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * WebView-based Apple Music sign-in with automatic token capture.
 *
 * The Music User Token is captured from BOTH places the web player puts it:
 * the `media-user-token` cookie on the music.apple.com origin (the primary
 * source — the web player writes it there right after the Apple ID handshake,
 * which is why the localStorage-only probe used to miss it and tokens were
 * never fetched right after signing in) and localStorage (the web app also
 * mirrors it there once the player SPA boots). Every candidate is handed to
 * the AMP API for verification (`/v1/me/storefront` answers 200 only for a
 * valid pairing), and the winner is persisted (plus a developer token when
 * the user hasn't pasted one — scraped from the web player, honouring the
 * "optional" help text). Mirrors the DeezerLoginScreen finishLogin shape:
 * verify, persist, toast.
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.datastore.preferences.core.edit
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.applemusic.AppleMusicAudioProvider
import moe.rukamori.archivetune.canvas.AppleMusicProvider
import moe.rukamori.archivetune.constants.AppleMusicDevTokenKey
import moe.rukamori.archivetune.constants.AppleMusicMediaUserTokenKey
import moe.rukamori.archivetune.constants.AppleMusicSourceEnabledKey
import moe.rukamori.archivetune.ui.component.AuthWebViewScreen
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.get
import moe.rukamori.archivetune.utils.resetAuthWebViewSession
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONArray

const val APPLE_MUSIC_LOGIN_ROUTE = "settings/applemusic/login"

private const val LOGIN_URL = "https://music.apple.com/login"
private const val COOKIE_ORIGIN = "https://music.apple.com"
private const val TAG = "AppleMusicLogin"

/**
 * Shape shared by every capture path: a JWT (three dot-separated base64url
 * segments, `eyJ…`) or the classic `0.` + base64 media-user-token. Length is
 * bounded the same way as the in-page probe so both paths agree on what a
 * token looks like.
 */
private fun looksLikeMediaUserToken(value: String?): Boolean {
    if (value.isNullOrBlank()) return false
    if (value.length < 40 || value.length > 4096) return false
    return MEDIA_TOKEN_JWT_REGEX.matches(value) || MEDIA_TOKEN_CLASSIC_REGEX.matches(value)
}

private val MEDIA_TOKEN_JWT_REGEX = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")
private val MEDIA_TOKEN_CLASSIC_REGEX = Regex("^0\\.[A-Za-z0-9+/=]{40,}$")

/**
 * Collects every localStorage value that looks like a media-user-token
 * (a JWT or the classic `0.` + base64 shape), skipping obvious developer /
 * player tokens. Returns a JSON array (string[]) so evaluateJavascript's
 * JSON-formatted callback result can be parsed directly.
 */
private const val TOKEN_PROBE_JS = """
(function(){
  function ok(v){
    if(!v||typeof v!=='string')return false;
    if(v.length<40||v.length>4096)return false;
    return /^eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$/.test(v)
      || /^0\.[A-Za-z0-9+\/=]{40,}$/.test(v);
  }
  var out=[];
  try{
    var d=localStorage.getItem('media-user-token');
    if(ok(d)) out.push(d);
  }catch(e){}
  try{
    for(var i=0;i<localStorage.length;i++){
      var k=localStorage.key(i); if(!k)continue;
      var lk=k.toLowerCase();
      if(lk.indexOf('token')===-1)continue;
      if(lk.indexOf('developer')>=0||lk.indexOf('dev-')>=0||lk.indexOf('devtoken')>=0
        ||lk.indexOf('amtv')>=0||lk.indexOf('jwt')>=0||lk.indexOf('media-user-token')>=0)continue;
      var v=localStorage.getItem(k);
      if(ok(v)) out.push(v);
      else if(v&&v.length<2000&&v.charAt(0)==='{'){
        try{
          var j=JSON.parse(v);
          for(var key in j){ if(ok(j[key])) out.push(j[key]); }
        }catch(e2){}
      }
    }
  }catch(e){}
  return out;
})()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AppleMusicLoginScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val handled = remember { AtomicBoolean(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun readSessionCookie(): Boolean =
        CookieManager
            .getInstance()
            .getCookie(COOKIE_ORIGIN)
            ?.split(';')
            ?.any { it.trim().startsWith("its.pod=", ignoreCase = true) || it.trim().startsWith("pxro=", ignoreCase = true) }
            ?: false

    /**
     * The primary capture path: the `media-user-token` cookie on the
     * music.apple.com origin. CookieManager sees every cookie the WebView
     * stored (including HttpOnly ones, which the in-page JS probe can never
     * read), and the web player writes this cookie directly after the Apple ID
     * handshake — usually before any localStorage mirror exists. The cookie
     * value may arrive URI-encoded, so the decoded form is accepted too.
     */
    fun readMediaUserTokenCookie(): String? {
        val jar = CookieManager.getInstance().getCookie(COOKIE_ORIGIN) ?: return null
        for (raw in jar.split(';')) {
            val entry = raw.trim()
            val eq = entry.indexOf('=')
            if (eq <= 0) continue
            if (!entry.substring(0, eq).equals("media-user-token", ignoreCase = true)) continue
            val value = entry.substring(eq + 1).trim().trim('"')
            if (looksLikeMediaUserToken(value)) return value
            val decoded = runCatching { android.net.Uri.decode(value) }.getOrDefault(value)
            if (looksLikeMediaUserToken(decoded)) return decoded
        }
        return null
    }

    fun finishLogin(mediaToken: String) {
        if (!handled.compareAndSet(false, true)) return
        scope.launch {
            val devToken =
                withContext(Dispatchers.IO) {
                    val pasted = context.dataStore.get(AppleMusicDevTokenKey, "").trim()
                    if (pasted.isNotBlank()) {
                        pasted
                    } else {
                        // Honour the "Developer token is optional" promise: use the
                        // app-scraped web-player JWT so playback can engage without a
                        // manually pasted developer token.
                        runCatching { AppleMusicProvider.currentDevToken() }.getOrNull()
                    }
                }

            val verified =
                devToken != null &&
                    withContext(Dispatchers.IO) {
                        AppleMusicAudioProvider.verifyTokens(mediaToken, devToken)
                    }

            if (!verified) {
                Log.w(TAG, "media-user-token captured but verification failed; not persisting")
                handled.set(false)
                toast(context.getString(R.string.applemusic_login_failed))
                return@launch
            }

            context.dataStore.edit { prefs ->
                prefs[AppleMusicMediaUserTokenKey] = mediaToken
                if (devToken.isNotBlank()) prefs[AppleMusicDevTokenKey] = devToken
                prefs[AppleMusicSourceEnabledKey] = true
            }
            toast(context.getString(R.string.applemusic_login_success))
            navController.navigateUp()
        }
    }

    fun probeForToken(view: WebView) {
        if (handled.get()) return
        // Cookie first: it is written by the Apple ID handshake itself and does
        // not depend on the web app's JS booting, so it is available the moment
        // the sign-in completes (the localStorage mirror only appears once the
        // player SPA initialises — which is why the old probe missed the token
        // right after an automatic sign-in).
        readMediaUserTokenCookie()?.let { token ->
            if (!handled.get()) {
                finishLogin(token)
                return
            }
        }
        view.evaluateJavascript(TOKEN_PROBE_JS) { result ->
            if (result == null || result == "null" || result == "[]") return@evaluateJavascript
            val candidates =
                runCatching { JSONArray(result) }.getOrNull() ?: return@evaluateJavascript
            val mediaToken =
                (0 until candidates.length())
                    .mapNotNull { i -> candidates.optString(i).takeIf { it.isNotBlank() } }
                    .firstOrNull()
            if (mediaToken != null && !handled.get()) {
                finishLogin(mediaToken)
            }
        }
    }

    // The token can appear without any navigation once the web player boots
    // after the Apple ID handshake, so polling is the only reliable trigger.
    // The cookie capture is not gated on the session cookies: the
    // media-user-token cookie IS the proof the handshake finished, so it is
    // checked on every tick even while its.pod/pxro are still settling.
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            webView?.let { view ->
                if (!handled.get()) {
                    if (readMediaUserTokenCookie() != null || readSessionCookie()) {
                        view.post { probeForToken(view) }
                    }
                }
            }
        }
    }

    AuthWebViewScreen(
        navController = navController,
        title = stringResource(R.string.applemusic_login),
        subtitle = stringResource(R.string.applemusic_login_subtitle),
        factory = { ctx ->
            WebView(ctx).apply {
                webView = this
                webViewClient =
                    object : WebViewClient() {
                        override fun onPageFinished(
                            view: WebView,
                            url: String?,
                        ) {
                            if (url == null || !url.startsWith("https://music.apple.com")) return
                            if (!readSessionCookie() && readMediaUserTokenCookie() == null) return
                            probeForToken(view)
                        }
                    }
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                resetAuthWebViewSession(ctx, this, clearCookies = true) {
                    loadUrl(LOGIN_URL)
                }
            }
        },
    )
}
