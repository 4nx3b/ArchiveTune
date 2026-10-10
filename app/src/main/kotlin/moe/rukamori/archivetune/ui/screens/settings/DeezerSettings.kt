/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * Deezer integration settings. Reached from the Integration screen alongside
 * Tidal/Qobuz. Hosts the entry point to the Deezer login flow, which captures
 * an `arl` cookie so the provider can resolve full Premium streams.
 */

package moe.rukamori.archivetune.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.LocalPlayerAwareWindowInsets
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.constants.DeezerAccountNameKey
import moe.rukamori.archivetune.constants.DeezerAccountPremiumKey
import moe.rukamori.archivetune.constants.DeezerArlKey
import moe.rukamori.archivetune.constants.DeezerEnabledKey
import moe.rukamori.archivetune.constants.DeezerInstancesKey
import moe.rukamori.archivetune.deezer.DeezerAudioProvider
import moe.rukamori.archivetune.deezer.DeezerInstances
import moe.rukamori.archivetune.ui.component.FrostedHeaderPill
import moe.rukamori.archivetune.ui.component.IconButton
import moe.rukamori.archivetune.ui.component.PreferenceEntry
import moe.rukamori.archivetune.ui.component.PreferenceGroup
import moe.rukamori.archivetune.ui.component.TextFieldDialog
import moe.rukamori.archivetune.ui.utils.backToMain
import moe.rukamori.archivetune.utils.PoolAccountManager
import moe.rukamori.archivetune.utils.dataStore
import moe.rukamori.archivetune.utils.rememberPreference
import androidx.compose.foundation.layout.asPaddingValues
import moe.rukamori.archivetune.ui.screens.ScreenHeaderHaze
import moe.rukamori.archivetune.ui.screens.rememberScreenHeaderHaze
import moe.rukamori.archivetune.LocalStableSystemBarsTopPadding
import dev.chrisbanes.haze.hazeSource
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import moe.rukamori.archivetune.ui.component.SettingsPageTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeezerSettings(
    navController: NavController,
    scrollTo: String? = null,
) {
    val context = LocalContext.current

    val scope = rememberCoroutineScope()
    val (accountName, onAccountNameChange) = rememberPreference(DeezerAccountNameKey, "")
    val (_, onArlChange) = rememberPreference(DeezerArlKey, "")
    val (_, onPremiumChange) = rememberPreference(DeezerAccountPremiumKey, false)
    val (instancesRaw, onInstancesChange) = rememberPreference(DeezerInstancesKey, "")
    val instanceCount = remember(instancesRaw) { DeezerInstances.parse(instancesRaw).size }
    var showArlDialog by remember { mutableStateOf(false) }
    var showInstancesDialog by remember { mutableStateOf(false) }
    var verifyingArl by remember { mutableStateOf(false) }

    if (showArlDialog) {
        var arlText by remember { mutableStateOf("") }
        TextFieldDialog(
            icon = { Icon(painterResource(R.drawable.key), null) },
            title = { Text(stringResource(R.string.deezer_arl_login)) },
            textFieldValue = arlText,
            onTextFieldValueChange = { arlText = it },
            placeholder = { Text(stringResource(R.string.deezer_arl_placeholder)) },
            masked = true,
            enabled = !verifyingArl,
            dismissOnDone = false,
            isInputValid = { it.trim().removePrefix("arl=").trim().length >= 32 },
            onDone = { raw ->
                val arl = raw.trim().removePrefix("arl=").trim()
                verifyingArl = true
                scope.launch {
                    // Same verification the WebView flow uses: an anonymous or expired ARL still
                    // answers HTTP 200, so only a real USER_ID counts as signed in.
                    val info = withContext(Dispatchers.IO) { DeezerAudioProvider.verifyArl(arl) }
                    verifyingArl = false
                    if (info == null) {
                        Toast.makeText(context, R.string.deezer_arl_invalid, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                    context.dataStore.edit { prefs ->
                        prefs[DeezerArlKey] = arl
                        prefs[DeezerAccountNameKey] = info.name
                        prefs[DeezerAccountPremiumKey] = info.lossless
                        prefs[DeezerEnabledKey] = true
                    }
                    DeezerAudioProvider.setManualArl(arl, info.lossless)
                    Toast
                        .makeText(context, context.getString(R.string.deezer_login_success, info.name), Toast.LENGTH_SHORT)
                        .show()
                    showArlDialog = false
                }
            },
            onDismiss = { if (!verifyingArl) showArlDialog = false },
        )
    }

    if (showInstancesDialog) {
        var instancesText by remember { mutableStateOf(instancesRaw) }
        TextFieldDialog(
            icon = { Icon(painterResource(R.drawable.link), null) },
            title = { Text(stringResource(R.string.deezer_api_instances)) },
            textFieldValue = instancesText,
            onTextFieldValueChange = { instancesText = it },
            placeholder = { Text("https://deezer-instance.example.com") },
            singleLine = false,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            isInputValid = { true },
            onDone = { raw ->
                val normalized = DeezerInstances.parse(raw).joinToString("\n")
                onInstancesChange(normalized)
                DeezerInstances.setUserInstances(normalized)
                if (normalized.isNotEmpty()) {
                    scope.launch { context.dataStore.edit { it[DeezerEnabledKey] = true } }
                }
            },
            onDismiss = { showInstancesDialog = false },
            extraContent = {
                Text(
                    text = stringResource(R.string.deezer_api_instances_hint),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            },
        )
    }

    val headerHaze = rememberScreenHeaderHaze()
    val systemBarsTopPadding = LocalStableSystemBarsTopPadding.current

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
                SettingsPageTopBar(
                    titleText = stringResource(R.string.deezer_integration),
                    onBack = navController::navigateUp,
                    onBackLongClick = navController::backToMain,
                )
            },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
        val playerAwareBottomPadding =
            LocalPlayerAwareWindowInsets.current
                .only(WindowInsetsSides.Bottom)
                .asPaddingValues()
                .calculateBottomPadding()
        val topPadding = innerPadding.calculateTopPadding()
        val scrollState = rememberScrollState()
        val positions = rememberPreferencePositions()
        androidx.compose.runtime.LaunchedEffect(scrollTo) { positions.scrollToKey(scrollTo, scrollState) }

        Column(
            Modifier
                .windowInsetsPadding(LocalPlayerAwareWindowInsets.current.only(WindowInsetsSides.Horizontal))

                .then(positions.containerModifier())
                .verticalScroll(scrollState)
                .hazeSource(headerHaze)
                .padding(top = topPadding)
                .padding(bottom = playerAwareBottomPadding + 16.dp),
        ) {
            PreferenceGroup(
                title = stringResource(R.string.deezer_integration),
            ) {
                if (accountName.isEmpty()) {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("deezer_login"),
                            title = { Text(stringResource(R.string.deezer_login)) },
                            description = stringResource(R.string.deezer_login_description),
                            icon = { Icon(painterResource(R.drawable.provider_deezer), null) },
                            onClick = { navController.navigate(DEEZER_LOGIN_ROUTE) },
                        )
                    }
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("deezer_arl_login"),
                            title = { Text(stringResource(R.string.deezer_arl_login)) },
                            description = stringResource(R.string.deezer_arl_login_description),
                            icon = { Icon(painterResource(R.drawable.key), null) },
                            onClick = { showArlDialog = true },
                        )
                    }
                } else {
                    item {
                        PreferenceEntry(
                            modifier = positions.modifierFor("deezer_sign_out"),
                            title = { Text(stringResource(R.string.deezer_sign_out)) },
                            description = stringResource(R.string.deezer_signed_in_as, accountName),
                            icon = { Icon(painterResource(R.drawable.logout), null) },
                            onClick = {
                                // Push immediately so playback stops using the account without waiting for
                                // the App-level collector (mirrors both sign-in paths).
                                DeezerAudioProvider.setManualArl("", false)
                                // Clearing the ARL is what actually signs out; App.kt's collector observes it
                                // and drops the provider's session. Name/premium are display state only.
                                onArlChange("")
                                onAccountNameChange("")
                                onPremiumChange(false)
                                Toast
                                    .makeText(context, R.string.deezer_signed_out, Toast.LENGTH_SHORT)
                                    .show()
                            },
                        )
                    }
                }
            }

            PreferenceGroup(
                title = stringResource(R.string.deezer_api_group),
            ) {
                item {
                    PreferenceEntry(
                        modifier = positions.modifierFor("deezer_api_instances"),
                        title = { Text(stringResource(R.string.deezer_api_instances)) },
                        description =
                            if (instanceCount == 0) {
                                stringResource(R.string.deezer_api_instances_none)
                            } else {
                                pluralStringResource(R.plurals.deezer_api_instances_count, instanceCount, instanceCount)
                            },
                        icon = { Icon(painterResource(R.drawable.link), null) },
                        onClick = { showInstancesDialog = true },
                    )
                }
                item {
                    PreferenceEntry(
                        title = { Text(stringResource(R.string.deezer_api_pool)) },
                        description =
                            if (PoolAccountManager.isPoolEnabled()) {
                                stringResource(R.string.deezer_api_pool_on)
                            } else {
                                stringResource(R.string.deezer_api_pool_off)
                            },
                        icon = { Icon(painterResource(R.drawable.cloud), null) },
                    )
                }
            }
        }

        ScreenHeaderHaze(
            hazeState = headerHaze,
            systemBarsTopPadding = systemBarsTopPadding,
        )
        }
}
}
