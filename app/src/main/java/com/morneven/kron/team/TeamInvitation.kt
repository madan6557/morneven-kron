package com.morneven.kron.team

import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.data.TeamRole
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class TeamInvitation internal constructor(
    val teamId: String,
    val folderId: String,
    val inviteId: String,
    secret: ByteArray,
    val targetEmailHash: String,
    val role: String,
    val expiresAtEpochMillis: Long,
    val ownerKeyFingerprint: String,
) {
    private val secretBytes = secret.copyOf()

    internal fun secretCopy(): ByteArray = secretBytes.copyOf()

    internal fun clear() = secretBytes.fill(0)

    override fun toString(): String = "TeamInvitation(redacted)"
}

object TeamInvitationCodec {
    private const val PREFIX = "KRONTEAM1."
    private const val VERSION = 1
    private const val SECRET_BYTES = 32
    private const val MAX_CODE_CHARS = 4096
    private const val INVITATION_LIFETIME_MILLIS = 24 * 60 * 60 * 1000L
    private val secureRandom = SecureRandom()

    fun create(
        teamId: String,
        folderId: String,
        targetEmail: String,
        role: String,
        ownerKeyFingerprint: String,
        nowEpochMillis: Long = System.currentTimeMillis(),
        inviteId: String = UUID.randomUUID().toString(),
    ): TeamInvitation {
        require(role == TeamRole.EDITOR || role == TeamRole.VIEWER) { "Role undangan tidak valid" }
        requireIdentifier(teamId, "Team ID")
        requireIdentifier(folderId, "Folder ID")
        requireIdentifier(inviteId, "Invite ID")
        require(isSha256(ownerKeyFingerprint)) { "Fingerprint Owner tidak valid" }
        val secret = ByteArray(SECRET_BYTES).also(secureRandom::nextBytes)
        return TeamInvitation(
            teamId = teamId,
            folderId = folderId,
            inviteId = inviteId,
            secret = secret,
            targetEmailHash = emailHash(targetEmail),
            role = role,
            expiresAtEpochMillis = Math.addExact(nowEpochMillis, INVITATION_LIFETIME_MILLIS),
            ownerKeyFingerprint = ownerKeyFingerprint,
        ).also { secret.fill(0) }
    }

