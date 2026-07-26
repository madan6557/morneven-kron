package com.morneven.kron.security

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStorageTest {
    @Test
    fun keyProfilePermanentlyBindsEnvelopeToStablePassphraseMode() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "key-profile-${UUID.randomUUID()}")
        val context = isolatedContext(base, root)
        try {
            val manager = DatabaseKeyManager(context)
            val key = manager.getOrCreateDatabasePassphrase()
            key.fill(0)
            val missingPrimary = context.getDatabasePath("kron-v4.db")
            assertTrue(manager.isNewDatabaseInitializationPending())
            assertNull(DatabaseEncryptionManager(context, manager).preparePrimaryDatabase(missingPrimary).guard)
            manager.confirmKeyProfile(DatabaseKeyMode.PASSPHRASE)

            assertTrue(manager.isAnyKeyProfileProvisioned())
            assertEquals(DatabaseKeyMode.PASSPHRASE, manager.readKeyProfileMode())
            assertTrue(manager.isExistingRawKeyProfileValid())
            assertFalse(manager.isNewDatabaseInitializationPending())
            val missingPrimaryError = runCatching {
                DatabaseEncryptionManager(context, manager).preparePrimaryDatabase(missingPrimary)
            }.exceptionOrNull()
            assertTrue(missingPrimaryError is DatabaseRecoveryRequiredException)

            val profile = File(context.noBackupFilesDir, "security/database-key-profile-v2.bin")
            val corrupted = profile.readBytes().also { bytes ->
                bytes[bytes.lastIndex] = (bytes.last().toInt() xor 0x01).toByte()
            }
            profile.writeBytes(corrupted)

            assertFalse(manager.isExistingRawKeyProfileValid())
            assertTrue(runCatching { manager.loadExistingDatabasePassphrase() }.isSuccess)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryArtifactBlocksNewKeyWhenPrimaryDatabaseIsMissing() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val root = File(base.cacheDir, "missing-primary-${UUID.randomUUID()}")
        val context = isolatedContext(base, root)
        val primary = context.getDatabasePath("kron-v4.db")
        val rollback = File(primary.parentFile, ".${primary.name}.pre-1.4.7")
        try {
            rollback.parentFile?.mkdirs()
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(rollback, null).use { database ->
                database.execSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
            }

            val encryption = DatabaseEncryptionManager(context, DatabaseKeyManager(context))
            val error = runCatching { encryption.preparePrimaryDatabase(primary) }.exceptionOrNull()

            assertTrue(error is DatabaseRecoveryRequiredException)
            assertFalse(File(context.noBackupFilesDir, "security/database-key-v1.bin").exists())
            assertTrue(encryption.hasRecoverablePreEncryptionCopy(primary))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun databasePassphraseIsStableAndNotStoredAsPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DatabaseKeyManager(context)
        val first = manager.getOrCreateDatabasePassphrase()
        val second = manager.getOrCreateDatabasePassphrase()
        try {
            assertArrayEquals(first, second)
            assertTrue(manager.isProvisioned())
            val envelope = context.noBackupFilesDir.resolve("security/database-key-v1.bin").readBytes()
            assertFalse(envelope.asList().windowed(first.size).any { it.toByteArray().contentEquals(first) })
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun attachmentRoundTripUsesEncryptedStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedAttachmentStore(context, DatabaseKeyManager(context))
        val plain = "bukti finansial rahasia".toByteArray()
        val stored = plain.inputStream().use { store.encrypt(it, "test_attachment_123") }
        try {
            assertFalse(stored.file.readBytes().containsSubsequence(plain))
            val restored = ByteArrayOutputStream()
            store.decrypt(stored.file, restored)
            assertArrayEquals(plain, restored.toByteArray())
            assertTrue(stored.sha256.matches(Regex("[0-9a-f]{64}")))
        } finally {
            stored.file.delete()
        }
    }

    @Test
    fun plaintextDatabaseIsExportedToValidatedSqlCipherBeforeItBecomesPrimary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val plaintext = File(context.cacheDir, "plaintext-${UUID.randomUUID()}.db")
        val portable = File(context.cacheDir, "portable-${UUID.randomUUID()}.db")
        try {
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(plaintext, null).use { database ->
                database.execSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                database.execSQL("INSERT INTO proof(id, value) VALUES(1, 'verified')")
            }
            val encryption = DatabaseEncryptionManager(context, DatabaseKeyManager(context))
            val preparation = encryption.preparePrimaryDatabase(plaintext)
            val guard = requireNotNull(preparation.guard)
            assertEquals(DatabaseKeyMode.PASSPHRASE, preparation.keyMode)
            val header = plaintext.inputStream().use { input -> ByteArray(16).also(input::read) }
            assertFalse(header.contentEquals("SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)))
            encryption.exportPlaintext(plaintext, portable)
            android.database.sqlite.SQLiteDatabase.openDatabase(portable.absolutePath, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { database ->
                database.rawQuery("SELECT value FROM proof WHERE id=1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("verified", cursor.getString(0))
                }
            }
            guard.commit()
            val inspection = encryption.inspectPrimaryDatabase(plaintext)
            assertTrue(inspection.acceptsPassphrase)
            assertFalse(inspection.acceptsRawKey)
        } finally {
            plaintext.delete()
            File(plaintext.path + "-wal").delete()
            File(plaintext.path + "-shm").delete()
            portable.delete()
        }
    }

    @Test
    fun stable1320PassphraseDatabaseReopensAfterProfileIsWritten() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val rootDirectory = File(base.cacheDir, "stable-passphrase-${UUID.randomUUID()}")
        val context = isolatedContext(base, rootDirectory)
        val primary = context.getDatabasePath("kron-v4.db")
        try {
            SqlCipherLibrary.ensureLoaded()
            val keyManager = DatabaseKeyManager(context)
            val passphrase = keyManager.getOrCreateDatabasePassphrase()
            try {
                net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                    primary,
                    passphrase,
                    null,
                    null,
                    null,
                ).use { database ->
                    database.rawExecSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                    database.rawExecSQL("INSERT INTO proof(id, value) VALUES(1, 'stable-1.3.20')")
                }
            } finally {
                passphrase.fill(0)
            }

            val encryption = DatabaseEncryptionManager(context, keyManager)
            val firstPreparation = encryption.preparePrimaryDatabase(primary)
            assertEquals(DatabaseKeyMode.PASSPHRASE, firstPreparation.keyMode)
            keyManager.confirmKeyProfile(firstPreparation.keyMode)
            firstPreparation.guard?.commit()

            val reopenedKeyManager = DatabaseKeyManager(context)
            val reopenedEncryption = DatabaseEncryptionManager(context, reopenedKeyManager)
            val secondPreparation = reopenedEncryption.preparePrimaryDatabase(primary)
            assertEquals(DatabaseKeyMode.PASSPHRASE, secondPreparation.keyMode)
            val inspection = reopenedEncryption.inspectPrimaryDatabase(primary)
            assertTrue(inspection.acceptsPassphrase)
            assertFalse(inspection.acceptsRawKey)
            assertEquals(DatabaseKeyMode.PASSPHRASE, reopenedKeyManager.readKeyProfileMode())

            val reopenedPassphrase = reopenedKeyManager.loadExistingDatabasePassphrase()!!
            try {
                net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                    primary.absolutePath,
                    reopenedPassphrase,
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
                    null,
                ).use { database ->
                    database.query("SELECT value FROM proof WHERE id=1").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("stable-1.3.20", cursor.getString(0))
                    }
                }
            } finally {
                reopenedPassphrase.fill(0)
            }
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun schemaUpgradeRollbackRestoresVersion13Database() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val rootDirectory = File(base.cacheDir, "schema-rollback-${UUID.randomUUID()}")
        val context = isolatedContext(base, rootDirectory)
        val primary = context.getDatabasePath("kron-v4.db")
        try {
            SqlCipherLibrary.ensureLoaded()
            val keyManager = DatabaseKeyManager(context)
            val passphrase = keyManager.getOrCreateDatabasePassphrase()
            try {
                net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                    primary,
                    passphrase,
                    null,
                    null,
                    null,
                ).use { database ->
                    database.rawExecSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                    database.rawExecSQL("INSERT INTO proof(id, value) VALUES(1, 'schema-13')")
                    database.rawExecSQL("PRAGMA user_version = 13")
                }

                val encryption = DatabaseEncryptionManager(context, keyManager)
                val guard = requireNotNull(encryption.preparePrimaryDatabase(primary, 14).guard)
                assertTrue(primary.parentFile?.listFiles().orEmpty().any {
                    it.name == ".${primary.name}.pre-schema-13-to-14"
                })
                assertTrue(
                    File(primary.parentFile, ".${primary.name}.pre-schema-13-to-14.security/database-key-v1.bin").isFile,
                )

                net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                    primary.absolutePath,
                    passphrase,
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE,
                    null,
                ).use { database ->
                    database.rawExecSQL("UPDATE proof SET value = 'failed-candidate' WHERE id = 1")
                    database.rawExecSQL("PRAGMA user_version = 14")
                }
                guard.rollback()

                net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                    primary.absolutePath,
                    passphrase,
                    null,
                    net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READONLY,
                    null,
                ).use { database ->
                    database.query("SELECT value FROM proof WHERE id=1").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals("schema-13", cursor.getString(0))
                    }
                    database.query("PRAGMA user_version").use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(13, cursor.getInt(0))
                    }
                }
            } finally {
                passphrase.fill(0)
            }
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun staleLegacyRawProfileCannotOverrideValidPassphraseDatabase() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val rootDirectory = File(base.cacheDir, "stale-profile-${UUID.randomUUID()}")
        val context = isolatedContext(base, rootDirectory)
        val primary = context.getDatabasePath("kron-v4.db")
        try {
            SqlCipherLibrary.ensureLoaded()
            val keyManager = DatabaseKeyManager(context)
            val passphrase = keyManager.getOrCreateDatabasePassphrase()
            try {
                net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                    primary,
                    passphrase,
                    null,
                    null,
                    null,
                ).use { database ->
                    database.rawExecSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY)")
                }
            } finally {
                passphrase.fill(0)
            }
            val legacyProfile = File(context.noBackupFilesDir, "security/database-key-profile-v1.bin")
            legacyProfile.parentFile?.mkdirs()
            legacyProfile.writeBytes("stale-profile-that-must-not-block-data".toByteArray())

            val encryption = DatabaseEncryptionManager(context, keyManager)
            val preparation = encryption.preparePrimaryDatabase(primary)
            assertEquals(DatabaseKeyMode.PASSPHRASE, preparation.keyMode)
            assertTrue(encryption.inspectPrimaryDatabase(primary).acceptsPassphrase)
        } finally {
            rootDirectory.deleteRecursively()
        }
    }

    @Test
    fun rawKeyDatabaseFromPreviousReleaseCanBeOpenedAndExported() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val encrypted = File(context.cacheDir, "raw-legacy-${UUID.randomUUID()}.db")
        val portable = File(context.cacheDir, "raw-portable-${UUID.randomUUID()}.db")
        val keyManager = DatabaseKeyManager(context)
        val keyMaterial = keyManager.getOrCreateDatabasePassphrase()
        val rawKey = rawKeySpec(keyMaterial)
        try {
            net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                encrypted,
                rawKey.toByteArray(Charsets.US_ASCII),
                null,
                null,
                null,
            ).use { database ->
                database.rawExecSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                database.rawExecSQL("INSERT INTO proof(id, value) VALUES(1, 'raw-key')")
            }

            val encryption = DatabaseEncryptionManager(context, keyManager)
            assertEquals(DatabaseKeyMode.RAW_HEX, encryption.preparePrimaryDatabase(encrypted).keyMode)
            encryption.exportPlaintext(encrypted, portable)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                portable.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery("SELECT value FROM proof WHERE id=1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("raw-key", cursor.getString(0))
                }
            }
        } finally {
            keyMaterial.fill(0)
            encrypted.delete()
            File(encrypted.path + "-wal").delete()
            File(encrypted.path + "-shm").delete()
            portable.delete()
        }
    }

    @Test
    fun validatedPreEncryptionCopyRestoresWithoutDeletingUnreadablePrimary() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val primary = File(context.cacheDir, "recovery-${UUID.randomUUID()}.db")
        val rollback = File(primary.parentFile, ".${primary.name}.pre-1.4.7")
        val portable = File(primary.parentFile, "${primary.name}.portable")
        try {
            primary.writeBytes("unreadable encrypted data".toByteArray())
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(rollback, null).use { database ->
                database.execSQL("CREATE TABLE proof (id INTEGER PRIMARY KEY, value TEXT NOT NULL)")
                database.execSQL("INSERT INTO proof(id, value) VALUES(1, 'rollback')")
            }

            val encryption = DatabaseEncryptionManager(context, DatabaseKeyManager(context))
            assertTrue(encryption.hasRecoverablePreEncryptionCopy(primary))
            encryption.restorePreEncryptionCopy(primary)

            val quarantined = primary.parentFile?.listFiles()
                ?.singleOrNull { it.name.startsWith(".${primary.name}.unreadable-") }
            assertTrue(quarantined?.readText() == "unreadable encrypted data")
            encryption.exportPlaintext(primary, portable)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                portable.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { database ->
                database.rawQuery("SELECT value FROM proof WHERE id=1", null).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals("rollback", cursor.getString(0))
                }
            }
        } finally {
            primary.delete()
            File(primary.path + "-wal").delete()
            File(primary.path + "-shm").delete()
            rollback.delete()
            portable.delete()
            primary.parentFile?.listFiles()
                ?.filter { it.name.startsWith(".${primary.name}.unreadable-") }
                ?.forEach(File::delete)
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }

    private fun isolatedContext(base: Context, root: File): Context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = File(root, "no-backup").apply { mkdirs() }
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getDatabasePath(name: String): File =
            File(root, "databases/$name").also { it.parentFile?.mkdirs() }
    }

    private fun rawKeySpec(keyMaterial: ByteArray): String = buildString(keyMaterial.size * 2 + 3) {
        append("x'")
        keyMaterial.forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
        append('\'')
    }

    private companion object {
        const val HEX_DIGITS = "0123456789abcdef"
    }
}

private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
