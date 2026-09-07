/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.googledrive

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.backup.BackupArchiveCategory
import moe.rukamori.archivetune.backup.CreateBackupUseCase
import timber.log.Timber
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoogleDriveClient
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val createBackupUseCase: CreateBackupUseCase,
    ) {

        sealed interface UploadResult {
            data class Success(val fileName: String) : UploadResult

            data class TransientFailure(val message: String) : UploadResult

            data class PermanentFailure(val message: String) : UploadResult
        }

        suspend fun uploadBackup(settings: GoogleDriveSyncSettings, backupFileName: String): UploadResult =
            withContext(Dispatchers.IO) {
                val treeUriString = settings.remoteFolderUri
                    ?: return@withContext UploadResult.PermanentFailure("No backup folder configured")
                val treeUri =
                    try {
                        Uri.parse(treeUriString)
                    } catch (e: Exception) {
                        return@withContext UploadResult.PermanentFailure("Invalid folder URI: ${e.message}")
                    }

                if (!isGoogleDriveAuthority(treeUri)) {
                    Timber.w(
                        "GoogleDriveClient.uploadBackup: folder URI authority '%s' is not Google Drive — uploading via SAF anyway (user-picked folder)",
                        treeUri.authority,
                    )
                }
                val folderDocUri =
                    try {
                        val folderDocId = DocumentsContract.getTreeDocumentId(treeUri)
                        DocumentsContract.buildDocumentUriUsingTree(treeUri, folderDocId)
                    } catch (e: Exception) {
                        return@withContext UploadResult.PermanentFailure("Could not resolve the picked folder: ${e.message}")
                    }

                val tempDir = File(context.cacheDir, "gdrive_backup").apply { mkdirs() }
                val tempFile = File(tempDir, "${System.currentTimeMillis()}_upload.backup")
                try {

                    val tempUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.FileProvider",
                        tempFile,
                    )
                    createBackupUseCase(
                        uri = tempUri,
                        categories = BackupArchiveCategory.entries.toSet(),
                    )

                    val fullFileName = "$backupFileName.backup"

                    if (settings.overwriteExisting) {
                        findChildDocumentId(treeUri, folderDocUri, fullFileName)?.let { existingDocId ->
                            val existingDocUri =
                                DocumentsContract.buildDocumentUriUsingTree(treeUri, existingDocId)
                            runCatching {
                                DocumentsContract.deleteDocument(context.contentResolver, existingDocUri)
                            }.onFailure { Timber.w(it, "GoogleDriveClient: could not delete existing backup document") }
                        }
                    }

                    val targetDocUri =
                        try {
                            DocumentsContract.createDocument(
                                context.contentResolver,
                                folderDocUri,
                                BACKUP_MIME_TYPE,
                                fullFileName,
                            )
                        } catch (e: Exception) {
                            Timber.w(e, "GoogleDriveClient: provider refused to create backup document")
                            null
                        } ?: return@withContext UploadResult.TransientFailure(
                            "The cloud provider refused to create the backup file",
                        )

                    val written =
                        try {
                            context.contentResolver.openOutputStream(targetDocUri, "w")?.use { out ->
                                tempFile.inputStream().use { input ->
                                    input.copyTo(out)
                                }
                                true
                            } ?: false
                        } catch (io: IOException) {
                            Timber.w(io, "GoogleDriveClient: IOException writing backup to folder")

                            runCatching {
                                DocumentsContract.deleteDocument(context.contentResolver, targetDocUri)
                            }
                            false
                        }
                    if (!written) {
                        return@withContext UploadResult.TransientFailure("Failed to write backup bytes to the folder")
                    }

                    UploadResult.Success(fullFileName)
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (security: SecurityException) {

                    Timber.w(security, "GoogleDriveClient: no permission for folder URI")
                    UploadResult.PermanentFailure("Folder access was revoked — please re-pick the folder")
                } catch (e: Exception) {
                    Timber.w(e, "GoogleDriveClient.uploadBackup failed")
                    UploadResult.TransientFailure(e.message ?: "Unknown error")
                } finally {
                    runCatching { tempFile.delete() }
                }
            }

        private fun findChildDocumentId(treeUri: Uri, folderDocUri: Uri, name: String): String? {
            val folderDocId = DocumentsContract.getDocumentId(folderDocUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, folderDocId)
            return try {
                context.contentResolver.query(
                    childrenUri,
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(0) ?: continue
                        val docName = cursor.getString(1) ?: continue
                        if (docName == name) return docId
                    }
                    null
                }
            } catch (e: Exception) {
                Timber.w(e, "GoogleDriveClient: failed to list folder children")
                null
            }
        }

        companion object {

            private const val BACKUP_MIME_TYPE = "application/octet-stream"

            private val GOOGLE_DRIVE_AUTHORITIES = setOf(
                "com.google.android.apps.docs.storage",
                "com.google.android.apps.docs.storage.legacy",
            )

            fun isGoogleDriveAuthority(uri: Uri): Boolean {
                val authority = uri.authority ?: return false
                return authority in GOOGLE_DRIVE_AUTHORITIES
            }
        }
    }
