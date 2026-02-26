/*
 * ArchiveTune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package moe.koiverse.archivetune.viewmodels

import android.annotation.SuppressLint
import android.app.Application
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.koiverse.archivetune.innertube.YouTube
import moe.koiverse.archivetune.innertube.utils.PoTokenGenerator
import moe.koiverse.archivetune.utils.reportException
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

sealed interface PoTokenState {
    data object Idle : PoTokenState
    data object Loading : PoTokenState

    data class Success(
        val gvsToken: String,
        val playerToken: String,
        val visitorData: String,
    ) : PoTokenState

    data class Error(val message: String) : PoTokenState
}

data class WebViewExtraction(
    val visitorData: String? = null,
    val poToken: String? = null,
)

@HiltViewModel
class PoTokenViewModel
@Inject
constructor(
    application: Application,
) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<PoTokenState>(PoTokenState.Idle)
    val state: StateFlow<PoTokenState> = _state.asStateFlow()

    fun generateTokens(sourceUrl: String?, useVisitorData: Boolean) {
        viewModelScope.launch {
            _state.value = PoTokenState.Loading

            try {
                val url = sourceUrl?.takeIf { it.isNotBlank() }
                val extraction = if (url != null) {
                    extractTokensFromUrl(url)
                } else {
                    null
                }

                val visitorData = resolveVisitorData(extraction, useVisitorData)

                if (visitorData.isNullOrBlank()) {
                    _state.value = PoTokenState.Error("No visitor data available. Tokens cannot be generated.")
                    return@launch
                }

                val gvsToken = extraction?.poToken
                    ?: PoTokenGenerator.generateSessionToken(visitorData)
                val playerToken = PoTokenGenerator.generateColdStartToken(visitorData, "player")

                _state.value = PoTokenState.Success(
                    gvsToken = gvsToken,
                    playerToken = playerToken,
                    visitorData = visitorData,
                )
            } catch (e: Exception) {
                reportException(e)
                _state.value = PoTokenState.Error(
                    e.message ?: "Token generation failed"
                )
            }
        }
    }

    fun regenerate(sourceUrl: String?, useVisitorData: Boolean) {
        _state.value = PoTokenState.Idle
        generateTokens(sourceUrl, useVisitorData)
    }

    fun resetState() {
        _state.value = PoTokenState.Idle
    }

    private suspend fun resolveVisitorData(
        extraction: WebViewExtraction?,
        useVisitorData: Boolean,
    ): String? {
        extraction?.visitorData?.takeIf { it.isNotBlank() }?.let { return it }

        if (useVisitorData) {
            YouTube.visitorData?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return withContext(Dispatchers.IO) {
            YouTube.visitorData()
                .onFailure { reportException(it) }
                .getOrNull()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun extractTokensFromUrl(url: String): WebViewExtraction =
        withContext(Dispatchers.Main) {
            suspendCoroutine { continuation ->
                val context = getApplication<Application>()
                val result = WebViewExtraction()
                var visitorData: String? = null
                var poToken: String? = null
                var resumed = false

                val webView = WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onVisitorData(data: String?) {
                            if (!data.isNullOrBlank()) {
                                visitorData = data
                            }
                            completeIfReady()
                        }

                        @JavascriptInterface
                        fun onPoToken(token: String?) {
                            if (!token.isNullOrBlank()) {
                                poToken = token
                            }
                            completeIfReady()
                        }

                        private fun completeIfReady() {
                            if (!resumed && (visitorData != null || poToken != null)) {
                                resumed = true
                                continuation.resume(
                                    WebViewExtraction(
                                        visitorData = visitorData,
                                        poToken = poToken,
                                    )
                                )
                            }
                        }
                    }, "TokenExtractor")

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, pageUrl: String?) {
                            view.loadUrl(
                                "javascript:void((function(){" +
                                    "try{var vd=window.yt&&window.yt.config_&&window.yt.config_.VISITOR_DATA;" +
                                    "if(vd){TokenExtractor.onVisitorData(vd)}" +
                                    "else{TokenExtractor.onVisitorData('')}}catch(e){TokenExtractor.onVisitorData('')}" +
                                    "try{var c=window.ytcfg;if(c&&c.get){var t=c.get('PO_TOKEN');" +
                                    "if(t){TokenExtractor.onPoToken(t);return}}" +
                                    "var s=document.querySelectorAll('script');" +
                                    "for(var i=0;i<s.length;i++){var m=s[i].textContent.match(/\"PO_TOKEN\":\"([^\"]+)\"/);" +
                                    "if(m){TokenExtractor.onPoToken(m[1]);return}}" +
                                    "TokenExtractor.onPoToken('')}catch(e){TokenExtractor.onPoToken('')}" +
                                    "})())"
                            )

                            view.postDelayed({
                                if (!resumed) {
                                    resumed = true
                                    continuation.resume(
                                        WebViewExtraction(
                                            visitorData = visitorData,
                                            poToken = poToken,
                                        )
                                    )
                                }
                            }, 8000)
                        }
                    }

                    loadUrl(url)
                }

                webView.postDelayed({
                    if (!resumed) {
                        resumed = true
                        webView.stopLoading()
                        webView.destroy()
                        continuation.resume(result)
                    }
                }, 15000)
            }
        }
}