    fun encode(invitation: TeamInvitation): String {
        val secret = invitation.secretCopy()
        return try {
            val payload = ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(VERSION)
                    output.writeUTF(invitation.teamId)
                    output.writeUTF(invitation.folderId)
                    output.writeUTF(invitation.inviteId)
                    output.writeInt(secret.size)
                    output.write(secret)
                    output.writeUTF(invitation.targetEmailHash)
                    output.writeUTF(invitation.role)
                    output.writeLong(invitation.expiresAtEpochMillis)
                    output.writeUTF(invitation.ownerKeyFingerprint)
                }
                bytes.toByteArray()
            }
            PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        } finally {
            secret.fill(0)
        }
    }

    fun decode(code: String, nowEpochMillis: Long = System.currentTimeMillis()): TeamInvitation {
        require(code.length in (PREFIX.length + 1)..MAX_CODE_CHARS && code.startsWith(PREFIX)) {
            "Format kode akses Team tidak valid"
        }
        require(code.none(Char::isWhitespace)) { "Kode akses Team tidak boleh mengandung spasi" }
        val payload = runCatching { Base64.getUrlDecoder().decode(code.removePrefix(PREFIX)) }
            .getOrElse { throw IllegalArgumentException("Format kode akses Team tidak valid") }
        return try {
            DataInputStream(ByteArrayInputStream(payload)).use { input ->
                require(input.readInt() == VERSION) { "Versi kode akses Team tidak didukung" }
                val teamId = input.readUTF().also { requireIdentifier(it, "Team ID") }
                val folderId = input.readUTF().also { requireIdentifier(it, "Folder ID") }
                val inviteId = input.readUTF().also { requireIdentifier(it, "Invite ID") }
                require(input.readInt() == SECRET_BYTES) { "Secret undangan tidak valid" }
                val secret = ByteArray(SECRET_BYTES).also(input::readFully)
                try {
                    val emailHash = input.readUTF().also { require(isSha256(it)) { "Email undangan tidak valid" } }
                    val role = input.readUTF().also {
                        require(it == TeamRole.EDITOR || it == TeamRole.VIEWER) { "Role undangan tidak valid" }
                    }
                    val expiry = input.readLong()
                    require(expiry > nowEpochMillis) { "Kode akses Team sudah kedaluwarsa" }
                    require(expiry <= Math.addExact(nowEpochMillis, INVITATION_LIFETIME_MILLIS + 60 * 60 * 1000L)) {
                        "Masa berlaku kode akses Team tidak valid"
                    }
                    val fingerprint = input.readUTF().also { require(isSha256(it)) { "Fingerprint Owner tidak valid" } }
                    require(input.read() == -1) { "Kode akses Team memiliki data tambahan" }
                    TeamInvitation(teamId, folderId, inviteId, secret, emailHash, role, expiry, fingerprint)
                } finally {
                    secret.fill(0)
                }
            }
        } finally {
            payload.fill(0)
        }
    }

    fun emailMatches(invitation: TeamInvitation, email: String): Boolean = MessageDigest.isEqual(
        hexToBytes(invitation.targetEmailHash),
        hexToBytes(emailHash(email)),
    )

    fun emailHash(email: String): String {
        val normalized = Normalizer.normalize(email, Normalizer.Form.NFKC).trim().lowercase(Locale.ROOT)
        require(normalized.length in 3..320 && '@' in normalized) { "Email tujuan tidak valid" }
        return sha256(normalized.toByteArray(Charsets.UTF_8))
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private fun requireIdentifier(value: String, label: String) {
        require(value.length in 1..512 && value.all { it.isLetterOrDigit() || it in "-_." }) { "$label tidak valid" }
    }

    private fun isSha256(value: String): Boolean = value.matches(Regex("[0-9a-f]{64}"))

    private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}

data class OpenedTeamInvitationEnvelope(
    val teamKey: ByteArray,
    val certificateBase64: String,
)

object TeamInvitationEnvelopeCrypto {
    private const val VERSION = 1
    private const val TEAM_KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val MAX_ENVELOPE_BYTES = 64 * 1024
    private val secureRandom = SecureRandom()

