package com.morneven.kron.security

import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.ReceiptEntity
import androidx.room.withTransaction
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class ReceiptManager @Inject constructor(
    private val database: KronDatabase,
    private val attachmentStore: EncryptedAttachmentStore,
    private val snapshotOperationLock: SnapshotOperationLock,
) {
    suspend fun importReceipt(
        eventId: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
    ): ReceiptEntity = withContext(Dispatchers.IO) {
        snapshotOperationLock.withLock {
            importReceiptLocked(eventId, displayName, mimeType, input)
        }
    }

    private suspend fun importReceiptLocked(
        eventId: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
    ): ReceiptEntity {
        require(database.kronDao().eventById(eventId) != null) { "Transaksi untuk bukti tidak ditemukan" }
        val safeName = displayName.trim().take(MAX_DISPLAY_NAME).ifBlank { "Bukti transaksi" }
        val safeMime = mimeType.trim().take(MAX_MIME_TYPE).ifBlank { "application/octet-stream" }
        val storageId = UUID.randomUUID().toString().replace("-", "")
        val stored = attachmentStore.encrypt(input, storageId)
        val receipt = ReceiptEntity(
            eventId = eventId,
            localPath = stored.file.absolutePath,
            storageId = storageId,
            displayName = safeName,
            mimeType = safeMime,
            byteSize = stored.byteSize,
            sha256 = stored.sha256,
            encryptionNonce = stored.nonce,
            encryptionVersion = stored.encryptionVersion,
        )
        return try {
            database.withTransaction {
                val id = database.kronDao().insertReceipt(receipt)
                val saved = receipt.copy(id = id)
                database.kronDao().insertAudit(
                    AuditSnapshotEntity(
                        eventId = eventId,
                        reason = "ATTACH_RECEIPT",
                        beforeJson = "{}",
                        afterJson = JSONObject()
                            .put("receiptId", id)
                            .put("storageId", storageId)
                            .put("displayName", safeName)
                            .put("mimeType", safeMime)
                            .put("byteSize", stored.byteSize)
                            .put("sha256", stored.sha256)
                            .toString(),
                    ),
                )
                saved
            }
        } catch (error: Exception) {
            stored.file.delete()
            throw error
        }
    }

    companion object {
        private const val MAX_DISPLAY_NAME = 160
        private const val MAX_MIME_TYPE = 120
    }
}
