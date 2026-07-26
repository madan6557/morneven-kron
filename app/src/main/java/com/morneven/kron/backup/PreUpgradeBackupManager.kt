package com.morneven.kron.backup

import android.content.Context
import android.net.Uri
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.security.DatabaseEncryptionManager
import com.morneven.kron.security.DatabaseKeyManager
import com.morneven.kron.security.EncryptedAttachmentStore
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

class PreUpgradeBackupManager(private val context: Context) {
    private val keyManager = DatabaseKeyManager(context)
    private val encryption = DatabaseEncryptionManager(context, keyManager)
    private val attachmentStore = EncryptedAttachmentStore(context, keyManager)

    fun export(uri: Uri, password: CharArray) {
        require(password.size >= MIN_PASSWORD_LENGTH) { "Password backup minimal 12 karakter" }
        val workspace = File(context.cacheDir, "pre-upgrade-${UUID.randomUUID()}")
        require(workspace.mkdirs()) { "Ruang kerja backup tidak dapat dibuat" }
        try {
            val portable = File(workspace, DATABASE_ENTRY)
            val primary = context.getDatabasePath(KronDatabase.DATABASE_NAME)
            encryption.exportPlaintext(primary, portable)
            validatePortableDatabase(portable)
            val attachments = extractAttachments(portable, workspace)
            val staged = File(workspace, "KRON-pre-upgrade-1.5.0.kronbackup")
            writeBackup(staged, portable, attachments, password)
            verifyBackup(staged, password)
            val output = context.contentResolver.openOutputStream(uri, "w")
                ?: error("Lokasi backup tidak dapat dibuka")
            output.buffered().use { destination ->
                staged.inputStream().buffered().use { source -> source.copyTo(destination) }
                destination.flush()
            }
            val destinationDigest = MessageDigest.getInstance("SHA-256")
            var destinationBytes = 0L
            val verificationInput = context.contentResolver.openInputStream(uri)
                ?: error("Backup tujuan tidak dapat diperiksa ulang")
            verificationInput.buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    destinationBytes += read
                    require(destinationBytes <= MAX_PACKAGE_BYTES) { "Backup tujuan melebihi batas aman" }
                    destinationDigest.update(buffer, 0, read)
                }
            }
            require(destinationBytes == staged.length()) { "Ukuran backup tujuan tidak cocok" }
            val destinationSha = destinationDigest.digest().joinToString("") { "%02x".format(it) }
            require(destinationSha == sha256(staged)) { "Checksum backup tujuan tidak cocok" }
        } finally {
            password.fill('\u0000')
            workspace.deleteRecursively()
        }
    }

    private fun extractAttachments(database: File, workspace: File): List<Attachment> {
        val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        return sqlite.use { opened ->
            val tableExists = opened.rawQuery(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='receipts'",
                null,
            ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
            if (!tableExists) return@use emptyList()
            opened.rawQuery(
                "SELECT storageId, localPath, encryptionVersion FROM receipts WHERE localPath IS NOT NULL ORDER BY storageId",
                null,
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val storageId = cursor.getString(0)
                        require(STORAGE_ID.matches(storageId)) { "Storage ID lampiran tidak valid" }
                        val source = File(cursor.getString(1))
                        require(source.isFile) { "Lampiran $storageId tidak ditemukan" }
                        val target = File(workspace, "attachment-$storageId.bin")
                        if (cursor.getInt(2) == EncryptedAttachmentStore.ENCRYPTION_VERSION) {
                            target.outputStream().use { attachmentStore.decrypt(source, it) }
                        } else {
                            source.inputStream().use { input ->
                                target.outputStream().use { output -> input.copyToLimited(output, MAX_ATTACHMENT_BYTES) }
                            }
                        }
                        require(target.length() <= MAX_ATTACHMENT_BYTES) { "Lampiran melebihi batas ukuran" }
                        add(
                            Attachment(
                                storageId = storageId,
                                file = target,
                                sha256 = sha256(target),
                            ),
                        )
                    }
                }
            }
        }
    }

    private fun validatePortableDatabase(database: File) {
        val sqlite = android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        )
        sqlite.use { opened ->
            require(opened.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }) { "Integritas database backup tidak valid" }
            require(!opened.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) {
                "Relasi database backup tidak valid"
            }
            val cash = scalar(opened, "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines")
            val available = scalar(
                opened,
                "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines " +
                    "WHERE bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL",
            )
            require(cash == available) { "Invariant total aset backup tidak seimbang" }
            listOf("CASH", "EBUDGET").forEach { channel ->
                val channelCash = scalar(
                    opened,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE fundingChannel='$channel'",
                )
                val channelAvailable = scalar(
                    opened,
                    "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines " +
                        "WHERE fundingChannel='$channel' AND " +
                        "(bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                )
                require(channelCash == channelAvailable) { "Invariant kanal $channel tidak seimbang" }
            }
        }
    }

    private fun scalar(database: android.database.sqlite.SQLiteDatabase, sql: String): Long =
        database.rawQuery(sql, null).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }

    private fun writeBackup(
        target: File,
        database: File,
        attachments: List<Attachment>,
        password: CharArray,
    ) {
        val salt = ByteArray(SALT_BYTES).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        FileOutputStream(target, false).buffered().use { raw ->
            val header = DataOutputStream(raw)
            header.write(MAGIC_V3)
            header.write(salt)
            header.write(nonce)
            header.writeInt(PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                deriveKey(password, salt, PBKDF2_ITERATIONS),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            CipherOutputStream(header, cipher).use { encrypted ->
                writePortablePackage(encrypted, database, attachments)
            }
        }
    }

    private fun writePortablePackage(
        output: OutputStream,
        database: File,
        attachments: List<Attachment>,
    ) {
        val schemaVersion = android.database.sqlite.SQLiteDatabase.openDatabase(
            database.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY,
        ).use { opened -> opened.rawQuery("PRAGMA user_version", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) } }
        val databaseSha = sha256(database)
        val manifest = JSONObject()
            .put("format", PORTABLE_FORMAT)
            .put("app", "KRON")
            .put("createdAt", Instant.now().toString())
            .put("schemaVersion", schemaVersion)
            .put("databaseSha256", databaseSha)
            .put("databaseBytes", database.length())
            .put("attachmentCount", attachments.size)
            .put("datasetId", JSONObject.NULL)
            .put("generation", 0)
        val checksums = buildString {
            append(DATABASE_ENTRY).append('\t').append(databaseSha).append('\t').append(database.length()).append('\n')
            attachments.forEach { attachment ->
                append("attachments/").append(attachment.storageId).append(".bin")
                    .append('\t').append(attachment.sha256).append('\t').append(attachment.file.length()).append('\n')
            }
        }
        ZipOutputStream(output).use { zip ->
            zip.writeBytes(MANIFEST_ENTRY, manifest.toString().toByteArray(Charsets.UTF_8))
            zip.writeBytes(CHECKSUM_ENTRY, checksums.toByteArray(Charsets.UTF_8))
            zip.putNextEntry(stableEntry(DATABASE_ENTRY))
            database.inputStream().use { it.copyToLimited(zip, MAX_DATABASE_BYTES) }
            zip.closeEntry()
            attachments.forEach { attachment ->
                zip.putNextEntry(stableEntry("attachments/${attachment.storageId}.bin"))
                attachment.file.inputStream().use { it.copyToLimited(zip, MAX_ATTACHMENT_BYTES) }
                zip.closeEntry()
            }
        }
    }

    private fun verifyBackup(source: File, password: CharArray) {
        val extracted = File(source.parentFile, "verify.zip")
        try {
            DataInputStream(source.inputStream().buffered()).use { input ->
                val magic = ByteArray(MAGIC_V3.size).also(input::readFully)
                require(magic.contentEquals(MAGIC_V3)) { "Header backup tidak valid" }
                val salt = ByteArray(SALT_BYTES).also(input::readFully)
                val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
                val iterations = input.readInt()
                require(iterations == PBKDF2_ITERATIONS) { "Parameter backup tidak valid" }
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    deriveKey(password, salt, iterations),
                    GCMParameterSpec(GCM_TAG_BITS, nonce),
                )
                extracted.outputStream().use { output ->
                    CipherInputStream(input, cipher).use { encrypted ->
                        encrypted.copyToLimited(output, MAX_PACKAGE_BYTES)
                    }
                }
            }
            val names = mutableSetOf<String>()
            val actual = linkedMapOf<String, Pair<Long, String>>()
            var manifest: JSONObject? = null
            var checksumIndex: String? = null
            ZipInputStream(extracted.inputStream().buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    require(
                        entry.name == MANIFEST_ENTRY || entry.name == CHECKSUM_ENTRY ||
                            entry.name == DATABASE_ENTRY || ATTACHMENT_ENTRY.matches(entry.name),
                    ) { "Backup memiliki file yang tidak dikenal" }
                    require(names.add(entry.name)) { "Backup memiliki file ganda" }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val output = ByteArrayOutputStream()
                    var bytes = 0L
                    val sink = object : OutputStream() {
                        override fun write(value: Int) {
                            digest.update(value.toByte())
                            if (entry.name == MANIFEST_ENTRY || entry.name == CHECKSUM_ENTRY) output.write(value)
                            bytes++
                        }

                        override fun write(buffer: ByteArray, offset: Int, length: Int) {
                            digest.update(buffer, offset, length)
                            if (entry.name == MANIFEST_ENTRY || entry.name == CHECKSUM_ENTRY) {
                                output.write(buffer, offset, length)
                            }
                            bytes += length
                        }
                    }
                    val limit = when (entry.name) {
                        MANIFEST_ENTRY -> MAX_MANIFEST_BYTES
                        CHECKSUM_ENTRY -> MAX_CHECKSUM_BYTES
                        DATABASE_ENTRY -> MAX_DATABASE_BYTES
                        else -> MAX_ATTACHMENT_BYTES
                    }
                    zip.copyToLimited(sink, limit)
                    actual[entry.name] = bytes to digest.digest().joinToString("") { "%02x".format(it) }
                    if (entry.name == MANIFEST_ENTRY) manifest = JSONObject(output.toString(Charsets.UTF_8.name()))
                    if (entry.name == CHECKSUM_ENTRY) checksumIndex = output.toString(Charsets.UTF_8.name())
                    zip.closeEntry()
                }
            }
            require(MANIFEST_ENTRY in names && CHECKSUM_ENTRY in names && DATABASE_ENTRY in names) {
                "Backup hasil verifikasi tidak lengkap"
            }
            val parsedChecksums = requireNotNull(checksumIndex).lineSequence()
                .filter(String::isNotBlank)
                .associate { line ->
                    val parts = line.split('\t')
                    require(parts.size == 3) { "Indeks checksum backup tidak valid" }
                    parts[0] to (parts[2].toLong() to parts[1])
                }
            require(parsedChecksums.keys == actual.keys - setOf(MANIFEST_ENTRY, CHECKSUM_ENTRY)) {
                "Indeks checksum backup tidak lengkap"
            }
            require(parsedChecksums.all { (name, expected) -> actual[name] == expected }) {
                "Checksum isi backup tidak cocok"
            }
            val verifiedManifest = requireNotNull(manifest)
            require(verifiedManifest.optInt("format") == PORTABLE_FORMAT) { "Format backup tidak valid" }
            require(verifiedManifest.optString("databaseSha256") == actual.getValue(DATABASE_ENTRY).second) {
                "Checksum database pada manifest tidak cocok"
            }
            require(
                verifiedManifest.optInt("attachmentCount", -1) ==
                    actual.keys.count { ATTACHMENT_ENTRY.matches(it) },
            ) { "Jumlah lampiran pada manifest tidak cocok" }
        } finally {
            extracted.delete()
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        return try {
            SecretKeySpec(
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded,
                "AES",
            )
        } finally {
            spec.clearPassword()
        }
    }

    private fun ZipOutputStream.writeBytes(name: String, bytes: ByteArray) {
        putNextEntry(stableEntry(name))
        write(bytes)
        closeEntry()
    }

    private fun stableEntry(name: String): ZipEntry = ZipEntry(name).apply { time = 0L }

    private fun java.io.InputStream.copyToLimited(output: OutputStream, limit: Long) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            require(total <= limit) { "Isi backup melebihi batas aman" }
            output.write(buffer, 0, read)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Attachment(
        val storageId: String,
        val file: File,
        val sha256: String,
    )

    companion object {
        private const val MIN_PASSWORD_LENGTH = 12
        private const val PBKDF2_ITERATIONS = 600_000
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val PORTABLE_FORMAT = 2
        private const val DATABASE_ENTRY = "database.sqlite"
        private const val MANIFEST_ENTRY = "manifest.json"
        private const val CHECKSUM_ENTRY = "checksums.tsv"
        private const val MAX_DATABASE_BYTES = 250L * 1024 * 1024
        private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
        private const val MAX_PACKAGE_BYTES = 500L * 1024 * 1024
        private const val MAX_MANIFEST_BYTES = 64L * 1024
        private const val MAX_CHECKSUM_BYTES = 1024L * 1024
        private val MAGIC_V3 = "KRONBKP3".toByteArray(Charsets.US_ASCII)
        private val STORAGE_ID = Regex("[A-Za-z0-9_-]{8,128}")
        private val ATTACHMENT_ENTRY = Regex("attachments/([A-Za-z0-9_-]{8,128})\\.bin")
    }
}
