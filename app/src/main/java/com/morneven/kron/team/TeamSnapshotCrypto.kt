package com.morneven.kron.team

import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.SnapshotManifestCodec
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

data class OpenedTeamSnapshot(
    val manifest: DriveSnapshotManifest,
    val payload: ByteArray,
)

class InvalidTeamSnapshotException : IllegalArgumentException(
    "Team key tidak cocok atau snapshot rusak",
)

class TeamSnapshotCryptor(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    fun encrypt(manifest: DriveSnapshotManifest, payload: ByteArray, teamKey: ByteArray): ByteArray {
        require(manifest.protocolVersion == 2) { "Snapshot Team harus memakai protokol v2" }
        require(teamKey.size == TEAM_KEY_BYTES) { "Team key tidak valid" }
        require(manifest.payloadSha256 == AesGcmDriveSnapshotCryptor.sha256(payload)) {
            "Checksum payload Team tidak sesuai manifest"
        }
        val header = SnapshotManifestCodec.encode(manifest).toByteArray(Charsets.UTF_8)
        require(header.size in 1..MAX_HEADER_BYTES) { "Manifest snapshot Team terlalu besar" }
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val encrypted = crypt(Cipher.ENCRYPT_MODE, payload, teamKey, nonce, header)
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.write(MAGIC)
                    output.writeInt(header.size)
                    output.write(header)
                    output.write(nonce)
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                }
                bytes.toByteArray()
            }
        } finally {
            nonce.fill(0)
            encrypted.fill(0)
        }
    }

    fun decrypt(envelope: ByteArray, teamKey: ByteArray): OpenedTeamSnapshot {
        require(teamKey.size == TEAM_KEY_BYTES) { "Team key tidak valid" }
        require(envelope.size in MIN_ENVELOPE_BYTES..MAX_ENVELOPE_BYTES) { "Ukuran snapshot Team tidak valid" }
        val parts = DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            val magic = ByteArray(MAGIC.size).also(input::readFully)
            require(magic.contentEquals(MAGIC)) { "Format snapshot Team tidak dikenali" }
            val headerSize = input.readInt()
            require(headerSize in 1..MAX_HEADER_BYTES) { "Ukuran manifest snapshot Team tidak valid" }
            val header = ByteArray(headerSize).also(input::readFully)
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val encryptedSize = input.readInt()
            require(encryptedSize in 17..MAX_ENVELOPE_BYTES) { "Ukuran payload snapshot Team tidak valid" }
            val encrypted = ByteArray(encryptedSize).also(input::readFully)
            require(input.read() == -1) { "Snapshot Team memiliki data tambahan" }
            Parts(header, nonce, encrypted)
        }
        val manifest = SnapshotManifestCodec.decode(parts.header.toString(Charsets.UTF_8))
        require(manifest.protocolVersion == 2) { "Snapshot Team harus memakai protokol v2" }
        val payload = try {
            crypt(Cipher.DECRYPT_MODE, parts.encrypted, teamKey, parts.nonce, parts.header)
        } catch (_: AEADBadTagException) {
            throw InvalidTeamSnapshotException()
        } finally {
            parts.nonce.fill(0)
            parts.encrypted.fill(0)
        }
        val expected = manifest.payloadSha256.hexBytes()
        val actual = AesGcmDriveSnapshotCryptor.sha256(payload).hexBytes()
        if (!MessageDigest.isEqual(expected, actual)) {
            payload.fill(0)
            throw InvalidTeamSnapshotException()
        }
        return OpenedTeamSnapshot(manifest, payload)
    }

    private fun crypt(
        mode: Int,
        bytes: ByteArray,
        teamKey: ByteArray,
        nonce: ByteArray,
        header: ByteArray,
    ): ByteArray = Cipher.getInstance(AES_GCM).run {
        init(mode, SecretKeySpec(teamKey, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(MAGIC)
        updateAAD(header)
        doFinal(bytes)
    }

    private fun String.hexBytes(): ByteArray {
        require(matches(Regex("[0-9a-f]{64}"))) { "Checksum snapshot Team tidak valid" }
        return ByteArray(length / 2) { index -> substring(index * 2, index * 2 + 2).toInt(16).toByte() }
    }

    private data class Parts(
        val header: ByteArray,
        val nonce: ByteArray,
        val encrypted: ByteArray,
    )

    companion object {
        private const val TEAM_KEY_BYTES = 32
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val MAX_ENVELOPE_BYTES = 128 * 1024 * 1024
        private const val MIN_ENVELOPE_BYTES = 8 + 4 + 2 + NONCE_BYTES + 4 + 16
        private val MAGIC = "KRONTMS1".toByteArray(Charsets.US_ASCII)
    }
}
