package com.morneven.kron.security

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Singleton
class DatabaseEncryptionManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyManager: DatabaseKeyManager,
) {
    init {
        SqlCipherLibrary.ensureLoaded()
    }

    fun openHelperFactory(): SupportOpenHelperFactory {
        val passphrase = keyManager.getOrCreateDatabasePassphrase()
        return SupportOpenHelperFactory(passphrase)
    }

    fun preparePrimaryDatabase(database: File): EncryptionGuard? {
        val rollback = File(database.parentFile, "${database.name}.pre-encryption")
        if (!database.exists() && rollback.exists()) {
            require(rollback.renameTo(database)) { "Database lama tidak dapat dipulihkan" }
        }
        if (!database.exists()) return null
        val passphrase = keyManager.getOrCreateDatabasePassphrase()
        if (tryOpenEncrypted(database, passphrase)) {
            passphrase.fill(0)
            return if (rollback.exists()) EncryptionGuard(database, rollback) else null
        }

        checkpointPlaintext(database)
        val candidate = File(database.parentFile, "${database.name}.encrypted-new")
        candidate.delete()
        try {
            encryptPlaintext(database, candidate, passphrase)
            validateEncrypted(candidate, passphrase)
            rollback.delete()
            require(database.renameTo(rollback)) { "Database lama tidak dapat diamankan" }
            try {
                require(candidate.renameTo(database)) { "Database terenkripsi tidak dapat dipasang" }
            } catch (error: Exception) {
                rollback.renameTo(database)
                throw error
            }
            deleteSidecars(database)
            return EncryptionGuard(database, rollback)
        } finally {
            passphrase.fill(0)
            candidate.delete()
        }
    }

    fun exportPlaintext(encryptedDatabase: File, target: File) {
        target.delete()
        val passphrase = keyManager.getOrCreateDatabasePassphrase()
        try {
            val source = SQLiteDatabase.openDatabase(
                encryptedDatabase.absolutePath,
                passphrase,
                null,
                SQLiteDatabase.OPEN_READWRITE,
                null,
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
            validatePlaintextHeader(target)
        } finally {
            passphrase.fill(0)
            if (!target.exists()) target.delete()
        }
    }

    fun encryptPortableDatabase(plaintext: File, target: File) {
        target.delete()
        val passphrase = keyManager.getOrCreateDatabasePassphrase()
        try {
            encryptPlaintext(plaintext, target, passphrase)
            validateEncrypted(target, passphrase)
        } finally {
            passphrase.fill(0)
        }
    }

    fun validateEncrypted(database: File) {
        val passphrase = keyManager.getOrCreateDatabasePassphrase()
        try {
            validateEncrypted(database, passphrase)
        } finally {
            passphrase.fill(0)
        }
    }

    private fun tryOpenEncrypted(database: File, passphrase: ByteArray): Boolean {
        if (!database.exists() || database.length() < SQLITE_HEADER.size) return false
        return try {
            val header = database.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also(input::read) }
            if (!header.contentEquals(SQLITE_HEADER)) return true
            SQLiteDatabase.openDatabase(
                database.absolutePath, passphrase, null, SQLiteDatabase.OPEN_READONLY, null,
            ).use { true }
        } catch (_: Exception) {
            false
        }
    }

    private fun checkpointPlaintext(database: File) {
        val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READWRITE,
        )
        sqlite.use { it.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor -> cursor.moveToFirst() } }
    }

    private fun encryptPlaintext(source: File, target: File, passphrase: ByteArray) {
        validatePlaintextHeader(source)
        val plaintext = SQLiteDatabase.openDatabase(
            source.absolutePath,
            ByteArray(0),
            null,
            SQLiteDatabase.OPEN_READWRITE,
            null,
        )
        plaintext.use { database ->
            val targetPath = sqlString(target.absolutePath)
            val keyLiteral = passphrase.joinToString("") { "%02x".format(it) }
            database.rawExecSQL("ATTACH DATABASE '$targetPath' AS encrypted KEY \"x'$keyLiteral'\"")
            try {
                database.query("SELECT sqlcipher_export('encrypted')").use { cursor ->
                    require(cursor.moveToFirst()) { "Database tidak dapat dienkripsi" }
                }
                val version = database.query("PRAGMA user_version").use { cursor ->
                    require(cursor.moveToFirst())
                    cursor.getInt(0)
                }
                database.rawExecSQL("PRAGMA encrypted.user_version = $version")
            } finally {
                database.rawExecSQL("DETACH DATABASE encrypted")
            }
        }
    }

    private fun validateEncrypted(file: File, passphrase: ByteArray) {
        val database = SQLiteDatabase.openDatabase(
            file.absolutePath,
            passphrase,
            null,
            SQLiteDatabase.OPEN_READONLY,
            null,
        )
        database.use {
            val valid = it.query("PRAGMA cipher_integrity_check").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            require(valid) { "Integritas database terenkripsi tidak valid" }
        }
    }

    private fun validatePlaintextHeader(file: File) {
        require(file.exists() && file.length() >= SQLITE_HEADER.size) { "Database portabel tidak valid" }
        val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also(input::read) }
        require(header.contentEquals(SQLITE_HEADER)) { "Database portabel bukan SQLite plaintext" }
    }

    private fun deleteSidecars(database: File) {
        File(database.path + "-wal").delete()
        File(database.path + "-shm").delete()
    }

    private fun sqlString(value: String): String = value.replace("'", "''")

    class EncryptionGuard internal constructor(
        private val encryptedDatabase: File,
        private val rollbackDatabase: File,
    ) {
        fun commit() {
            rollbackDatabase.delete()
        }

        fun rollback() {
            if (!rollbackDatabase.exists()) return
            encryptedDatabase.delete()
            require(rollbackDatabase.renameTo(encryptedDatabase)) { "Database lama tidak dapat dikembalikan" }
        }
    }

    companion object {
        private val SQLITE_HEADER = byteArrayOf(
            0x53, 0x51, 0x4c, 0x69, 0x74, 0x65, 0x20, 0x66,
            0x6f, 0x72, 0x6d, 0x61, 0x74, 0x20, 0x33, 0x00,
        )
    }
}
