package com.morneven.kron.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.KronDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class BackupManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
) {
    suspend fun export(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "Password backup minimal 8 karakter" }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
        val databaseFile = context.getDatabasePath(DATABASE_NAME)
        require(databaseFile.exists()) { "Database belum tersedia" }
        val plain = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write("{\"format\":1,\"app\":\"KRON\",\"createdAt\":\"${Instant.now()}\",\"databaseSha256\":\"${sha256(databaseFile.readBytes())}\"}".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("database.sqlite"))
                databaseFile.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plain)
        context.contentResolver.openOutputStream(uri, "w")?.use { stream ->
            DataOutputStream(stream).use { data ->
                data.write(MAGIC)
                data.write(salt)
                data.write(nonce)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
        } ?: error("Tidak dapat membuka tujuan backup")
        password.fill('\u0000')
    }

    suspend fun stageRestore(uri: Uri, password: CharArray) = withContext(Dispatchers.IO) {
        require(password.size >= 8) { "Password backup minimal 8 karakter" }
        val encrypted = context.contentResolver.openInputStream(uri)?.use { stream ->
            DataInputStream(stream).use { data ->
                require(data.readExact(MAGIC.size).contentEquals(MAGIC)) { "Format backup tidak dikenali" }
                val salt = data.readExact(16)
                val nonce = data.readExact(12)
                val size = data.readInt()
                require(size in 1..MAX_BACKUP_BYTES) { "Ukuran backup tidak valid" }
                val payload = data.readExact(size)
                Triple(salt, nonce, payload)
            }
        } ?: error("Tidak dapat membuka backup")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, encrypted.first), GCMParameterSpec(128, encrypted.second))
        val plain = try {
            cipher.doFinal(encrypted.third)
        } catch (_: Exception) {
            throw IllegalArgumentException("Password salah atau backup rusak")
        } finally {
            password.fill('\u0000')
        }
        val candidate = File(context.cacheDir, "restore-candidate.db")
        val staged = File(context.filesDir, PENDING_FILE)
        candidate.delete()
        try {
            var manifest: String? = null
            ZipInputStream(ByteArrayInputStream(plain)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "manifest.json" -> manifest = zip.readBytes().toString(Charsets.UTF_8)
                        "database.sqlite" -> candidate.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                }
            }
            require(manifest?.contains("\"format\":1") == true && candidate.exists()) { "Isi backup tidak lengkap" }
            val expectedChecksum = Regex("\"databaseSha256\":\"([0-9a-f]+)\"").find(requireNotNull(manifest))?.groupValues?.get(1)
            require(expectedChecksum == sha256(candidate.readBytes())) { "Checksum database tidak cocok" }
            validateDatabase(candidate)
            candidate.copyTo(staged, overwrite = true)
        } finally {
            plain.fill(0)
            candidate.delete()
        }
    }

    private fun validateDatabase(file: File) {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        db.use {
            require(!it.rawQuery("PRAGMA foreign_key_check", null).use { cursor -> cursor.moveToFirst() }) { "Relasi database tidak valid" }
            val cash = scalar(it, "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines")
            val available = scalar(it, "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE bucket IN ('VAULT','UNALLOCATED','ROLLOVER') OR allocationId IS NOT NULL")
            require(cash == available) { "Invariant total aset backup tidak seimbang" }
            listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
                val channelCash = scalar(it, "SELECT COALESCE(SUM(c.amount),0) FROM cash_journal_lines c JOIN accounts a ON a.id=c.accountId WHERE a.fundingChannel='$channel'")
                val channelAvailable = scalar(it, "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE fundingChannel='$channel' AND (bucket IN ('VAULT','UNALLOCATED','ROLLOVER') OR allocationId IS NOT NULL)")
                require(channelCash == channelAvailable) { "Invariant kanal $channel tidak seimbang" }
            }
        }
    }

    private fun scalar(database: SQLiteDatabase, sql: String): Long = database.rawQuery(sql, null).use { cursor ->
        require(cursor.moveToFirst())
        cursor.getLong(0)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, 210_000, 256)
        return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
    }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    companion object {
        private const val DATABASE_NAME = "kron.db"
        private const val PENDING_FILE = "pending-restore.db"
        private const val MAX_BACKUP_BYTES = 250 * 1024 * 1024
        private val MAGIC = "KRONBKP1".toByteArray()

        fun applyPendingRestore(context: Context) {
            val staged = File(context.filesDir, PENDING_FILE)
            if (!staged.exists()) return
            val target = context.getDatabasePath(DATABASE_NAME)
            target.parentFile?.mkdirs()
            File(target.path + "-wal").delete()
            File(target.path + "-shm").delete()
            staged.copyTo(target, overwrite = true)
            staged.delete()
        }
    }
}
