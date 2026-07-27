package com.morneven.kron.team

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

data class TeamBlobReference(val sha256: String, val byteSize: Long)

@Singleton
class TeamBlobStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val drive: TeamDriveRestClient,
) {
    suspend fun upload(
        accessToken: String,
        folderId: String,
        teamId: String,
        plaintext: ByteArray,
        teamKey: ByteArray,
    ): TeamBlobReference {
        val sha256 = sha256(plaintext).hex()
        val blobName = "blob-$sha256.bin"
        val existing = drive.listFiles(accessToken, folderId, teamId)
            .firstOrNull { it.name == blobName }
        if (existing != null) return TeamBlobReference(sha256, existing.sizeBytes)
        val encrypted = encryptBlob(plaintext, teamKey, sha256)
        val file = drive.uploadImmutable(
            accessToken = accessToken,
            folderId = folderId,
            name = blobName,
            kind = "blob",
            teamId = teamId,
            bytes = encrypted,
            extraProperties = mapOf("sha256" to sha256),
        )
        return TeamBlobReference(sha256, file.sizeBytes)
    }

    suspend fun download(
        accessToken: String,
        folderId: String,
        teamId: String,
        sha256: String,
        teamKey: ByteArray,
    ): ByteArray {
        val blobName = "blob-$sha256.bin"
        val files = drive.listFiles(accessToken, folderId, teamId)
        val file = files.firstOrNull { it.name == blobName }
            ?: throw IllegalStateException("Blob $sha256 tidak ditemukan di Drive")
        val encrypted = drive.download(accessToken, file.fileId)
        return decryptBlob(encrypted, teamKey, sha256)
    }

    suspend fun listBlobs(accessToken: String, folderId: String, teamId: String): List<TeamBlobReference> =
        drive.listFiles(accessToken, folderId, teamId)
            .filter { it.name.startsWith("blob-") && it.name.endsWith(".bin") }
            .mapNotNull { file ->
                file.appProperties["sha256"]?.let { sha ->
                    TeamBlobReference(sha, file.sizeBytes)
                }
            }

    suspend fun delete(accessToken: String, fileId: String) = drive.delete(accessToken, fileId)

    suspend fun gc(
        accessToken: String,
        folderId: String,
        teamId: String,
        referencedShas: Set<String>,
    ): Int {
        var deleted = 0
        for (blob in listBlobs(accessToken, folderId, teamId)) {
            if (blob.sha256 !in referencedShas) {
                val files = drive.listFiles(accessToken, folderId, teamId)
                    .filter { it.appProperties["sha256"] == blob.sha256 }
                for (file in files) {
                    drive.delete(accessToken, file.fileId)
                    deleted++
                }
            }
        }
        return deleted
    }

    companion object {
        fun encryptBlob(plaintext: ByteArray, teamKey: ByteArray, contentKey: String): ByteArray {
            val nonce = deterministicNonce(contentKey)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(teamKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            val encrypted = cipher.doFinal(plaintext)
            val sizeBytes = packInt(encrypted.size)
            return ByteArrayOutputStream().also { bos ->
                bos.write(MAGIC)
                bos.write(nonce)
                bos.write(sizeBytes)
                bos.write(encrypted)
            }.toByteArray()
        }

        fun decryptBlob(encrypted: ByteArray, teamKey: ByteArray, contentKey: String): ByteArray {
            require(encrypted.size >= MIN_BLOB_BYTES) { "Blob Team terlalu kecil" }
            var offset = 0
            val magic = slice(encrypted, offset, MAGIC.size)
            require(magic.contentEquals(MAGIC)) { "Format blob Team tidak dikenali" }
            offset += MAGIC.size
            val nonce = slice(encrypted, offset, NONCE_BYTES)
            offset += NONCE_BYTES
            val encSize = unpackInt(slice(encrypted, offset, 4))
            offset += 4
            val ciphertext = slice(encrypted, offset, encSize)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(teamKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            return cipher.doFinal(ciphertext)
        }

        private fun deterministicNonce(contentKey: String): ByteArray {
            val hash = sha256(sha256(contentKey.toByteArray(Charsets.UTF_8)).hex().toByteArray(Charsets.UTF_8))
            return slice(hash, 0, NONCE_BYTES)
        }

        private fun slice(src: ByteArray, start: Int, len: Int): ByteArray {
            val result = ByteArray(len)
            System.arraycopy(src, start, result, 0, len)
            return result
        }

        private fun packInt(value: Int): ByteArray = byteArrayOf(
            (value shr 24).toByte(), (value shr 16).toByte(),
            (value shr 8).toByte(), value.toByte(),
        )

        private fun unpackInt(bytes: ByteArray): Int =
            (bytes[0].toInt() and 0xFF shl 24) or
            (bytes[1].toInt() and 0xFF shl 16) or
            (bytes[2].toInt() and 0xFF shl 8) or
            (bytes[3].toInt() and 0xFF)

        private fun sha256(bytes: ByteArray): ByteArray =
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes)

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        private val MAGIC = "KRONTBLO".toByteArray(Charsets.US_ASCII)
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private val MIN_BLOB_BYTES = MAGIC.size + NONCE_BYTES + 4 + 17
    }

}
