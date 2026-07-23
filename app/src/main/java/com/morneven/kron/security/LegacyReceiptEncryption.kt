package com.morneven.kron.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import java.io.File

object LegacyReceiptEncryption {
    fun migrate(
        context: Context,
        database: SupportSQLiteDatabase,
        attachmentStore: EncryptedAttachmentStore,
    ) {
        val legacyReceipts = database.query(
            """
            SELECT id, eventId, localPath, storageId
            FROM receipts
            WHERE encryptionVersion != ?
            ORDER BY id
            """.trimIndent(),
            arrayOf(EncryptedAttachmentStore.ENCRYPTION_VERSION),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        LegacyReceipt(
                            id = cursor.getLong(0),
                            eventId = cursor.getString(1),
                            localPath = cursor.getString(2),
                            storageId = cursor.getString(3),
                        ),
                    )
                }
            }
        }

        legacyReceipts.forEach { receipt ->
            migrateOne(context, database, attachmentStore, receipt)
        }
    }

    private fun migrateOne(
        context: Context,
        database: SupportSQLiteDatabase,
        attachmentStore: EncryptedAttachmentStore,
        receipt: LegacyReceipt,
    ) {
        val source = File(receipt.localPath)
        val target = attachmentStore.destination(receipt.storageId)
        val existing = target.takeIf(File::isFile)?.let { file ->
            runCatching { attachmentStore.inspect(file) }.getOrNull()
        }
        val stored = existing ?: run {
            require(source.isFile) { "Lampiran lama ${receipt.id} tidak ditemukan" }
            source.inputStream().use { input -> attachmentStore.encrypt(input, receipt.storageId) }
            attachmentStore.inspect(target)
        }

        if (source.canonicalFile != target.canonicalFile && isPrivateLegacyReceipt(context, source)) {
            require(source.delete() || !source.exists()) { "Lampiran lama ${receipt.id} tidak dapat diamankan" }
        }

        database.execSQL(
            """
            UPDATE receipts
            SET localPath = ?, byteSize = ?, sha256 = ?, encryptionNonce = ?, encryptionVersion = ?
            WHERE id = ? AND encryptionVersion != ?
            """.trimIndent(),
            arrayOf(
                stored.file.absolutePath,
                stored.byteSize,
                stored.sha256,
                stored.nonce,
                EncryptedAttachmentStore.ENCRYPTION_VERSION,
                receipt.id,
                EncryptedAttachmentStore.ENCRYPTION_VERSION,
            ),
        )
        database.execSQL(
            """
            INSERT INTO audit_snapshots(eventId, reason, beforeJson, afterJson)
            SELECT ?, 'ENCRYPT_LEGACY_RECEIPT', '{"encryptionVersion":0}', '{"encryptionVersion":1}'
            WHERE changes() = 1
            """.trimIndent(),
            arrayOf(receipt.eventId),
        )
    }

    private fun isPrivateLegacyReceipt(context: Context, source: File): Boolean {
        val canonicalSource = source.canonicalFile.toPath()
        val privateReceiptRoots = listOf(
            File(context.filesDir, RECEIPTS_DIRECTORY),
            File(context.noBackupFilesDir, RECEIPTS_DIRECTORY),
        )
        return privateReceiptRoots.any { root -> canonicalSource.startsWith(root.canonicalFile.toPath()) }
    }

    private data class LegacyReceipt(
        val id: Long,
        val eventId: String,
        val localPath: String,
        val storageId: String,
    )

    private const val RECEIPTS_DIRECTORY = "receipts"
}
