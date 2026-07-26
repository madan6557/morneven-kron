package com.morneven.kron.team

import android.content.Context
import com.morneven.kron.security.DatabaseKeyManager
import com.morneven.kron.sync.CrashSafeSecretFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class TeamKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val keyManager: DatabaseKeyManager,
) {
    private val mutex = Mutex()

    suspend fun store(teamId: String, teamKey: ByteArray) = withContext(Dispatchers.IO) {
        require(teamKey.size == TEAM_KEY_BYTES) { "Team key tidak valid" }
        mutex.withLock {
            val file = keyFile(teamId)
            val crashSafe = CrashSafeSecretFile(file) { candidate -> isValid(teamId, candidate) }
            check(crashSafe.recover() == null) { "Team key sudah terpasang pada perangkat" }
            val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
            val encrypted = Cipher.getInstance(AES_GCM).run {
                init(Cipher.ENCRYPT_MODE, keyManager.deriveSubkey(keyLabel(teamId)), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad(teamId))
                doFinal(teamKey)
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
                crashSafe.write(serialized)
            } finally {
                nonce.fill(0)
                encrypted.fill(0)
                serialized.fill(0)
            }
        }
    }

    suspend fun acquire(teamId: String): ByteArray? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = keyFile(teamId)
            CrashSafeSecretFile(file) { candidate -> isValid(teamId, candidate) }
                .recover()
                ?.let { decrypt(teamId, it) }
        }
    }

    suspend fun clear(teamId: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = keyFile(teamId)
            CrashSafeSecretFile(file) { candidate -> isValid(teamId, candidate) }.clear(::eraseFile)
        }
    }

    private fun decrypt(teamId: String, source: File): ByteArray = DataInputStream(
        source.inputStream().buffered(),
    ).use { input ->
        val magic = ByteArray(MAGIC.size).also(input::readFully)
        require(magic.contentEquals(MAGIC)) { "Penyimpanan Team key tidak valid" }
        val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
        val encryptedSize = input.readInt()
        require(encryptedSize in 17..128) { "Ukuran Team key tidak valid" }
        val encrypted = ByteArray(encryptedSize).also(input::readFully)
        require(input.read() == -1) { "Penyimpanan Team key memiliki data tambahan" }
        try {
            Cipher.getInstance(AES_GCM).run {
                init(Cipher.DECRYPT_MODE, keyManager.deriveSubkey(keyLabel(teamId)), GCMParameterSpec(TAG_BITS, nonce))
                updateAAD(aad(teamId))
                doFinal(encrypted)
            }.also { require(it.size == TEAM_KEY_BYTES) { "Team key tidak valid" } }
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
        }
    }

    private fun isValid(teamId: String, candidate: File): Boolean = runCatching {
        decrypt(teamId, candidate).also { it.fill(0) }
    }.isSuccess

    private fun keyFile(teamId: String): File {
        require(teamId.length in 1..512) { "Team ID tidak valid" }
        val directory = File(context.noBackupFilesDir, "security/team-keys-v1")
        val name = TeamInvitationCodec.sha256(teamId.toByteArray(Charsets.UTF_8))
        return File(directory, "$name.bin")
    }

    private fun keyLabel(teamId: String): String = "KRON Team key v1:${TeamInvitationCodec.sha256(aad(teamId))}"

    private fun aad(teamId: String): ByteArray = "KRONTEAMKEY1:$teamId".toByteArray(Charsets.UTF_8)

    private fun eraseFile(file: File) {
        if (!file.exists()) return
        runCatching {
            RandomAccessFile(file, "rw").use { target ->
                val zeros = ByteArray(4096)
                var remaining = target.length()
                target.seek(0)
                while (remaining > 0) {
                    val count = minOf(remaining, zeros.size.toLong()).toInt()
                    target.write(zeros, 0, count)
                    remaining -= count
                }
                target.fd.sync()
            }
        }
        check(file.delete() || !file.exists()) { "Team key tidak dapat dihapus" }
    }

    companion object {
        private const val TEAM_KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private val MAGIC = "KRONTKY1".toByteArray(Charsets.US_ASCII)
    }
}
