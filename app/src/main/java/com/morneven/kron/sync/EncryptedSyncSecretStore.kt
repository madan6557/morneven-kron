package com.morneven.kron.sync

import android.content.Context
import com.morneven.kron.security.DatabaseKeyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import java.time.Instant
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stores only the Drive encryption passphrase. OAuth access tokens are never persisted here. */
@Singleton
class EncryptedSyncSecretStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyManager: DatabaseKeyManager,
) : SyncSecretProvider {
    private val mutex = Mutex()
    private var stagedPassphrase: CharArray? = null
    private val secretFile: File
        get() = File(context.noBackupFilesDir, "security/drive-sync-secret-v1.bin")
    private val crashSafeFile by lazy {
        CrashSafeSecretFile(secretFile, ::isValidSecretFile)
    }

    suspend fun stage(passphrase: CharArray) = withContext(Dispatchers.IO) {
        require(passphrase.size in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
            "Passphrase sinkronisasi harus 12 sampai 1024 karakter"
        }
        mutex.withLock {
            check(crashSafeFile.recover() == null) {
                "Passphrase Drive yang sudah tersimpan tidak dapat diganti sebagai rotasi"
            }
            stagedPassphrase?.fill('\u0000')
            stagedPassphrase = passphrase.copyOf()
        }
    }

    /**
     * Stages a replacement while keeping the currently committed secret intact.
     * The crash-safe file keeps the old value in its backup until the new value
     * has been verified and atomically committed.
     */
    suspend fun stageReplacement(passphrase: CharArray) = withContext(Dispatchers.IO) {
        require(passphrase.size in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
            "Passphrase sinkronisasi harus 12 sampai 1024 karakter"
        }
        mutex.withLock {
            crashSafeFile.recover()
            stagedPassphrase?.fill('\u0000')
            stagedPassphrase = passphrase.copyOf()
        }
    }

    suspend fun commitStaged(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val staged = stagedPassphrase ?: return@withLock false
            try {
                try {
                    storeLocked(staged)
                    return@withLock true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    // Do not rethrow; indicate failure to caller so UI can surface it.
                    return@withLock false
                }
            } finally {
                staged.fill('\u0000')
                stagedPassphrase = null
            }
        }
    }

    suspend fun discardStaged() = withContext(Dispatchers.IO) {
        mutex.withLock {
            stagedPassphrase?.fill('\u0000')
            stagedPassphrase = null
        }
    }

    suspend fun hasStaged(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { stagedPassphrase != null }
    }

    override suspend fun acquirePassphrase(): CharArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            stagedPassphrase?.let { return@withLock it.copyOf() }
            crashSafeFile.recover()?.let(::readPassphrase)
        }
    }

    suspend fun isStored(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock { crashSafeFile.recover() != null }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            stagedPassphrase?.fill('\u0000')
            stagedPassphrase = null
            crashSafeFile.clear(::eraseFile)
        }
    }

    private fun storeLocked(passphrase: CharArray) {
        val plaintext = encode(passphrase)
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val encrypted = try {
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                keyManager.deriveSubkey(KEY_LABEL),
                GCMParameterSpec(TAG_BITS, nonce),
            )
            cipher.updateAAD(MAGIC)
            cipher.doFinal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        val serialized = ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(MAGIC)
                output.write(nonce)
                output.writeInt(encrypted.size)
                output.write(encrypted)
            }
            bytes.toByteArray()
        }
        try {
            crashSafeFile.write(serialized)
        } finally {
            serialized.fill(0)
            encrypted.fill(0)
            nonce.fill(0)
        }
    }

    private fun readPassphrase(source: File): CharArray {
        val plaintext = FileInputStream(source).buffered().use { raw ->
            DataInputStream(raw).use { input ->
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "Penyimpanan passphrase sinkronisasi tidak valid" }
                val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
                val encryptedSize = input.readInt()
                require(encryptedSize in 1..MAX_ENCRYPTED_BYTES) { "Ukuran passphrase sinkronisasi tidak valid" }
                val encrypted = ByteArray(encryptedSize).also(input::readFully)
                require(input.read() == -1) { "Penyimpanan passphrase memiliki data tambahan" }
                try {
                    val cipher = Cipher.getInstance(AES_GCM)
                    cipher.init(
                        Cipher.DECRYPT_MODE,
                        keyManager.deriveSubkey(KEY_LABEL),
                        GCMParameterSpec(TAG_BITS, nonce),
                    )
                    cipher.updateAAD(MAGIC)
                    cipher.doFinal(encrypted)
                } finally {
                    nonce.fill(0)
                    encrypted.fill(0)
                }
            }
        }
        return try {
            decode(plaintext).also { decoded ->
                if (decoded.size !in MIN_PASSPHRASE_LENGTH..MAX_PASSPHRASE_LENGTH) {
                    decoded.fill('\u0000')
                    error("Passphrase sinkronisasi tidak valid")
                }
            }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun isValidSecretFile(candidate: File): Boolean = runCatching {
        readPassphrase(candidate).also { it.fill('\u0000') }
    }.isSuccess

    private fun eraseFile(file: File) {
        if (!file.exists()) return
        runCatching {
            RandomAccessFile(file, "rw").use { target ->
                val zeros = ByteArray(4096)
                var remaining = target.length()
                target.seek(0)
                while (remaining > 0) {
                    val count = minOf(zeros.size.toLong(), remaining).toInt()
                    target.write(zeros, 0, count)
                    remaining -= count
                }
                target.fd.sync()
            }
        }
        check(file.delete() || !file.exists()) { "Passphrase sinkronisasi tidak dapat dihapus" }
    }

    private fun encode(chars: CharArray): ByteArray {
        val buffer = Charsets.UTF_8.newEncoder().encode(CharBuffer.wrap(chars))
        return try {
            ByteArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill(0)
        }
    }

    private fun decode(bytes: ByteArray): CharArray {
        val buffer = Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes))
        return try {
            CharArray(buffer.remaining()).also(buffer::get)
        } finally {
            if (buffer.hasArray()) buffer.array().fill('\u0000')
        }
    }

    companion object {
        private const val MIN_PASSPHRASE_LENGTH = 12
        private const val MAX_PASSPHRASE_LENGTH = 1024
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val KEY_LABEL = "KRON Drive sync passphrase v1"
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val MAX_ENCRYPTED_BYTES = 8 * 1024
        private val MAGIC = "KRONSYN1".toByteArray(Charsets.US_ASCII)
    }
}

