/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 */

@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package moe.rukamori.archivetune.ui.screens.settings

import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import moe.rukamori.archivetune.R
import moe.rukamori.archivetune.ui.component.DefaultDialog
import moe.rukamori.archivetune.ui.theme.CustomFontLoader
import moe.rukamori.archivetune.ui.theme.SfProFontCatalog

@Composable
fun SfProFontPickerDialog(
    onDismiss: () -> Unit,
    onApply: (uri: String, displayName: String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<List<SfProFontCatalog.FontEntry>?>(null) }
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var downloadingName by remember { mutableStateOf<String?>(null) }
    var downloadFailedName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        failed = false
        catalog = SfProFontCatalog.fetchCatalog()
        loading = false
        if (catalog == null) failed = true
    }

    fun downloadAndApply(entry: SfProFontCatalog.FontEntry) {
        if (downloadingName != null) return
        downloadFailedName = null
        downloadingName = entry.name
        scope.launch {
            val bytes = SfProFontCatalog.downloadFont(entry)
            val uri =
                if (bytes != null) {
                    CustomFontLoader.applyDownloadedFont(
                        context.applicationContext,
                        bytes,
                        entry.fileName ?: entry.url.substringAfterLast('/'),
                    )
                } else {
                    null
                }
            downloadingName = null
            if (uri == null) {
                downloadFailedName = entry.name
            } else {
                onApply(uri, entry.name)
                onDismiss()
            }
        }
    }

    DefaultDialog(
        onDismiss = onDismiss,
        buttons = {
            TextButton(onClick = onDismiss, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(android.R.string.cancel))
            }
        },
    ) {
        Column(modifier = Modifier.padding(top = 4.dp)) {
            Text(
                text = stringResource(R.string.sf_pro_fonts),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))

            when {
                loading -> {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                failed -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 160.dp)
                                .padding(vertical = 24.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sf_pro_error),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TextButton(onClick = {
                            loading = true
                            failed = false
                            scope.launch {
                                catalog = SfProFontCatalog.fetchCatalog()
                                loading = false
                                if (catalog == null) failed = true
                            }
                        }) {
                            Text(stringResource(R.string.sf_pro_retry))
                        }
                    }
                }
                else -> {
                    val entries = catalog.orEmpty()
                    val grouped =
                        entries
                            .groupBy { it.family ?: "" }
                            .entries
                            .sortedBy { (family, _) -> family }
                    LazyColumn(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(max = 420.dp),
                    ) {
                        grouped.forEach { (family, fonts) ->
                            item(key = "family:$family") {
                                Text(
                                    text = family.ifBlank { stringResource(R.string.sf_pro_fonts) },
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                                )
                            }
                            items(fonts, key = { it.url }) { entry ->
                                SfProFontRow(
                                    entry = entry,
                                    downloading = downloadingName == entry.name,
                                    failed = downloadFailedName == entry.name,
                                    onClick = { downloadAndApply(entry) },
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SfProFontRow(
    entry: SfProFontCatalog.FontEntry,
    downloading: Boolean,
    failed: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick, enabled = !downloading)
                .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details =
                buildList {
                    entry.type?.let { add(it.replaceFirstChar { it.uppercase() }) }
                    entry.weight?.let { add(it) }
                    if (entry.style == "italic") add("italic")
                    entry.format?.let { add(it.uppercase()) }
                }.joinToString("  ")
            val failedText =
                if (failed) {
                    "  " + stringResource(R.string.sf_pro_download_failed)
                } else {
                    ""
                }
            Text(
                text = details + failedText,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (failed) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (downloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
            )
        } else {
            IconButton(onClick = onClick) {
                Icon(
                    painter = painterResource(R.drawable.solar_download_minimalistic_linear),
                    contentDescription = entry.name,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
