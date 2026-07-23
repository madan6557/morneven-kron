package com.morneven.kron.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SQLiteDatabase

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyManager: DatabaseKeyManager,
) {
    private val continuityMarker: File
        get() = File(context.noBackupFilesDir, "security/database-continuity-v1.5.0.validated")

    data class DatabasePreparation(
        val keyMode: DatabaseKeyMode,
        val guard: EncryptionGuard?,
    )

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
        val resolvedMode: DatabaseKeyMode?,
    )

    init {
        SqlCipherLibrary.ensureLoaded()
    }

    fun openHelperFactory(
        dbPath: String,
        keyMode: DatabaseKeyMode,
    ): SupportSQLiteOpenHelper.Factory {
        val database = File(dbPath)
        if (
            !database.exists() &&
            (
                keyManager.isAnyKeyProfileProvisioned() ||
                    (keyManager.isProvisioned() && !keyManager.isNewDatabaseInitializationPending())
                )
        ) {
            throw DatabaseRecoveryRequiredException(
                "Database utama tidak ditemukan, tetapi metadata kunci KRON masih tersedia.",
            )
        }
        val root = keyManager.getOrCreateDatabasePassphrase()
        val effectiveKey = keyBytes(root, keyMode)
        root.fill(0)
        return net.zetetic.database.sqlcipher.SupportOpenHelperFactory(effectiveKey)
    }

    fun preparePrimaryDatabase(database: File): DatabasePreparation {
        if (!database.exists()) {
            if (
                keyManager.isAnyKeyProfileProvisioned() ||
                (keyManager.isProvisioned() && !keyManager.isNewDatabaseInitializationPending()) ||
                hasPrimaryRecoveryArtifacts(database)
            ) {
                throw DatabaseRecoveryRequiredException(
                    "Database utama tidak ditemukan, tetapi metadata kunci atau artefak pemulihan KRON masih tersedia.",
                )
            }
            return DatabasePreparation(DatabaseKeyMode.PASSPHRASE, null)
        }

        val existingMode = resolveKnownMode(database)
        if (existingMode != null) {
            if (isContinuityValidated()) {
                return DatabasePreparation(existingMode, null)
            }
            keyManager.preserveKeyMetadataForUpgrade()
            val rollback = preUpgradeCopy(database)
            if (!hasDatabaseFiles(rollback)) {
                copyDatabaseFiles(database, rollback)
                require(resolveKnownMode(rollback) == existingMode) {
                    "Salinan pra-upgrade database tidak dapat diverifikasi"
                }
            }
            return DatabasePreparation(
                existingMode,
                EncryptionGuard(database, rollback, ::markContinuityValidated),
            )
        }

        val plaintext = canOpenPlaintext(database)
        val emptyKey = !plaintext && canOpenEncrypted(database, ByteArray(0))
        if (!plaintext && !emptyKey) {
            throw DatabaseKeyUnavailableException(
                "Database KRON tidak cocok dengan passphrase 1.3.x, raw-key 1.4.x, plaintext, atau empty-key legacy.",
            )
        }

        val rollback = preUpgradeCopy(database)
        check(!hasDatabaseFiles(rollback)) {
            "Salinan pra-upgrade lama sudah tersedia. Migrasi baru diblokir."
        }
        keyManager.preserveKeyMetadataForUpgrade()
        copyDatabaseFiles(database, rollback)
        val sourceStaging = File(database.parentFile, ".${database.name}.source-staging-v147")
        val targetStaging = File(database.parentFile, ".${database.name}.encrypted-staging-v147")
        deleteDatabaseFiles(sourceStaging)
        deleteDatabaseFiles(targetStaging)
        val root = keyManager.getOrCreateForValidatedLegacyDatabase()
        try {
            copyDatabaseFiles(database, sourceStaging)
            exportToEncrypted(
                source = sourceStaging,
                sourceKey = ByteArray(0),
                target = targetStaging,
                targetRoot = root,
                targetMode = DatabaseKeyMode.PASSPHRASE,
            )
            validateEncrypted(targetStaging, keyBytes(root, DatabaseKeyMode.PASSPHRASE))
            deleteDatabaseFiles(database)
            require(targetStaging.renameTo(database)) { "Database terenkripsi tidak dapat diaktifkan" }
            syncDirectory(requireNotNull(database.parentFile))
            return DatabasePreparation(
                DatabaseKeyMode.PASSPHRASE,
                EncryptionGuard(database, rollback, ::markContinuityValidated),
            )
        } catch (error: Exception) {
            restoreDatabaseFiles(rollback, database, keepSource = true)
            throw error
        } finally {
            root.fill(0)
            deleteDatabaseFiles(sourceStaging)
            deleteDatabaseFiles(targetStaging)
        }
    }

    fun inspectPrimaryDatabase(database: File): DatabaseInspection {
        if (!database.exists()) {
            return DatabaseInspection(
                exists = false,
                isPlaintext = false,
                acceptsEmptyKey = false,
                keyEnvelopePresent = keyManager.isProvisioned(),
                keyEnvelopeReadable = false,
                keyProfilePresent = keyManager.isAnyKeyProfileProvisioned(),
                keyProfileValid = false,
                keyInitializationPending = keyManager.isNewDatabaseInitializationPending(),
                acceptsRawKey = false,
                acceptsPassphrase = false,
                hasPreEncryptionCopy = hasRecoverablePreEncryptionCopy(database),
                hasRecoveryArtifacts = hasPrimaryRecoveryArtifacts(database),
                resolvedMode = null,
            )
        }
        val plaintext = canOpenPlaintext(database)
        val emptyKey = !plaintext && canOpenEncrypted(database, ByteArray(0))
        val root = runCatching { keyManager.loadExistingDatabasePassphrase() }.getOrNull()
        val passphrase = root?.copyOf()
        val raw = root?.let { keyBytes(it, DatabaseKeyMode.RAW_HEX) }
        val passphraseMatches = passphrase?.let { canOpenEncrypted(database, it) } == true
        val rawMatches = raw?.let { canOpenEncrypted(database, it) } == true
        return try {
            DatabaseInspection(
                exists = true,
                isPlaintext = plaintext,
                acceptsEmptyKey = emptyKey,
                keyEnvelopePresent = keyManager.isProvisioned(),
                keyEnvelopeReadable = root != null,
                keyProfilePresent = keyManager.isAnyKeyProfileProvisioned(),
                keyProfileValid = keyManager.isExistingRawKeyProfileValid(),
                keyInitializationPending = keyManager.isNewDatabaseInitializationPending(),
                acceptsRawKey = rawMatches,
                acceptsPassphrase = passphraseMatches,
                hasPreEncryptionCopy = hasRecoverablePreEncryptionCopy(database),
                hasRecoveryArtifacts = hasPrimaryRecoveryArtifacts(database),
                resolvedMode = when {
                    passphraseMatches -> DatabaseKeyMode.PASSPHRASE
                    rawMatches -> DatabaseKeyMode.RAW_HEX
                    else -> null
                },
            )
        } finally {
            raw?.fill(0)
            passphrase?.fill(0)
            root?.fill(0)
        }
    }

    fun hasRecoverablePreEncryptionCopy(database: File): Boolean = recoveryCopies(database).any { candidate ->
        resolveKnownMode(candidate) != null || canOpenPlaintext(candidate) || canOpenEncrypted(candidate, ByteArray(0))
    }

    fun restorePreEncryptionCopy(database: File) {
        val rollback = recoveryCopies(database).firstOrNull { candidate ->
            resolveKnownMode(candidate) != null || canOpenPlaintext(candidate) || canOpenEncrypted(candidate, ByteArray(0))
        } ?: error("Salinan pra-upgrade tidak tersedia")
        require(hasDatabaseFiles(rollback)) { "Salinan pra-upgrade tidak tersedia" }
        require(
            resolveKnownMode(rollback) != null || canOpenPlaintext(rollback) ||
                canOpenEncrypted(rollback, ByteArray(0)),
        ) { "Salinan pra-upgrade tidak dapat diverifikasi" }
        keyManager.restoreKeyMetadataFromUpgradeCopy()
        val quarantine = File(database.parentFile, ".${database.name}.unreadable-${System.currentTimeMillis()}")
        deleteDatabaseFiles(quarantine)
        if (hasDatabaseFiles(database)) moveDatabaseFiles(database, quarantine)
        try {
            copyDatabaseFiles(rollback, database)
        } catch (error: Exception) {
            deleteDatabaseFiles(database)
            if (hasDatabaseFiles(quarantine)) moveDatabaseFiles(quarantine, database)
            throw error
        }
    }

    fun discardValidatedPreUpgradeCopy(database: File) {
        if (!isContinuityValidated()) return
        deleteDatabaseFiles(preUpgradeCopy(database))
    }

    fun markFreshDatabaseValidated() = markContinuityValidated()

    fun prepareValidatedRestoreKey() {
        val root = keyManager.getOrCreateDatabasePassphrase()
        root.fill(0)
    }

    fun exportPlaintext(encryptedDatabase: File, target: File) {
        deleteDatabaseFiles(target)
        if (canOpenPlaintext(encryptedDatabase)) {
            copyDatabaseFiles(encryptedDatabase, target)
            android.database.sqlite.SQLiteDatabase.openDatabase(
                target.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
            ).use { opened ->
                opened.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { it.moveToFirst() }
            }
            validatePlaintextDatabase(target)
            return
        }
        if (canOpenEncrypted(encryptedDatabase, ByteArray(0))) {
            exportToPlaintext(encryptedDatabase, ByteArray(0), target)
            validatePlaintextDatabase(target)
            return
        }
        val root = keyManager.loadExistingDatabasePassphrase()
            ?: throw DatabaseKeyUnavailableException("Kunci perangkat KRON tidak tersedia")
        try {
            val mode = resolveKnownMode(encryptedDatabase)
                ?: throw DatabaseKeyUnavailableException("Database tidak dapat dibuka dengan kunci KRON")
            val sourceKey = keyBytes(root, mode)
            try {
                exportToPlaintext(encryptedDatabase, sourceKey, target)
                validatePlaintextDatabase(target)
            } finally {
                sourceKey.fill(0)
            }
        } finally {
            root.fill(0)
        }
    }

    fun encryptPortableDatabase(plaintext: File, target: File) {
        deleteDatabaseFiles(target)
        validatePlaintextDatabase(plaintext)
        val root = keyManager.getOrCreateDatabasePassphrase()
        try {
            exportToEncrypted(
                source = plaintext,
                sourceKey = ByteArray(0),
                target = target,
                targetRoot = root,
                targetMode = DatabaseKeyMode.PASSPHRASE,
            )
            validateEncrypted(target, keyBytes(root, DatabaseKeyMode.PASSPHRASE))
        } finally {
            root.fill(0)
        }
    }

    fun validateEncrypted(database: File) {
        require(resolveKnownMode(database) != null) { "Database terenkripsi tidak valid" }
    }

    private fun isContinuityValidated(): Boolean = continuityMarker.isFile && runCatching {
        continuityMarker.readBytes().contentEquals(CONTINUITY_MAGIC)
    }.getOrDefault(false)

    @Synchronized
    private fun markContinuityValidated() {
        continuityMarker.parentFile?.mkdirs()
        val temporary = File(continuityMarker.parentFile, "${continuityMarker.name}.new")
        try {
            FileOutputStream(temporary, false).use { output ->
                output.write(CONTINUITY_MAGIC)
                output.flush()
                output.fd.sync()
            }
            if (!temporary.renameTo(continuityMarker)) {
                val atomicTemp = File(continuityMarker.parentFile, "${continuityMarker.name}.${System.nanoTime()}.tmp")
                try {
                    temporary.copyTo(atomicTemp, overwrite = true)
                    FileOutputStream(atomicTemp, true).use { it.fd.sync() }
                    require(atomicTemp.renameTo(continuityMarker)) { "Gagal mengganti marker kontinuitas secara atomik" }
                } finally {
                    atomicTemp.delete()
                }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun resolveKnownMode(database: File): DatabaseKeyMode? {
        if (!database.isFile) return null
        val root = runCatching { keyManager.loadExistingDatabasePassphrase() }.getOrNull() ?: return null
        val passphrase = root.copyOf()
        val raw = keyBytes(root, DatabaseKeyMode.RAW_HEX)
        return try {
            when {
                canOpenEncrypted(database, passphrase) -> DatabaseKeyMode.PASSPHRASE
                canOpenEncrypted(database, raw) -> DatabaseKeyMode.RAW_HEX
                else -> null
            }
        } finally {
            passphrase.fill(0)
            raw.fill(0)
            root.fill(0)
        }
    }

    private fun canOpenEncrypted(database: File, passphrase: ByteArray): Boolean {
        if (!database.isFile || database.length() < MIN_DATABASE_BYTES) return false
        return runCatching {
            SQLiteDatabase.openDatabase(
                database.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READONLY,
                null,
            ).use { opened ->
                val cipherValid = opened.query("PRAGMA cipher_integrity_check").use { cursor ->
                    var valid = true
                    while (cursor.moveToNext()) {
                        if (!cursor.getString(0).equals("ok", ignoreCase = true)) valid = false
                    }
                    valid
                }
                val foreignKeysValid = opened.query("PRAGMA foreign_key_check").use { cursor -> !cursor.moveToFirst() }
                cipherValid && foreignKeysValid
            }
        }.getOrDefault(false)
    }

    private fun canOpenPlaintext(database: File): Boolean = runCatching {
        if (!database.isFile) return@runCatching false
        android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { opened ->
            val integrity = opened.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            val foreignKeys = opened.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> !cursor.moveToFirst() }
            integrity && foreignKeys
        }
    }.getOrDefault(false)

    private fun validatePlaintextDatabase(file: File) {
        require(canOpenPlaintext(file)) { "Database KRON bukan SQLite plaintext yang valid" }
    }

    private fun exportToPlaintext(source: File, sourceKey: ByteArray, target: File) {
        deleteDatabaseFiles(target)
        SQLiteDatabase.openOrCreateDatabase(
            target,
            ByteArray(0),
            null,
            null,
            null,
        ).close()
        val opened = SQLiteDatabase.openDatabase(
            source.absolutePath,
            sourceKey,
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        opened.use { database ->
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
    }

    private fun exportToEncrypted(
        source: File,
        sourceKey: ByteArray,
        target: File,
        targetRoot: ByteArray,
        targetMode: DatabaseKeyMode,
    ) {
        require(sourceKey.isEmpty()) { "Sumber enkripsi harus berupa database portabel tanpa kunci" }
        deleteDatabaseFiles(target)
        val targetKey = keyBytes(targetRoot, targetMode)
        val opened = SQLiteDatabase.openOrCreateDatabase(
            target,
            targetKey,
            null,
            null,
            null,
        )
        try {
            opened.use { database ->
                val sourcePath = sqlString(source.absolutePath)
                database.rawExecSQL("ATTACH DATABASE '$sourcePath' AS portable KEY ''")
                try {
                    database.query("SELECT sqlcipher_export('main', 'portable')").use { cursor ->
                        require(cursor.moveToFirst()) { "Database tidak dapat dienkripsi" }
                    }
                    val version = database.query("PRAGMA portable.user_version").use { cursor ->
                        require(cursor.moveToFirst())
                        cursor.getInt(0)
                    }
                    database.rawExecSQL("PRAGMA user_version = $version")
                } finally {
                    database.rawExecSQL("DETACH DATABASE portable")
                }
            }
        } finally {
            targetKey.fill(0)
        }
    }

    private fun validateEncrypted(file: File, key: ByteArray) {
        try {
            require(canOpenEncrypted(file, key)) { "Integritas database terenkripsi tidak valid" }
        } finally {
            key.fill(0)
        }
    }

    private fun keyBytes(root: ByteArray, mode: DatabaseKeyMode): ByteArray = when (mode) {
        DatabaseKeyMode.PASSPHRASE -> root.copyOf()
        DatabaseKeyMode.RAW_HEX -> rawKeySpec(root)
    }

    private fun rawKeySpec(root: ByteArray): ByteArray =
        "x'${root.toHex()}'".toByteArray(Charsets.US_ASCII)

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        this@toHex.forEach { byte ->
            append(HEX_DIGITS[(byte.toInt() ushr 4) and 0x0f])
            append(HEX_DIGITS[byte.toInt() and 0x0f])
        }
    }

    private fun preUpgradeCopy(database: File): File =
        File(database.parentFile, ".${database.name}.pre-1.5.0")

    private fun recoveryCopies(database: File): List<File> = listOf(
        preUpgradeCopy(database),
        File(database.parentFile, ".${database.name}.pre-1.4.7"),
    )

    private fun hasPrimaryRecoveryArtifacts(database: File): Boolean =
        database.parentFile?.listFiles()?.any { candidate ->
            candidate.absolutePath != database.absolutePath &&
                (candidate.name.startsWith(database.name) || candidate.name.startsWith(".${database.name}"))
        } == true

    private fun hasDatabaseFiles(database: File): Boolean =
        database.exists() || File(database.path + "-wal").exists() || File(database.path + "-shm").exists()

    private fun copyDatabaseFiles(source: File, target: File) {
        require(source.isFile) { "Database sumber tidak tersedia" }
        deleteDatabaseFiles(target)
        copyFileAndSync(source, target)
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.isFile) copyFileAndSync(sidecar, File(target.path + suffix))
        }
        syncDirectory(requireNotNull(target.parentFile))
    }

    private fun moveDatabaseFiles(source: File, target: File) {
        require(source.renameTo(target)) { "Database tidak dapat dipindahkan" }
        listOf("-wal", "-shm").forEach { suffix ->
            val sidecar = File(source.path + suffix)
            if (sidecar.exists()) require(sidecar.renameTo(File(target.path + suffix))) {
                "File pendamping database tidak dapat dipindahkan"
            }
        }
        syncDirectory(requireNotNull(target.parentFile))
    }

    private fun restoreDatabaseFiles(source: File, target: File, keepSource: Boolean) {
        deleteDatabaseFiles(target)
        if (keepSource) copyDatabaseFiles(source, target) else moveDatabaseFiles(source, target)
    }

    private fun copyFileAndSync(source: File, target: File) {
        target.parentFile?.mkdirs()
        source.inputStream().use { input ->
            FileOutputStream(target, false).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun deleteDatabaseFiles(database: File) {
        database.delete()
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
    }

    private fun syncDirectory(directory: File) {
        runCatching {
            val descriptor = android.system.Os.open(directory.absolutePath, android.system.OsConstants.O_RDONLY, 0)
            try {
                android.system.Os.fsync(descriptor)
            } finally {
                android.system.Os.close(descriptor)
            }
        }.getOrThrow()
    }

    private fun sqlString(value: String): String = value.replace("'", "''")

    class EncryptionGuard internal constructor(
        private val liveDatabase: File,
        private val recoveryDatabase: File,
        private val onCommit: () -> Unit,
    ) {
        fun commit() = onCommit()

        fun rollback() {
            if (!recoveryDatabase.exists()) return
            val tempDb = File(liveDatabase.parentFile, ".${liveDatabase.name}.rollback-${System.nanoTime()}")
            try {
                recoveryDatabase.inputStream().use { input ->
                    FileOutputStream(tempDb, false).use { output ->
                        input.copyTo(output)
                        output.flush()
                        output.fd.sync()
                    }
                }
                listOf("-wal", "-shm").forEach { suffix ->
                    val source = File(recoveryDatabase.path + suffix)
                    if (source.isFile) {
                        source.inputStream().use { input ->
                            FileOutputStream(File(tempDb.path + suffix), false).use { output ->
                                input.copyTo(output)
                                output.flush()
                                output.fd.sync()
                            }
                        }
                    }
                }
                liveDatabase.delete()
                File(liveDatabase.path + "-wal").delete()
                File(liveDatabase.path + "-shm").delete()
                require(tempDb.renameTo(liveDatabase)) { "Gagal memulihkan database secara atomik" }
                listOf("-wal", "-shm").forEach { suffix ->
                    val tempSidecar = File(tempDb.path + suffix)
                    if (tempSidecar.isFile) {
                        require(tempSidecar.renameTo(File(liveDatabase.path + suffix))) {
                            "Gagal memulihkan file pendamping database"
                        }
                    }
                }
            } finally {
                tempDb.delete()
                File(tempDb.path + "-wal").delete()
                File(tempDb.path + "-shm").delete()
            }
        }
    }

    companion object {
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val MIN_DATABASE_BYTES = 16
        private val CONTINUITY_MAGIC = "KRONCONT150".toByteArray(Charsets.US_ASCII)
    }
}

class DatabaseRecoveryRequiredException(message: String) : IllegalStateException(message)

class DatabaseKeyProfileMismatchException(message: String) : IllegalStateException(message)
