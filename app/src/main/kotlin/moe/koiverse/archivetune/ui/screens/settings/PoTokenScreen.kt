/*
 * ArchiveTune Project Original (2026)
 * Kòi Natsuko (github.com/koiverse)
 * Licensed Under GPL-3.0 | see git history for contributors
 */

package moe.koiverse.archivetune.ui.screens.settings

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import moe.koiverse.archivetune.LocalPlayerAwareWindowInsets
import moe.koiverse.archivetune.R
import moe.koiverse.archivetune.constants.InnerTubeCookieKey
import moe.koiverse.archivetune.constants.PoTokenGvsKey
import moe.koiverse.archivetune.constants.PoTokenPlayerKey
import moe.koiverse.archivetune.constants.PoTokenSourceUrlKey
import moe.koiverse.archivetune.constants.UseVisitorDataKey
import moe.koiverse.archivetune.constants.VisitorDataKey
import moe.koiverse.archivetune.constants.WebClientPoTokenEnabledKey
import moe.koiverse.archivetune.innertube.utils.PoTokenGenerator
import moe.koiverse.archivetune.ui.component.IconButton
import moe.koiverse.archivetune.ui.component.PreferenceGroupTitle
import moe.koiverse.archivetune.ui.component.SwitchPreference
import moe.koiverse.archivetune.ui.utils.backToMain
import moe.koiverse.archivetune.utils.rememberPreference
import moe.koiverse.archivetune.viewmodels.PoTokenState
import moe.koiverse.archivetune.viewmodels.PoTokenViewModel

private const val DEFAULT_EXTRACT_URL = "https://youtube.com/account"

