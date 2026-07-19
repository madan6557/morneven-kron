package com.morneven.kron.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

data class DecryptedDriveSnapshot(
    val manifest: DriveSnapshotManifest,
    val payload: ByteArray,
)

interface DriveSnapshotCryptor {
    fun encrypt(manifest: DriveSnapshotManifest, payload: ByteArray, passphrase: CharArray): ByteArray
    fun decrypt(envelope: ByteArray, passphrase: CharArray): DecryptedDriveSnapshot
}

class InvalidDrivePassphraseException : IllegalArgumentException(
    "Passphrase Drive salah atau snapshot terenkripsi rusak",
)

class AesGcmDriveSnapshotCryptor(
    private val secureRandom: SecureRandom = SecureRandom(),
) : DriveSnapshotCryptor {
    override fun encrypt(
        manifest: DriveSnapshotManifest,
        payload: ByteArray,
        passphrase: CharArray,
    ): ByteArray {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase sinkronisasi minimal 12 karakter" }
        require(manifest.payloadSha256 == sha256(payload)) { "Checksum payload tidak sesuai manifest" }
        val header = SnapshotManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        require(header.size <= MAX_HEADER_BYTES) { "Manifest snapshot terlalu besar" }
        val salt = ByteArray(SALT_BYTES).also(secureRandom::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, payload, passphrase, salt, nonce, header, manifest.kdfIterations)
        return ByteArrayOutputStream().use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.writeInt(header.size)
                data.write(header)
                data.write(salt)
                data.write(nonce)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
            output.toByteArray()
        }
    }

    override fun decrypt(envelope: ByteArray, passphrase: CharArray): DecryptedDriveSnapshot {
        require(passphrase.size >= MIN_PASSPHRASE_LENGTH) { "Passphrase sinkronisasi minimal 12 karakter" }
        require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) { "Ukuran snapshot tidak valid" }
        val parts = DataInputStream(ByteArrayInputStream(envelope)).use { data ->
            require(data.readExact(MAGIC.size).contentEquals(MAGIC)) { "Format snapshot tidak dikenali" }
            val headerSize = data.readInt()
            require(headerSize in 1..MAX_HEADER_BYTES) { "Ukuran manifest tidak valid" }
            val header = data.readExact(headerSize)
            val salt = data.readExact(SALT_BYTES)
            val nonce = data.readExact(NONCE_BYTES)
            val payloadSize = data.readInt()
            require(payloadSize in 1..MAX_ENVELOPE_BYTES) { "Ukuran payload tidak valid" }
            val encrypted = data.readExact(payloadSize)
            require(data.read() == -1) { "Snapshot memiliki data tambahan yang tidak valid" }
            EnvelopeParts(header, salt, nonce, encrypted)
        }
        val manifest = SnapshotManifestCodec.decode(parts.header.toString(Charsets.UTF_8))
        val payload = try {
            crypt(
                Cipher.DECRYPT_MODE,
                parts.encrypted,
                passphrase,
                parts.salt,
                parts.nonce,
                parts.header,
                manifest.kdfIterations,
            )
        } catch (_: AEADBadTagException) {
            throw InvalidDrivePassphraseException()
        }
        require(MessageDigest.isEqual(hexToBytes(manifest.payloadSha256), hexToBytes(sha256(payload)))) {
            payload.fill(0)
            "Checksum snapshot tidak cocok"
        }
        return DecryptedDriveSnapshot(manifest, payload)
    }

    private fun crypt(
        mode: Int,
        bytes: ByteArray,
        passphrase: CharArray,
        salt: ByteArray,
        nonce: ByteArray,
        associatedData: ByteArray,
        iterations: Int,
    ): ByteArray {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        spec.clearPassword()
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(associatedData)
            cipher.doFinal(bytes)
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun DataInputStream.readExact(size: Int): ByteArray = ByteArray(size).also(::readFully)

    private data class EnvelopeParts(
        val header: ByteArray,
        val salt: ByteArray,
        val nonce: ByteArray,
        val encrypted: ByteArray,
    )

    companion object {
        private const val MIN_PASSPHRASE_LENGTH = 12
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_ENVELOPE_BYTES = 128 * 1024 * 1024
        private const val MIN_ENVELOPE_BYTES = 8 + 4 + 2 + SALT_BYTES + NONCE_BYTES + 4 + 16
        private val MAGIC = "KRONSYN1".toByteArray(Charsets.US_ASCII)

        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
            value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
