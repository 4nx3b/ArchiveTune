/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.appicon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.rukamori.archivetune.MainActivity
import moe.rukamori.archivetune.R
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

data class AppIcon(
    val id: String,
    val name: String?,
    val author: String?,
    val githubAuthorUrl: String?,
    @DrawableRes val previewDrawableResId: Int,
    val previewFilePath: String? = null,
    val aliasClassName: String,
    val isDefault: Boolean,
    val runtime: Boolean = false,
)

data class AppIconCatalog(
    val icons: List<AppIcon>,
    val selectedIconId: String,
)

@Serializable
private data class GeneratedAppIcon(
    val id: String,
    val name: String,
    val author: String,
    val githubAuthorUrl: String = "",
    val source: String,
    val drawableResourceName: String,
    val aliasClassName: String,
)

@Singleton
class AppIconRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        private val packageManager: PackageManager = context.packageManager
        private val json = Json { ignoreUnknownKeys = true }

        suspend fun loadCatalog(): AppIconCatalog =
            withContext(Dispatchers.IO) {
                val icons = loadIcons()
                val aliasIcons = icons.filterNot { it.runtime }
                val selectedIcon =
                    if (aliasIcons.isNotEmpty()) {
                        findSelectedIcon(icons)
                    } else {
                        findSelectedRuntimeIcon(icons)
                    }
                if (aliasIcons.isNotEmpty() && !isSelectionApplied(aliasIcons, selectedIcon)) {
                    applySelection(icons, selectedIcon)
                }
                AppIconCatalog(
                    icons = icons,
                    selectedIconId = selectedIcon.id,
                )
            }

        suspend fun selectIcon(iconId: String): AppIconCatalog =
            withContext(Dispatchers.IO + NonCancellable) {
                val icons = loadIcons()
                val selectedIcon =
                    icons.firstOrNull { icon -> icon.id == iconId }
                        ?: throw IllegalArgumentException("Unknown app icon ID.")
                if (selectedIcon.runtime) {
                    applyRuntimeSelection(selectedIcon)
                } else if (!isSelectionApplied(icons.filterNot { it.runtime }, selectedIcon)) {
                    applySelection(icons, selectedIcon)
                }
                AppIconCatalog(
                    icons = icons,
                    selectedIconId = selectedIcon.id,
                )
            }

        private fun loadIcons(): List<AppIcon> {
            val generatedIcons = loadGeneratedIcons()

            return buildList(generatedIcons.size + 1) {
                add(
                    AppIcon(
                        id = DefaultIconId,
                        name = null,
                        author = null,
                        githubAuthorUrl = null,
                        previewDrawableResId = R.drawable.app_icon_small,
                        aliasClassName = "${context.packageName}.launcher.DefaultIconAlias",
                        isDefault = true,
                    ),
                )
                addAll(generatedIcons)
            }
        }

        /** Icons from the baked-in asset catalog (non-slim builds only). */
        private fun loadBundledIcons(): List<AppIcon> =
            context.assets
                .open(CatalogAssetPath)
                .bufferedReader()
                .use { reader -> json.decodeFromString<List<GeneratedAppIcon>>(reader.readText()) }
                .map { generated ->
                    val drawableResId =
                        context.resources.getIdentifier(
                            generated.drawableResourceName,
                            "drawable",
                            context.packageName,
                        )
                    check(drawableResId != 0) {
                        "Missing generated drawable ${generated.drawableResourceName} for ${generated.source}."
                    }
                    AppIcon(
                        id = generated.id,
                        name = generated.name,
                        author = generated.author,
                        githubAuthorUrl = generated.githubAuthorUrl.takeIf(String::isNotBlank),
                        previewDrawableResId = drawableResId,
                        aliasClassName = generated.aliasClassName,
                        isDefault = false,
                    )
                }

        /** Icons from the runtime-downloaded pack (slim builds). */
        private fun loadRuntimeIcons(): List<AppIcon> {
            val catalog = IconPackRuntimeManager.catalogFile(context)
            if (!catalog.isFile) return emptyList()
            val entries =
                runCatching {
                    catalog.bufferedReader().use { reader ->
                        json.decodeFromString<List<GeneratedAppIcon>>(reader.readText())
                    }
                }.getOrNull() ?: return emptyList()
            return entries.mapNotNull { generated ->
                val iconFile =
                    IconPackRuntimeManager.iconFile(context, generated.drawableResourceName)
                if (!iconFile.isFile) return@mapNotNull null
                AppIcon(
                    id = generated.id,
                    name = generated.name,
                    author = generated.author,
                    githubAuthorUrl = generated.githubAuthorUrl.takeIf(String::isNotBlank),
                    previewDrawableResId = 0,
                    previewFilePath = iconFile.absolutePath,
                    aliasClassName = "",
                    isDefault = false,
                    runtime = true,
                )
            }
        }

        private fun loadGeneratedIcons(): List<AppIcon> =
            if (IconPackRuntimeManager.isBundled()) {
                loadBundledIcons()
            } else {
                loadRuntimeIcons()
            }

        private fun findSelectedIcon(icons: List<AppIcon>): AppIcon =
            icons.firstOrNull { icon ->
                packageManager.getComponentEnabledSetting(icon.componentName()) ==
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            }
                ?: icons.first { icon -> icon.id == DefaultIconId }

        private fun isSelectionApplied(
            icons: List<AppIcon>,
            selectedIcon: AppIcon,
        ): Boolean =
            icons.count(::isEffectivelyEnabled) == 1 &&
                isEffectivelyEnabled(selectedIcon)

        private fun isEffectivelyEnabled(icon: AppIcon): Boolean =
            when (packageManager.getComponentEnabledSetting(icon.componentName())) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon.isDefault
                else -> false
            }

        private fun applySelection(
            icons: List<AppIcon>,
            selectedIcon: AppIcon,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.setComponentEnabledSettings(
                    icons.map { icon ->
                        PackageManager.ComponentEnabledSetting(
                            icon.componentName(),
                            if (icon.id == selectedIcon.id) {
                                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                            } else {
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                            },
                            PackageManager.DONT_KILL_APP,
                        )
                    },
                )
            } else {
                val previousStates =
                    icons.associateWith { icon ->
                        packageManager.getComponentEnabledSetting(icon.componentName())
                    }
                try {
                    packageManager.setComponentEnabledSetting(
                        selectedIcon.componentName(),
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP,
                    )
                    icons
                        .asSequence()
                        .filterNot { icon -> icon.id == selectedIcon.id }
                        .forEach { icon ->
                            packageManager.setComponentEnabledSetting(
                                icon.componentName(),
                                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                                PackageManager.DONT_KILL_APP,
                            )
                        }
                } catch (error: RuntimeException) {
                    previousStates.forEach { (icon, state) ->
                        runCatching {
                            packageManager.setComponentEnabledSetting(
                                icon.componentName(),
                                state,
                                PackageManager.DONT_KILL_APP,
                            )
                        }
                    }
                    throw error
                }
            }

            check(isSelectionApplied(icons, selectedIcon)) {
                "Unable to apply launcher icon ${selectedIcon.id} exclusively."
            }
        }

        private fun AppIcon.componentName(): ComponentName = ComponentName(context.packageName, aliasClassName)

        // ── Runtime (pinned-shortcut) selection ──
        //
        // Slim builds ship no per-icon activity-aliases, so launcher switching
        // uses a pinned home-screen shortcut whose adaptive icon is built from
        // the downloaded pack bitmap — the only permissionless way to change a
        // launcher icon whose resources are not compiled into the APK.

        private fun findSelectedRuntimeIcon(icons: List<AppIcon>): AppIcon {
            val selectedId = runtimeSelectionPrefs().getString(KEY_RUNTIME_SELECTED, null) ?: DefaultIconId
            return icons.firstOrNull { it.id == selectedId } ?: icons.first { it.id == DefaultIconId }
        }

        private fun runtimeSelectionPrefs() =
            context.getSharedPreferences("icon_pack_runtime", Context.MODE_PRIVATE)

        private fun applyRuntimeSelection(selectedIcon: AppIcon) {
            val prefs = runtimeSelectionPrefs()
            if (!selectedIcon.isDefault && !IconPackRuntimeManager.supportsPinnedShortcuts()) {
                throw IllegalStateException("This Android version does not support pinned shortcuts")
            }

            if (selectedIcon.isDefault) {
                // Back to the baked-in default alias: remove every pinned icon
                // shortcut we own; the regular launcher entry takes over again.
                runCatching { removeIconShortcuts() }
                prefs.edit().putString(KEY_RUNTIME_SELECTED, DefaultIconId).apply()
                return
            }

            val bitmap =
                selectedIcon.previewFilePath?.let { path ->
                    runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
                }
            if (bitmap == null) throw IllegalStateException("Icon bitmap for ${selectedIcon.id} is missing")

            val shortcutId = iconShortcutId(selectedIcon.id)
            val intent =
                Intent(context, MainActivity::class.java).apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
            val info =
                ShortcutInfoCompat
                    .Builder(context, shortcutId)
                    .setShortLabel(context.getString(R.string.app_name))
                    .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                    .setIntent(intent)
                    .build()
            val pinned = ShortcutManagerCompat.requestPinShortcut(context, info, null)
            if (!pinned) {
                throw IllegalStateException("The launcher refused the icon shortcut — is pinning supported?")
            }
            prefs.edit().putString(KEY_RUNTIME_SELECTED, selectedIcon.id).apply()
        }

        private fun iconShortcutId(iconId: String) = "app_icon_$iconId"

        private fun removeIconShortcuts() {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .filter { it.id.startsWith("app_icon_") }
                .forEach { shortcut ->
                    // Pinned shortcuts cannot be removed programmatically —
                    // disabling greys them out and frees the launcher slot.
                    ShortcutManagerCompat.disableShortcuts(
                        context,
                        listOf(shortcut.id),
                        context.getString(R.string.app_name),
                    )
                    ShortcutManagerCompat.removeLongLivedShortcuts(context, listOf(shortcut.id))
                }
        }

        private companion object {
            const val CatalogAssetPath = "icon_pack/catalog.json"
            const val DefaultIconId = "default"
            const val KEY_RUNTIME_SELECTED = "selected_icon_id"
        }
    }