internal class CrashSafeSecretFile(
    private val target: File,
    private val validator: (File) -> Boolean,
) {
    private val temporary: File
        get() = File(requireNotNull(target.parentFile), "${target.name}.new")
    private val backup: File
        get() = File(requireNotNull(target.parentFile), "${target.name}.bak")

    fun recover(): File? {
        val targetValid = target.isFile && validator(target)
        if (targetValid) {
            deleteArtifact(temporary)
            deleteArtifact(backup)
            return target
        }

        val backupValid = backup.isFile && validator(backup)
        if (backupValid) {
            replace(backup, target)
            syncDirectory()
            deleteArtifact(temporary)
            check(validator(target)) { "Pemulihan passphrase sinkronisasi gagal" }
            return target
        }

        val temporaryValid = temporary.isFile && validator(temporary)
        if (!target.exists() && temporaryValid) {
            replace(temporary, target)
            syncDirectory()
            deleteArtifact(backup)
            check(validator(target)) { "Pemulihan passphrase sinkronisasi gagal" }
            return target
        }

        if (target.exists() || temporary.exists() || backup.exists()) {
            // Quarantine corrupt artifacts instead of throwing to avoid crash-loops.
            try {
                val quarantine = File(requireNotNull(target.parentFile), "quarantine")
                if (!quarantine.exists()) quarantine.mkdirs()
                listOf(target, temporary, backup).forEach { file ->
                    if (file.exists()) {
                        val dest = File(quarantine, "${file.name}.${Instant.now().toEpochMilli()}.corrupt")
                        try {
                            Files.move(file.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        } catch (_: Throwable) { }
                    }
                }
            } catch (_: Throwable) { }
            return null
        }
        return null
    }

    fun write(bytes: ByteArray) {
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "Folder keamanan sinkronisasi tidak dapat dibuat" }
        recover()
        writeAndSync(temporary, bytes)
        check(validator(temporary)) { "Passphrase sinkronisasi baru gagal diverifikasi" }

        if (target.exists()) {
            Files.copy(target.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING)
            FileOutputStream(backup, true).use { it.fd.sync() }
            check(validator(backup)) { "Salinan pemulihan passphrase sinkronisasi tidak valid" }
            syncDirectory()
        }

        try {
            replace(temporary, target)
            syncDirectory()
            check(validator(target)) { "Passphrase sinkronisasi baru gagal diverifikasi" }
        } catch (error: Throwable) {
            if (backup.isFile && validator(backup)) {
                replace(backup, target)
                syncDirectory()
            }
            throw error
        }
        deleteArtifact(backup)
        syncDirectory()
    }

    fun clear(eraser: (File) -> Unit) {
        listOf(target, temporary, backup).forEach(eraser)
        target.parentFile?.let(::syncDirectory)
    }

    private fun writeAndSync(file: File, bytes: ByteArray) {
        FileOutputStream(file, false).use { output ->
            output.write(bytes)
            output.flush()
            output.fd.sync()
        }
    }

    private fun replace(source: File, destination: File) {
        try {
            Files.move(
                source.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteArtifact(file: File) {
        check(!file.exists() || file.delete()) { "Artefak passphrase sinkronisasi tidak dapat dibersihkan" }
    }

    private fun syncDirectory() {
        target.parentFile?.let(::syncDirectory)
    }

    private fun syncDirectory(directory: File) {
        runCatching {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
