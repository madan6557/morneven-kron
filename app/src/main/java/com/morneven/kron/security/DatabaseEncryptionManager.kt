package com.morneven.kron.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteDatabaseHook
import net.zetetic.database.sqlcipher.SQLiteConnection

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyManager: DatabaseKeyManager,
) {
    data class DatabaseInspection(
        val exists: Boolean,
        val isPlaintext: Boolean,
        val acceptsEmptyKey: Boolean,
        val keyEnvelopePresent: Boolean,
        val keyEnvelopeReadable: Boolean,
        val keyProfilePresent: Boolean,
        val keyProfileValid: Boolean,
        val keyInitializationPending: Boolean,
        val acceptsRawKey: Boolean,
        val acceptsPassphrase: Boolean,
        val hasPreEncryptionCopy: Boolean,
        val hasRecoveryArtifacts: Boolean,
    )

    init {
        SqlCipherLibrary.ensureLoaded()
    }

    fun openHelperFactory(dbPath: String): SupportSQLiteOpenHelper.Factory {
        val database = File(dbPath)
        if (
            !database.exists() &&
            (
                keyManager.isRawKeyProfileProvisioned() ||
                    (keyManager.isProvisioned() && !keyManager.isNewDatabaseInitializationPending())
                )
        ) {
            throw DatabaseRecoveryRequiredException(
                "Database utama tidak ditemukan, tetapi metadata kunci KRON masih tersedia.",
            )
        }
        val keyMaterial = keyManager.getOrCreateDatabasePassphrase()
        return try {
            net.zetetic.database.sqlcipher.SupportOpenHelperFactory(
                rawKeySpec(keyMaterial),
                SQLCIPHER_4_HOOK,
                false,
            )
        } finally {
            keyMaterial.fill(0)
        }
    }

    fun preparePrimaryDatabase(database: File): EncryptionGuard? {
        if (!database.exists()) {
            if (
                keyManager.isRawKeyProfileProvisioned() ||
                (keyManager.isProvisioned() && !keyManager.isNewDatabaseInitializationPending()) ||
                hasPrimaryRecoveryArtifacts(database)
            ) {
                throw DatabaseRecoveryRequiredException(
                    "Database utama tidak ditemukan, tetapi metadata kunci atau artefak pemulihan KRON masih tersedia.",
                )
            }
            return null
        }
        val rollback = File(database.parentFile, "${database.name}.pre-encryption")
        val sourceStaging = File(database.parentFile, "${database.name}.source-staging")
        val staging = File(database.parentFile, "${database.name}.encryption-staging")
        val plaintext = canOpenPlaintext(database)
        val emptyKeyDatabase = !plaintext && canOpenEncrypted(database, ByteArray(0))
        val profileProvisioned = database.name == PRIMARY_DATABASE_NAME &&
            keyManager.isRawKeyProfileProvisioned()
        if (profileProvisioned && (plaintext || emptyKeyDatabase)) {
            throw DatabaseKeyProfileMismatchException(
                "Profil kunci menyatakan SQLCipher raw key, tetapi database memakai format lain.",
            )
        }
        val keyMaterial = when {
            plaintext || emptyKeyDatabase -> keyManager.getOrCreateForValidatedLegacyDatabase()
            else -> keyManager.loadExistingDatabasePassphrase()
                ?: throw DatabaseKeyUnavailableException(
                    "Database KRON terenkripsi, tetapi kunci perangkat aslinya tidak ditemukan.",
                )
        }
        val rawKey = rawKeySpec(keyMaterial)
        try {
            val acceptsRawKey = canOpenEncrypted(database, rawKey)
            if (acceptsRawKey) {
                return if (rollback.exists()) EncryptionGuard(database, rollback) else null
            }
            if (profileProvisioned) {
                throw DatabaseKeyProfileMismatchException(
                    "Database tidak cocok dengan profil raw key KRON yang telah dikunci.",
                )
            }
            val sourceKey = when {
                canOpenEncrypted(database, keyMaterial) -> keyMaterial
                plaintext || emptyKeyDatabase -> ByteArray(0)
                else -> throw IllegalStateException(
                    "Database KRON terenkripsi dengan kunci yang tidak dikenal. " +
                    "Pulihkan database dari cadangan yang sesuai.",
                )
            }
            if (hasDatabaseFiles(rollback)) {
                throw DatabaseRecoveryRequiredException(
                    "Salinan pra-enkripsi lama masih tersedia. Migrasi baru diblokir.",
                )
            }
            deleteDatabaseFiles(sourceStaging)
            deleteDatabaseFiles(staging)
            try {
                copyDatabaseFiles(database, sourceStaging)
                exportToEncrypted(sourceStaging, sourceKey, staging, rawKey)
                validateEncrypted(staging, rawKey)
                require(database.renameTo(rollback)) { "Database lama tidak dapat disiapkan untuk enkripsi" }
                try {
                    moveSidecar(database, rollback, "-wal")
                    moveSidecar(database, rollback, "-shm")
                    require(staging.renameTo(database)) { "Database terenkripsi tidak dapat diaktifkan" }
                } catch (error: Exception) {
                    restoreDatabaseFiles(rollback, database)
                    throw error
                }
                return EncryptionGuard(database, rollback)
            } finally {
                if (sourceKey !== keyMaterial) sourceKey.fill(0)
            }
        } finally {
            deleteDatabaseFiles(sourceStaging)
            deleteDatabaseFiles(staging)
            rawKey.fill(0)
            keyMaterial.fill(0)
        }
    }

    fun hasRecoverablePreEncryptionCopy(database: File): Boolean =
        canOpenPlaintext(File(database.parentFile, "${database.name}.pre-encryption"))

    /** Called only after a portable restore database has passed all validation. */
    fun prepareValidatedRestoreKey() {
        val keyMaterial = keyManager.getOrCreateDatabasePassphrase()
        keyMaterial.fill(0)
    }

    /** Performs read-only format and key checks. It never creates a key or changes a file. */
    fun inspectPrimaryDatabase(database: File): DatabaseInspection {
        if (!database.exists()) {
            return DatabaseInspection(
                exists = false,
                isPlaintext = false,
                acceptsEmptyKey = false,
                keyEnvelopePresent = keyManager.isProvisioned(),
                keyEnvelopeReadable = false,
                keyProfilePresent = keyManager.isRawKeyProfileProvisioned(),
                keyProfileValid = false,
                keyInitializationPending = keyManager.isNewDatabaseInitializationPending(),
                acceptsRawKey = false,
                acceptsPassphrase = false,
                hasPreEncryptionCopy = hasRecoverablePreEncryptionCopy(database),
                hasRecoveryArtifacts = hasPrimaryRecoveryArtifacts(database),
            )
        }
        val plaintext = canOpenPlaintext(database)
        val emptyKey = !plaintext && canOpenEncrypted(database, ByteArray(0))
        val keyMaterial = runCatching { keyManager.loadExistingDatabasePassphrase() }.getOrNull()
        val rawKey = keyMaterial?.let(::rawKeySpec)
        return try {
            DatabaseInspection(
                exists = true,
                isPlaintext = plaintext,
                acceptsEmptyKey = emptyKey,
                keyEnvelopePresent = keyManager.isProvisioned(),
                keyEnvelopeReadable = keyMaterial != null,
                keyProfilePresent = keyManager.isRawKeyProfileProvisioned(),
                keyProfileValid = keyManager.isExistingRawKeyProfileValid(),
                keyInitializationPending = keyManager.isNewDatabaseInitializationPending(),
                acceptsRawKey = rawKey?.let { canOpenEncrypted(database, it) } == true,
                acceptsPassphrase = keyMaterial?.let { canOpenEncrypted(database, it) } == true,
                hasPreEncryptionCopy = hasRecoverablePreEncryptionCopy(database),
                hasRecoveryArtifacts = hasPrimaryRecoveryArtifacts(database),
            )
        } finally {
            rawKey?.fill(0)
            keyMaterial?.fill(0)
        }
    }

    /**
     * Re-encrypts a validated plaintext recovery copy with the current raw-key
     * profile. The unreadable primary database is retained beside it.
     */
    fun restorePreEncryptionCopy(database: File) {
        val rollback = File(database.parentFile, "${database.name}.pre-encryption")
        require(canOpenPlaintext(rollback)) { "Salinan pra-enkripsi tidak dapat dipulihkan" }
        val sourceStaging = File(database.parentFile, "${database.name}.recovery-source")
        val staging = File(database.parentFile, "${database.name}.recovery-staging")
        val quarantine = File(database.parentFile, "${database.name}.unreadable-${System.currentTimeMillis()}")
        val keyMaterial = keyManager.getOrCreateForValidatedLegacyDatabase()
        val rawKey = rawKeySpec(keyMaterial)
        deleteDatabaseFiles(sourceStaging)
        deleteDatabaseFiles(staging)
        try {
            copyDatabaseFiles(rollback, sourceStaging)
            exportToEncrypted(sourceStaging, ByteArray(0), staging, rawKey)
            validateEncrypted(staging, rawKey)
            require(!quarantine.exists()) { "Lokasi penyimpanan database lama tidak tersedia" }
            var primaryQuarantined = false
            try {
                if (database.exists()) {
                    require(database.renameTo(quarantine)) { "Database yang tidak dapat dibuka gagal diamankan" }
                    primaryQuarantined = true
                    moveSidecar(database, quarantine, "-wal")
                    moveSidecar(database, quarantine, "-shm")
                }
                require(staging.renameTo(database)) { "Salinan pra-enkripsi tidak dapat dipasang" }
            } catch (error: Exception) {
                if (primaryQuarantined) restoreDatabaseFiles(quarantine, database)
                throw error
            }
        } finally {
            deleteDatabaseFiles(sourceStaging)
            deleteDatabaseFiles(staging)
            rawKey.fill(0)
            keyMaterial.fill(0)
        }
    }

    fun exportPlaintext(encryptedDatabase: File, target: File) {
        deleteDatabaseFiles(target)
        val keyMaterial = keyManager.getOrCreateDatabasePassphrase()
        val rawKey = rawKeySpec(keyMaterial)
        try {
            val key = when {
                canOpenEncrypted(encryptedDatabase, rawKey) -> rawKey
                canOpenEncrypted(encryptedDatabase, keyMaterial) -> keyMaterial
                else -> ByteArray(0)
            }
            val source = SQLiteDatabase.openDatabase(
                encryptedDatabase.absolutePath,
                key,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                SQLCIPHER_4_HOOK,
            )
            source.use { database ->
                val targetPath = sqlString(target.absolutePath)
                database.rawExecSQL("ATTACH DATABASE '$targetPath' AS portable KEY ''")
                try {
                    database.query("SELECT sqlcipher_export('portable')").use { cursor ->
                        require(cursor.moveToFirst()) { "Database tidak dapat diekspor" }
                    }
                    val version = database.query("PRAGMA user_version").use { cursor ->
                        require(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
                    database.rawExecSQL("PRAGMA portable.user_version = $version")
                } finally {
                    database.rawExecSQL("DETACH DATABASE portable")
                }
            }
            validatePlaintextDatabase(target)
        } finally {
            rawKey.fill(0)
            keyMaterial.fill(0)
            if (!target.exists()) target.delete()
        }
    }

    fun encryptPortableDatabase(plaintext: File, target: File) {
        deleteDatabaseFiles(target)
        val keyMaterial = keyManager.getOrCreateDatabasePassphrase()
        val rawKey = rawKeySpec(keyMaterial)
        try {
            validatePlaintextDatabase(plaintext)
            exportToEncrypted(plaintext, ByteArray(0), target, rawKey)
            validateEncrypted(target, rawKey)
        } finally {
            rawKey.fill(0)
            keyMaterial.fill(0)
        }
    }

    fun validateEncrypted(database: File) {
        val keyMaterial = keyManager.getOrCreateDatabasePassphrase()
        val rawKey = rawKeySpec(keyMaterial)
        try {
            validateEncrypted(database, rawKey)
        } finally {
            rawKey.fill(0)
            keyMaterial.fill(0)
        }
    }

    private fun canOpenEncrypted(database: File, passphrase: ByteArray): Boolean {
        if (!database.exists() || database.length() < SQLITE_HEADER.size) return false
        return runCatching {
            SQLiteDatabase.openDatabase(
                database.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                SQLCIPHER_4_HOOK,
            ).use { db ->
                db.query("PRAGMA cipher_integrity_check").use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            }
        }.getOrDefault(false)
    }

    private fun canOpenPlaintext(database: File): Boolean {
        return try {
            android.database.sqlite.SQLiteDatabase.openDatabase(
                database.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
            ).use { sqlite ->
                sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun validatePlaintextDatabase(file: File) {
        require(file.exists() && file.length() >= SQLITE_HEADER.size) { "Database KRON tidak valid" }
        require(canOpenPlaintext(file)) {
            "Database KRON bukan SQLite plaintext yang dapat dibuka"
        }
    }

    private fun exportToEncrypted(
        source: File,
        sourceKey: ByteArray,
        target: File,
        targetRawKey: ByteArray,
    ) {
        deleteDatabaseFiles(target)
        val keyLiteral = String(targetRawKey, Charsets.US_ASCII)
        val database = SQLiteDatabase.openDatabase(
            source.absolutePath,
            sourceKey,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            SQLCIPHER_4_HOOK,
        )
        database.use { db ->
            val targetPath = sqlString(target.absolutePath)
            db.rawExecSQL("ATTACH DATABASE '$targetPath' AS encrypted KEY \"$keyLiteral\"")
            try {
                db.query("SELECT sqlcipher_export('encrypted')").use { cursor ->
                    require(cursor.moveToFirst()) { "Database tidak dapat dienkripsi" }
                }
                val version = db.query("PRAGMA user_version").use { cursor ->
                    require(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                db.rawExecSQL("PRAGMA encrypted.user_version = $version")
            } finally {
                db.rawExecSQL("DETACH DATABASE encrypted")
            }
        }
    }

    private fun validateEncrypted(file: File, passphrase: ByteArray) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            SQLCIPHER_4_HOOK,
        )
        database.use {
            val valid = it.query("PRAGMA cipher_integrity_check").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            require(valid) { "Integritas database terenkripsi tidak valid" }
        }
    }

    private fun deleteSidecars(database: File) {
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
    }

    private fun deleteDatabaseFiles(database: File) {
        database.delete()
        deleteSidecars(database)
    }

    private fun hasDatabaseFiles(database: File): Boolean =
        database.exists() || File(database.path + "-wal").exists() || File(database.path + "-shm").exists()

    private fun copyDatabaseFiles(source: File, target: File) {
        require(source.isFile) { "Database sumber tidak tersedia" }
        copyFileAndSync(source, target)
        listOf("-wal", "-shm").forEach { suffix ->
            val sourceSidecar = File(source.path + suffix)
            if (sourceSidecar.isFile) copyFileAndSync(sourceSidecar, File(target.path + suffix))
        }
    }

    private fun copyFileAndSync(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun restoreDatabaseFiles(rollback: File, database: File) {
        deleteDatabaseFiles(database)
        require(rollback.renameTo(database)) { "Database lama tidak dapat dikembalikan" }
        moveSidecar(rollback, database, "-wal")
        moveSidecar(rollback, database, "-shm")
    }

    private fun moveSidecar(source: File, target: File, suffix: String) {
        val sourceSidecar = File(source.path + suffix)
        if (!sourceSidecar.exists()) return
        require(sourceSidecar.renameTo(File(target.path + suffix))) { "File pendamping database tidak dapat diamankan" }
    }

    private fun hasPrimaryRecoveryArtifacts(database: File): Boolean =
        database.parentFile?.listFiles()?.any { candidate ->
            candidate.absolutePath != database.absolutePath &&
                (candidate.name.startsWith(database.name) || candidate.name.startsWith(".${database.name}"))
        } == true

    private fun sqlString(value: String): String = value.replace("'", "''")

    private fun rawKeySpec(keyMaterial: ByteArray): ByteArray {
        val hex = buildString(keyMaterial.size * 2 + 3) {
            append("x'")
            keyMaterial.forEach { byte ->
                append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
                append(HEX_DIGITS[byte.toInt() and 0x0f])
            }
            append('\'')
        }
        return hex.toByteArray(Charsets.US_ASCII)
    }

    class EncryptionGuard internal constructor(
        private val encryptedDatabase: File,
        private val rollbackDatabase: File,
    ) {
        fun commit() {
            rollbackDatabase.delete()
            File(rollbackDatabase.path + "-wal").delete()
            File(rollbackDatabase.path + "-shm").delete()
        }

        fun rollback() {
            if (!rollbackDatabase.exists()) return
            encryptedDatabase.delete()
            File(encryptedDatabase.path + "-wal").delete()
            File(encryptedDatabase.path + "-shm").delete()
            require(rollbackDatabase.renameTo(encryptedDatabase)) { "Database lama tidak dapat dikembalikan" }
            moveSidecarIfPresent(rollbackDatabase, encryptedDatabase, "-wal")
            moveSidecarIfPresent(rollbackDatabase, encryptedDatabase, "-shm")
        }

        private fun moveSidecarIfPresent(source: File, target: File, suffix: String) {
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) require(sidecar.renameTo(File(target.path + suffix))) {
                "File pendamping database lama tidak dapat dikembalikan"
            }
        }
    }

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val PRIMARY_DATABASE_NAME = "kron-v4.db"
        private val SQLCIPHER_4_HOOK = object : SQLiteDatabaseHook {
            override fun preKey(connection: SQLiteConnection) {
                connection.execute("PRAGMA cipher_compatibility = 4", null, null)
            }

            override fun postKey(connection: SQLiteConnection) = Unit
        }
        private val SQLITE_HEADER = byteArrayOf(
            0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
        )
    }
}

class DatabaseRecoveryRequiredException(message: String) : IllegalStateException(message)

class DatabaseKeyProfileMismatchException(message: String) : IllegalStateException(message)
