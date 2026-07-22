package com.morneven.kron.security

import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.EvidenceOrigin
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.LedgerType
import com.morneven.kron.data.ReceiptEntity
import androidx.room.withTransaction
import java.io.InputStream
import java.time.LocalDate
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
    private val ledgerPostingEngine: LedgerPostingEngine,
) {
    suspend fun importReceipt(
        eventId: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        origin: String = EvidenceOrigin.GALLERY,
        capturedAt: Long? = null,
        latitude: Double? = null,
        longitude: Double? = null,
    ): ReceiptEntity = withContext(Dispatchers.IO) {
        snapshotOperationLock.withLock {
            importReceiptLocked(eventId, displayName, mimeType, input, origin, capturedAt, latitude, longitude)
        }
    }

    private suspend fun importReceiptLocked(
        eventId: String,
        displayName: String,
        mimeType: String,
        input: InputStream,
        origin: String,
        capturedAt: Long?,
        latitude: Double?,
        longitude: Double?,
    ): ReceiptEntity {
        val targetEvent = requireNotNull(database.kronDao().eventById(eventId)) { "Transaksi untuk bukti tidak ditemukan" }
        require(origin in setOf(EvidenceOrigin.CAMERA, EvidenceOrigin.GALLERY)) { "Asal bukti tidak valid" }
        val safeName = displayName.trim().take(MAX_DISPLAY_NAME).ifBlank { "Bukti transaksi" }
        val safeMime = mimeType.trim().take(MAX_MIME_TYPE).ifBlank { "application/octet-stream" }
        val storageId = UUID.randomUUID().toString().replace("-", "")
        val evidenceEventId = UUID.randomUUID().toString()
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
            capturedAt = capturedAt,
            latitude = latitude,
            longitude = longitude,
            origin = origin,
            evidenceEventId = evidenceEventId,
        )
        return try {
            database.withTransaction {
                database.kronDao().insertEvent(
                    ActivityEventEntity(
                        id = evidenceEventId,
                        type = LedgerType.ATTACH_EVIDENCE,
                        title = "Bukti transaksi ditambahkan",
                        note = "Bukti asli disimpan tanpa perubahan",
                        source = "USER",
                        effectiveEpochDay = LocalDate.now().toEpochDay(),
                        relatedEventId = eventId,
                        accountId = targetEvent.accountId,
                    ),
                )
                val id = database.kronDao().insertReceipt(receipt)
                val saved = receipt.copy(id = id)
                database.kronDao().insertAudit(
                    AuditSnapshotEntity(
                        eventId = evidenceEventId,
                        reason = "ATTACH_RECEIPT",
                        beforeJson = "{}",
                        afterJson = JSONObject()
                            .put("receiptId", id)
                            .put("storageId", storageId)
                            .put("displayName", safeName)
                            .put("mimeType", safeMime)
                            .put("byteSize", stored.byteSize)
                            .put("sha256", stored.sha256)
                            .put("origin", origin)
                            .put("targetEventId", eventId)
                            .toString(),
                    ),
                )
                ledgerPostingEngine.finalizeEvent(evidenceEventId)
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
