package com.morneven.kron.security

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.data.ActivityEventEntity
import com.morneven.kron.data.EvidenceOrigin
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.LedgerType
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TeamCapability
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    @param:ApplicationContext private val context: Context,
    private val databaseRuntime: DatabaseRuntime,
    private val attachmentStore: EncryptedAttachmentStore,
    private val snapshotOperationLock: SnapshotOperationLock,
    private val ledgerPostingEngine: LedgerPostingEngine,
    private val teamAccessGuard: TeamAccessGuard,
) {
    private val database get() = databaseRuntime.current()

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
        teamAccessGuard.require(targetEvent.accountId, TeamCapability.WRITE)
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

    /**
     * Decodes an in-memory preview only. The source remains encrypted at rest and the byte array is
     * cleared as soon as Android has decoded it.
     */
    suspend fun preview(receipt: ReceiptEntity, maxDimension: Int = 1_280): Bitmap? = withContext(Dispatchers.IO) {
        if (!receipt.mimeType.startsWith("image/", ignoreCase = true)) return@withContext null
        snapshotOperationLock.withLock {
            val source = verifiedSource(receipt) ?: return@withLock null
            val bytes = ByteArrayOutputStream().use { output ->
                attachmentStore.decrypt(source, output)
                output.toByteArray()
            }
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withLock null
                var sample = 1
                while (bounds.outWidth / sample > maxDimension || bounds.outHeight / sample > maxDimension) {
                    sample *= 2
                }
                BitmapFactory.decodeByteArray(
                    bytes,
                    0,
                    bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                )
            } finally {
                bytes.fill(0)
            }
        }
    }

    /**
     * Creates a short-lived, app-owned copy for a gallery/viewer intent. It is deliberately not
     * written beside the encrypted receipt so FileProvider can grant only this one URI.
     */
    suspend fun materializeForViewing(receipt: ReceiptEntity): File = withContext(Dispatchers.IO) {
        require(receipt.mimeType.startsWith("image/", ignoreCase = true)) { "Bukti bukan gambar" }
        snapshotOperationLock.withLock {
            val source = requireNotNull(verifiedSource(receipt)) { "File bukti belum tersedia di perangkat ini" }
            val directory = File(context.cacheDir, VIEWING_DIRECTORY).apply { mkdirs() }
            val target = File(directory, "${receipt.storageId}.${extensionFor(receipt.mimeType)}")
            val candidate = File(directory, ".${target.name}.new")
            try {
                FileOutputStream(candidate).use { output ->
                    attachmentStore.decrypt(source, output)
                    output.fd.sync()
                }
                try {
                    Files.move(
                        candidate.toPath(),
                        target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(candidate.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                target
            } finally {
                candidate.delete()
            }
        }
    }

    private fun verifiedSource(receipt: ReceiptEntity): File? {
        val source = receipt.localPath?.let(::File)?.takeIf(File::isFile) ?: return null
        val inspected = attachmentStore.inspect(source)
        require(inspected.byteSize == receipt.byteSize && inspected.sha256.equals(receipt.sha256, ignoreCase = true)) {
            "Integritas bukti tidak cocok"
        }
        return source
    }

    private fun extensionFor(mimeType: String): String = when (mimeType.lowercase()) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        else -> "jpg"
    }

    companion object {
        private const val MAX_DISPLAY_NAME = 160
        private const val MAX_MIME_TYPE = 120
        private const val VIEWING_DIRECTORY = "receipt-preview"
    }
}
