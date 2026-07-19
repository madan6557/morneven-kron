package com.morneven.kron.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyReceiptEncryptionTest {
    private lateinit var context: Context
    private lateinit var helper: SupportSQLiteOpenHelper
    private lateinit var database: SupportSQLiteDatabase
    private lateinit var attachmentStore: EncryptedAttachmentStore
    private val filesToDelete = mutableListOf<File>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        attachmentStore = EncryptedAttachmentStore(context, DatabaseKeyManager(context))
        helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(
                            """
                            CREATE TABLE receipts (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                eventId TEXT NOT NULL,
                                localPath TEXT NOT NULL,
                                storageId TEXT NOT NULL,
                                displayName TEXT NOT NULL,
                                mimeType TEXT NOT NULL,
                                byteSize INTEGER NOT NULL,
                                sha256 TEXT NOT NULL,
                                encryptionNonce TEXT,
                                encryptionVersion INTEGER NOT NULL,
                                createdAt INTEGER NOT NULL
                            )
                            """.trimIndent(),
                        )
                        db.execSQL(
                            """
                            CREATE TABLE audit_snapshots (
                                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                                eventId TEXT NOT NULL,
                                reason TEXT NOT NULL,
                                beforeJson TEXT NOT NULL,
                                afterJson TEXT NOT NULL
                            )
                            """.trimIndent(),
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        database = helper.writableDatabase
    }

    @After
    fun tearDown() {
        helper.close()
        filesToDelete.forEach(File::delete)
    }

    @Test
    fun plaintextPrivateReceiptIsEncryptedAndMetadataIsUpdated() {
        val payload = "legacy receipt".toByteArray()
        val storageId = uniqueStorageId()
        val source = File(context.filesDir, "receipts/$storageId.jpg").apply {
            parentFile?.mkdirs()
            writeBytes(payload)
        }
        val target = attachmentStore.destination(storageId)
        filesToDelete += source
        filesToDelete += target
        insertLegacyReceipt(1, source, storageId)

        LegacyReceiptEncryption.migrate(context, database, attachmentStore)
        LegacyReceiptEncryption.migrate(context, database, attachmentStore)

        assertFalse(source.exists())
        assertTrue(target.isFile)
        database.query(
            "SELECT localPath, byteSize, sha256, encryptionNonce, encryptionVersion FROM receipts WHERE id = 1",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(target.absolutePath, cursor.getString(0))
            assertEquals(payload.size.toLong(), cursor.getLong(1))
            assertEquals(64, cursor.getString(2).length)
            assertFalse(cursor.getString(3).isNullOrBlank())
            assertEquals(EncryptedAttachmentStore.ENCRYPTION_VERSION, cursor.getInt(4))
        }
        database.query("SELECT COUNT(*) FROM audit_snapshots").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        val decrypted = ByteArrayOutputStream()
        attachmentStore.decrypt(target, decrypted)
        assertArrayEquals(payload, decrypted.toByteArray())
    }

    @Test
    fun completedEncryptedFileRecoversWhenLegacySourceIsMissing() {
        val payload = "recovered receipt".toByteArray()
        val storageId = uniqueStorageId()
        val target = attachmentStore.encrypt(ByteArrayInputStream(payload), storageId).file
        val missingSource = File(context.filesDir, "receipts/missing-$storageId.jpg")
        filesToDelete += target
        insertLegacyReceipt(2, missingSource, storageId)

        LegacyReceiptEncryption.migrate(context, database, attachmentStore)

        database.query("SELECT localPath, encryptionVersion FROM receipts WHERE id = 2").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(target.absolutePath, cursor.getString(0))
            assertEquals(EncryptedAttachmentStore.ENCRYPTION_VERSION, cursor.getInt(1))
        }
    }

    private fun insertLegacyReceipt(id: Long, source: File, storageId: String) {
        database.execSQL(
            """
            INSERT INTO receipts(
                id, eventId, localPath, storageId, displayName, mimeType,
                byteSize, sha256, encryptionNonce, encryptionVersion, createdAt
            ) VALUES(?, ?, ?, ?, ?, ?, 0, '', NULL, 0, 1)
            """.trimIndent(),
            arrayOf(id, "event-$id", source.absolutePath, storageId, "Bukti-$id", "image/jpeg"),
        )
    }

    private fun uniqueStorageId(): String = UUID.randomUUID().toString().replace("-", "")
}
