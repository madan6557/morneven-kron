package com.morneven.kron.security

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

data class StoredAttachment(
    val file: File,
    val byteSize: Long,
    val sha256: String,
    val nonce: String,
    val encryptionVersion: Int = EncryptedAttachmentStore.ENCRYPTION_VERSION,
)

@Singleton
class EncryptedAttachmentStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyManager: DatabaseKeyManager,
) {
    fun destination(storageId: String): File {
        require(STORAGE_ID.matches(storageId)) { "Storage ID lampiran tidak valid" }
        return File(context.noBackupFilesDir, "receipts/$storageId.kat")
    }

    fun encrypt(input: InputStream, storageId: String): StoredAttachment {
        val target = destination(storageId)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.new")
        return try {
            val result = encryptTo(input, temporary)
            replace(temporary, target)
            result.copy(file = target)
        } finally {
            temporary.delete()
        }
    }

    fun encryptTo(input: InputStream, target: File): StoredAttachment {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val key = keyManager.deriveSubkey(ATTACHMENT_KEY_LABEL)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { raw ->
            DataOutputStream(raw).use { data ->
                data.write(MAGIC)
                data.write(nonce)
                CipherOutputStream(data, cipher).use { encrypted ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        byteSize += read
                        require(byteSize <= MAX_ATTACHMENT_BYTES) { "Lampiran melebihi batas ukuran" }
                        digest.update(buffer, 0, read)
                        encrypted.write(buffer, 0, read)
                    }
                }
            }
        }
        FileOutputStream(target, true).use { it.fd.sync() }
        return StoredAttachment(
            file = target,
            byteSize = byteSize,
            sha256 = digest.digest().toHex(),
            nonce = Base64.encodeToString(nonce, Base64.NO_WRAP),
        )
    }

    fun decrypt(source: File, output: OutputStream) {
        source.inputStream().buffered().use { raw ->
            DataInputStream(raw).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC)) { "Format lampiran terenkripsi tidak dikenali" }
                val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyManager.deriveSubkey(ATTACHMENT_KEY_LABEL),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                CipherInputStream(data, cipher).use { encrypted ->
                    encrypted.copyToWithLimit(output, MAX_ATTACHMENT_BYTES)
                }
            }
        }
    }

    fun inspect(source: File): StoredAttachment {
        val digest = MessageDigest.getInstance("SHA-256")
        var byteSize = 0L
        var encodedNonce = ""
        source.inputStream().buffered().use { raw ->
            DataInputStream(raw).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC)) { "Format lampiran terenkripsi tidak dikenali" }
                val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
                encodedNonce = Base64.encodeToString(nonce, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    keyManager.deriveSubkey(ATTACHMENT_KEY_LABEL),
                    GCMParameterSpec(TAG_BITS, nonce),
                )
                CipherInputStream(data, cipher).use { encrypted ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = encrypted.read(buffer)
                        if (read < 0) break
                        byteSize += read
                        require(byteSize <= MAX_ATTACHMENT_BYTES) { "Lampiran melebihi batas ukuran" }
                        digest.update(buffer, 0, read)
                    }
                }
            }
        }
        return StoredAttachment(
            file = source,
            byteSize = byteSize,
            sha256 = digest.digest().toHex(),
            nonce = encodedNonce,
        )
    }

    private fun replace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun InputStream.copyToWithLimit(output: OutputStream, limit: Long) {
        var total = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = read(buffer)
            if (read < 0) return
            total += read
            require(total <= limit) { "Lampiran melebihi batas ukuran" }
            output.write(buffer, 0, read)
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        const val ENCRYPTION_VERSION = 1
        const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val ATTACHMENT_KEY_LABEL = "KRON attachment encryption v1"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private val MAGIC = "KRONATT1".toByteArray(Charsets.US_ASCII)
        private val STORAGE_ID = Regex("[A-Za-z0-9_-]{8,128}")
    }
}