private val SUPPORTED_CLIENTS = listOf(
    "web", "mweb", "web_safari", "web_embedded", "web_creator", "web_music"
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PoTokenScreen(
    navController: NavController,
    scrollBehavior: TopAppBarScrollBehavior,
    viewModel: PoTokenViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val tokenState by viewModel.state.collectAsState()

    var showWebView by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    var (webClientPoTokenEnabled, onWebClientPoTokenEnabledChange) = rememberPreference(
        WebClientPoTokenEnabledKey,
        defaultValue = false
    )
    var (useVisitorData, onUseVisitorDataChange) = rememberPreference(
        UseVisitorDataKey,
        defaultValue = false
    )
    var (sourceUrl, onSourceUrlChange) = rememberPreference(
        PoTokenSourceUrlKey,
        defaultValue = ""
    )
    var (storedGvsToken, onStoredGvsTokenChange) = rememberPreference(
        PoTokenGvsKey,
        defaultValue = ""
    )
    var (storedPlayerToken, onStoredPlayerTokenChange) = rememberPreference(
        PoTokenPlayerKey,
        defaultValue = ""
    )
    var (storedVisitorData, onStoredVisitorDataChange) = rememberPreference(
        VisitorDataKey,
        defaultValue = ""
    )
    val (innerTubeCookie, _) = rememberPreference(
        InnerTubeCookieKey,
        defaultValue = ""
    )

    val hasCookie = innerTubeCookie.isNotBlank()

    LaunchedEffect(tokenState) {
        when (val state = tokenState) {
            is PoTokenState.Success -> {
                onStoredGvsTokenChange(state.gvsToken)
                onStoredPlayerTokenChange(state.playerToken)
                onStoredVisitorDataChange(state.visitorData)
                Toast.makeText(context, R.string.tokens_generated, Toast.LENGTH_SHORT).show()
            }
            is PoTokenState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }

    val displayGvsToken = when (val s = tokenState) {
        is PoTokenState.Success -> s.gvsToken
        else -> storedGvsToken
    }
    val displayPlayerToken = when (val s = tokenState) {
        is PoTokenState.Success -> s.playerToken
        else -> storedPlayerToken
    }
    val displayVisitorData = when (val s = tokenState) {
        is PoTokenState.Success -> s.visitorData
        else -> storedVisitorData
    }

    if (showWebView) {
        BackHandler {
            webViewRef?.let { wv ->
                if (wv.canGoBack()) {
                    wv.goBack()
                } else {
                    wv.stopLoading()
                    wv.loadUrl("about:blank")
                    showWebView = false
                }
            } ?: run { showWebView = false }
        }

        fun triggerTokenExtraction() {
            val webView = webViewRef ?: return
            val currentUrl = webView.url.orEmpty()
            if (!currentUrl.contains("youtube.com/account")) {
                Toast.makeText(context, R.string.open_account_before_extract, Toast.LENGTH_SHORT).show()
                return
            }

            extractedVisitorData = null
            extractedPoToken = null

            webView.loadUrl("javascript:Android.onRetrieveVisitorData(window.yt.config_.VISITOR_DATA)")
            webView.loadUrl(
                "javascript:void((function(){" +
                    "try{var c=window.ytcfg;if(c&&c.get){var t=c.get('PO_TOKEN');" +
                    "if(t){Android.onRetrievePoToken(t);return}}}" +
                    "catch(e){}" +
                    "try{var s=document.querySelectorAll('script');" +
                    "for(var i=0;i<s.length;i++){" +
                    "var m=s[i].textContent.match(/\"PO_TOKEN\":\"([^\"]+)\"/);" +
                    "if(m){Android.onRetrievePoToken(m[1]);return}}}" +
                    "catch(e){}})())"
            )

            webView.postDelayed({
                if (showWebView && (extractedVisitorData.isNullOrBlank() || extractedPoToken.isNullOrBlank())) {
                    viewModel.onExtractionError(context.getString(R.string.token_generation_failed))
                }
            }, 2500L)
        }

        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier
                    .windowInsetsPadding(LocalPlayerAwareWindowInsets.current)
                    .fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )

                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false

                        addJavascriptInterface(object {
                            @JavascriptInterface
                            fun onRetrieveVisitorData(newVisitorData: String?) {
                                if (!newVisitorData.isNullOrBlank()) {
                                    extractedVisitorData = newVisitorData
                                    tryCompleteExtraction(viewModel) {
                                        showWebView = false
                                    }
                                }
                            }

                            @JavascriptInterface
                            fun onRetrievePoToken(newPoToken: String?) {
                                if (!newPoToken.isNullOrBlank()) {
                                    extractedPoToken = newPoToken
                                    tryCompleteExtraction(viewModel) {
                                        showWebView = false
                                    }
                                }
                            }
                        }, "Android")

                        webViewClient = object : WebViewClient()

                        extractedVisitorData = null
                        extractedPoToken = null

                        val targetUrl = sourceUrl.takeIf { it.isNotBlank() } ?: DEFAULT_EXTRACT_URL
                        loadUrl(targetUrl)
                        webViewRef = this
                    }
                }
            )

            TopAppBar(
                title = { Text(stringResource(R.string.extracting_from_url)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            webViewRef?.stopLoading()
                            webViewRef?.loadUrl("about:blank")
                            showWebView = false
                        },
                        onLongClick = {
                            webViewRef?.stopLoading()
                            webViewRef?.loadUrl("about:blank")
                            showWebView = false
                        }
                    ) {
                        Icon(
                            painterResource(R.drawable.arrow_back),
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { triggerTokenExtraction() },
                        onLongClick = { triggerTokenExtraction() }
                    ) {
                        Icon(
                            painterResource(R.drawable.done),
                            contentDescription = null
                        )
                    }
                }
            )
        }
        return
    }

    Column(
        Modifier
            .windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(
                    WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
                )
            )
            .verticalScroll(rememberScrollState())
            .animateContentSize(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            )
    ) {
        Spacer(
            Modifier.windowInsetsPadding(
                LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Top)
            )
        )

        SwitchPreference(
            title = { Text(stringResource(R.string.web_client_po_token)) },
            description = stringResource(R.string.web_client_po_token_desc),
            icon = { Icon(painterResource(R.drawable.token), null) },
            checked = webClientPoTokenEnabled,
            onCheckedChange = onWebClientPoTokenEnabledChange,
        )

        AnimatedVisibility(
            visible = webClientPoTokenEnabled,
            enter = expandVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ) + fadeIn(),
            exit = shrinkVertically(
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            ) + fadeOut(),
        ) {
            Column {
                PreferenceGroupTitle(
                    title = stringResource(R.string.generated_tokens)
                )

                SelectableTokenCard(
                    label = stringResource(R.string.po_token_gvs),
                    token = displayGvsToken,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(displayGvsToken))
                        Toast.makeText(context, R.string.token_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                SelectableTokenCard(
                    label = stringResource(R.string.po_token_player),
                    token = displayPlayerToken,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(displayPlayerToken))
                        Toast.makeText(context, R.string.token_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                SelectableTokenCard(
                    label = stringResource(R.string.visitor_data),
                    token = displayVisitorData,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(displayVisitorData))
                        Toast.makeText(context, R.string.token_copied, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )

                PreferenceGroupTitle(
                    title = stringResource(R.string.supported_clients)
                )

                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SUPPORTED_CLIENTS.forEach { client ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = client,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            ),
                        )
                    }
                }

                PreferenceGroupTitle(
                    title = stringResource(R.string.token_settings)
                )

                SwitchPreference(
                    title = { Text(stringResource(R.string.use_visitor_data)) },
                    description = stringResource(R.string.use_visitor_data_desc),
                    icon = { Icon(painterResource(R.drawable.person), null) },
                    checked = useVisitorData,
                    onCheckedChange = { enabled ->
                        if (enabled && hasCookie) {
                            Toast.makeText(
                                context,
                                R.string.cookies_must_be_disabled,
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            onUseVisitorDataChange(enabled)
                        }
                    },
                )

                OutlinedTextField(
                    value = sourceUrl,
                    onValueChange = onSourceUrlChange,
                    label = { Text(stringResource(R.string.source_url)) },
                    placeholder = { Text(stringResource(R.string.source_url_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                )

                Spacer(Modifier.height(12.dp))

                Button(
                    onClick = {
                        viewModel.resetState()
                        showWebView = true
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.sync),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.regenerate),
                        style = MaterialTheme.typography.labelLarge,
                    )
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    TopAppBar(
        title = { Text(stringResource(R.string.po_token_generation)) },
        navigationIcon = {
            IconButton(
                onClick = navController::navigateUp,
                onLongClick = navController::backToMain
            ) {
                Icon(
                    painterResource(R.drawable.arrow_back),
                    contentDescription = null
                )
            }
        }
    )
}

private var extractedVisitorData: String? = null
private var extractedPoToken: String? = null

private fun tryCompleteExtraction(
    viewModel: PoTokenViewModel,
    onComplete: () -> Unit,
) {
    val visitorData = extractedVisitorData ?: return
    val poToken = extractedPoToken ?: return

    val playerToken = PoTokenGenerator.generateColdStartToken(visitorData, "player")

    viewModel.onTokensExtracted(
        visitorData = visitorData,
        poToken = poToken,
        playerToken = playerToken,
    )

    extractedVisitorData = null
    extractedPoToken = null
    onComplete()
}

@Composable
private fun SelectableTokenCard(
    label: String,
    token: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(
                        text = token.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                        ),
                        color = if (token.isBlank()) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (token.isNotBlank()) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onCopy,
                    onLongClick = onCopy,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.copy),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