    fun seal(
        invitation: TeamInvitation,
        teamKey: ByteArray,
        signer: EvidenceSigningKeyManager,
    ): ByteArray {
        require(teamKey.size == TEAM_KEY_BYTES) { "Team key tidak valid" }
        val publicRecord = signer.publicRecord()
        require(publicRecord.fingerprint == invitation.ownerKeyFingerprint) { "Kunci bukti Owner berubah" }
        val metadata = metadata(invitation)
        val nonce = ByteArray(NONCE_BYTES).also(secureRandom::nextBytes)
        val secret = invitation.secretCopy()
        val derived = hkdf(secret, invitation.inviteId.toByteArray(Charsets.UTF_8))
        secret.fill(0)
        val encrypted = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(metadata)
                doFinal(teamKey)
            }
        } finally {
            derived.fill(0)
        }
        val signed = metadata + nonce + encrypted
        val signature = Base64.getDecoder().decode(signer.sign(signed))
        return try {
            ByteArrayOutputStream().use { bytes ->
                DataOutputStream(bytes).use { output ->
                    output.writeInt(VERSION)
                    output.writeInt(metadata.size)
                    output.write(metadata)
                    output.write(nonce)
                    output.writeInt(encrypted.size)
                    output.write(encrypted)
                    output.writeUTF(publicRecord.certificateBase64)
                    output.writeInt(signature.size)
                    output.write(signature)
                }
                bytes.toByteArray()
            }.also { require(it.size <= MAX_ENVELOPE_BYTES) { "Envelope undangan terlalu besar" } }
        } finally {
            signed.fill(0)
            signature.fill(0)
            encrypted.fill(0)
            nonce.fill(0)
        }
    }

    fun open(
        invitation: TeamInvitation,
        envelope: ByteArray,
        signer: EvidenceSigningKeyManager,
    ): OpenedTeamInvitationEnvelope {
        require(envelope.size in 1..MAX_ENVELOPE_BYTES) { "Envelope undangan tidak valid" }
        val parts = DataInputStream(ByteArrayInputStream(envelope)).use { input ->
            require(input.readInt() == VERSION) { "Versi envelope undangan tidak didukung" }
            val metadataSize = input.readInt()
            require(metadataSize in 1..4096) { "Metadata undangan tidak valid" }
            val metadata = ByteArray(metadataSize).also(input::readFully)
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val encryptedSize = input.readInt()
            require(encryptedSize in 17..256) { "Payload undangan tidak valid" }
            val encrypted = ByteArray(encryptedSize).also(input::readFully)
            val certificate = input.readUTF()
            val signatureSize = input.readInt()
            require(signatureSize in 32..512) { "Signature undangan tidak valid" }
            val signature = ByteArray(signatureSize).also(input::readFully)
            require(input.read() == -1) { "Envelope undangan memiliki data tambahan" }
            EnvelopeParts(metadata, nonce, encrypted, certificate, signature)
        }
        val expectedMetadata = metadata(invitation)
        require(MessageDigest.isEqual(expectedMetadata, parts.metadata)) { "Metadata envelope tidak cocok" }
        val certificateBytes = runCatching { Base64.getDecoder().decode(parts.certificateBase64) }
            .getOrElse { throw IllegalArgumentException("Sertifikat Owner tidak valid") }
        require(TeamInvitationCodec.sha256(certificateBytes) == invitation.ownerKeyFingerprint) {
            "Fingerprint Owner tidak cocok"
        }
        val signed = parts.metadata + parts.nonce + parts.encrypted
        require(
            signer.verify(signed, Base64.getEncoder().encodeToString(parts.signature), parts.certificateBase64),
        ) { "Signature undangan tidak valid" }

        val secret = invitation.secretCopy()
        val derived = hkdf(secret, invitation.inviteId.toByteArray(Charsets.UTF_8))
        secret.fill(0)
        val teamKey = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(128, parts.nonce))
                updateAAD(parts.metadata)
                doFinal(parts.encrypted)
            }
        } finally {
            derived.fill(0)
            signed.fill(0)
            certificateBytes.fill(0)
        }
        require(teamKey.size == TEAM_KEY_BYTES) { "Team key tidak valid" }
        return OpenedTeamInvitationEnvelope(teamKey, parts.certificateBase64)
    }

    private fun metadata(invitation: TeamInvitation): ByteArray = buildString {
        append("KRONTEAM-ENVELOPE-1\n")
        append(invitation.teamId).append('\n')
        append(invitation.folderId).append('\n')
        append(invitation.inviteId).append('\n')
        append(invitation.targetEmailHash).append('\n')
        append(invitation.role).append('\n')
        append(invitation.expiresAtEpochMillis).append('\n')
        append(invitation.ownerKeyFingerprint)
    }.toByteArray(Charsets.UTF_8)

    private fun hkdf(secret: ByteArray, salt: ByteArray): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(secret)
        }
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(extract, "HmacSHA256"))
                doFinal("KRON Team invitation key v1\u0001".toByteArray(Charsets.UTF_8))
            }
        } finally {
            extract.fill(0)
        }
    }

    private data class EnvelopeParts(
        val metadata: ByteArray,
        val nonce: ByteArray,
        val encrypted: ByteArray,
        val certificateBase64: String,
        val signature: ByteArray,
    )
}
