/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.utils

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts credential-like string preferences with an app-private Android Keystore key while
 * preserving the normal Preferences DataStore API for existing callers.
 *
 * Each value uses a fresh AES-GCM IV and the preference name as authenticated additional data, so
 * ciphertext cannot be copied between fields. Non-sensitive values remain readable for settings
 * migrations and diagnostics. If the Keystore key is lost (for example after an unsupported backup
 * restore), encrypted credentials are dropped and the user must sign in again; ciphertext is never
 * returned to callers as though it were a real token.
 */
internal object SensitivePreferenceCrypto {
    private const val KEY_ALIAS = "archivetune_sensitive_preferences_v1"
    private const val PREFIX = "secure:v1:"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private val explicitlySensitiveNames =
        setOf(
            "visitorData",
            "dataSyncId",
            "accountChannelHandle",
            "accountEmail",
            "accountName",
            "lastfmSession",
            "lastfmApiKeyOverride",
            "librefmApiKeyOverride",
            "customScrobbleApiKeyOverride",
            "lastfmUsername",
            "ai_api_key",
            "discordAvatarUrl",
            "discordName",
            "discordUsername",
            "proxyUsername",
            "savedAccounts",
            "spotify_sp_dc",
            "spotify_sp_key",
            "spotify_account_avatar_url",
            "spotify_account_name",
            "spotify_library_playlists_cache",
            "tidal_account_name",
            "together_client_id",
            "together_display_name",
            "together_last_join_link",
            "poolTidalAccounts",
            "poolQobuzAccounts",
        )

    private val secretNameFragments =
        listOf(
            "token",
            "secret",
            "password",
            "cookie",
        )

    private val key: SecretKey by lazy(::loadOrCreateKey)

    fun needsMigration(preferences: Preferences): Boolean =
        preferences.asMap().any { (preferenceKey, value) ->
            value is String &&
                isSensitive(preferenceKey.name) &&
                value.isNotEmpty() &&
                !value.startsWith(PREFIX)
        }

    fun encrypt(preferences: Preferences): Preferences =
        preferences.toMutablePreferences().apply {
            preferences.asMap().forEach { (preferenceKey, value) ->
                if (value is String && isSensitive(preferenceKey.name) && value.isNotEmpty()) {
                    val encrypted =
                        if (value.startsWith(PREFIX)) value else encryptValue(preferenceKey.name, value)
                    this[stringPreferencesKey(preferenceKey.name)] = encrypted
                }
            }
        }

    fun decrypt(preferences: Preferences): Preferences =
        preferences.toMutablePreferences().apply {
            preferences.asMap().forEach { (preferenceKey, value) ->
                if (value is String && isSensitive(preferenceKey.name) && value.startsWith(PREFIX)) {
                    val stringKey = stringPreferencesKey(preferenceKey.name)
                    val plaintext = decryptValue(preferenceKey.name, value)
                    if (plaintext == null) remove(stringKey) else this[stringKey] = plaintext
                }
            }
        }

    private fun isSensitive(name: String): Boolean {
        if (name in explicitlySensitiveNames) return true
        val normalized = name.lowercase()
        return secretNameFragments.any(normalized::contains)
    }

    private fun encryptValue(
        preferenceName: String,
        plaintext: String,
    ): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(preferenceName.toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP) +
            ":" +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    private fun decryptValue(
        preferenceName: String,
        encoded: String,
    ): String? =
        runCatching {
            val parts = encoded.removePrefix(PREFIX).split(':')
            require(parts.size == 2)
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)
            require(iv.size == IV_BYTES)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(preferenceName.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()

    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec
                .Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }
}

internal class SecurePreferencesDataStore(
    private val delegate: DataStore<Preferences>,
) : DataStore<Preferences> {
    override val data: Flow<Preferences> = delegate.data.map(SensitivePreferenceCrypto::decrypt)

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
        val encrypted =
            delegate.updateData { stored ->
                val plaintext = SensitivePreferenceCrypto.decrypt(stored)
                SensitivePreferenceCrypto.encrypt(transform(plaintext))
            }
        return SensitivePreferenceCrypto.decrypt(encrypted)
    }
}
