package com.lastwave.app.playback.usb

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

private const val USB_EXCLUSIVE_PREFS_NAME = "usb_exclusive_prefs"

private val Context.usbExclusiveDataStore: DataStore<Preferences> by preferencesDataStore(
    name = USB_EXCLUSIVE_PREFS_NAME,
)

object UsbExclusivePrefs {
    private val KEY_ENABLED = booleanPreferencesKey("usb_exclusive_enabled")

    fun enabledFlow(context: Context): Flow<Boolean> =
        context.applicationContext.usbExclusiveDataStore.data
            .catch { emit(emptyPreferences()) }
            .map { prefs -> prefs[KEY_ENABLED] == true }

    suspend fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.usbExclusiveDataStore.edit { prefs ->
            prefs[KEY_ENABLED] = enabled
        }
    }

    suspend fun isEnabledNow(context: Context, timeoutMs: Long = 500L): Boolean =
        withTimeoutOrNull(timeoutMs) { enabledFlow(context).first() } ?: false
}
