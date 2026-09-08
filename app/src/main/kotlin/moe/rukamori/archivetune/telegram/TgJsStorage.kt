/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 *
 * File-backed storage bridge for the mtcute host inside QuickJS.
 *
 * The JS side implements mtcute's ITelegramStorageProvider (kv, authKeys,
 * authKeysTemp, peers, refMessages) on top of this flat binary key-value
 * store: one file per (store, key) under filesDir/telegram-js/<store>/.
 * Keys are hashed (SHA-256 hex prefix) into filesystem-safe names. Writes are
 * write-through: auth keys must be persisted immediately per mtcute's contract,
 * so every set/delete lands on disk before the JS promise resolves.
 *
 * The presence of a non-empty directory is also the "an existing session is
 * stored here" signal used by TelegramClient.startIfSessionExists().
 */

package moe.rukamori.archivetune.telegram

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

internal object TgJsStorage {
    private const val TAG = "TgJsStorage"

    private val knownStores =
        setOf("kv", "authKeys", "authKeysTemp", "peers", "refMessages")

    @Volatile
    private var baseDir: File? = null

    fun attach(context: Context) {
        baseDir = File(context.filesDir, "telegram-js")
    }

    val sessionDir: File?
        get() = baseDir

    fun hasSession(context: Context): Boolean {
        val dir = baseDir ?: File(context.filesDir, "telegram-js")
        if (!dir.isDirectory) return false
        return knownStores.any { File(dir, it).listFiles()?.isNotEmpty() == true }
    }

    private fun storeDir(store: String): File? {
        val dir = baseDir ?: return null
        if (store !in knownStores) return null
        return File(dir, store)
    }

    private fun keyFile(store: String, key: String): File? {
        val dir = storeDir(store) ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))
        val name = digest.take(12).joinToString("") { "%02x".format(it) }
        return File(dir, "$name.bin")
    }

    suspend fun loadAll(store: String): List<List<Any?>> =
        withContext(Dispatchers.IO) {
            val dir = storeDir(store) ?: return@withContext emptyList()
            val files = dir.listFiles() ?: return@withContext emptyList()
            files
                .filter { it.isFile && it.name.endsWith(".bin") }
                .mapNotNull { file ->
                    runCatching {
                        listOf(file.name.removeSuffix(".bin"), file.readBytes())
                    }.getOrNull()
                }
        }

    suspend fun set(
        store: String,
        key: String,
        value: ByteArray,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val file = keyFile(store, key) ?: return@withContext false
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeBytes(value)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(value)
                    tmp.delete()
                }
                true
            }.getOrElse {
                Timber.tag(TAG).w(it, "store set failed: %s/%s", store, key)
                false
            }
        }

    suspend fun delete(
        store: String,
        key: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val file = keyFile(store, key) ?: return@withContext false
            file.delete()
        }

    suspend fun clear(store: String): Boolean =
        withContext(Dispatchers.IO) {
            val dir = storeDir(store) ?: return@withContext false
            dir.listFiles()?.forEach { it.delete() }
            true
        }

    suspend fun clearAll(): Boolean =
        withContext(Dispatchers.IO) {
            val dir = baseDir ?: return@withContext false
            knownStores.forEach { store ->
                File(dir, store).listFiles()?.forEach { it.delete() }
            }
            true
        }
}
