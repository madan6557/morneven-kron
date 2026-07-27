package com.morneven.kron.sharing.onetime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.util.Base64
import java.util.UUID

object ViewCapsuleCodec {
    private const val FORMAT_VERSION = 1
    private const val MAGIC = "KRONCP1"
    private val BASE64 = Base64.getUrlEncoder().withoutPadding()

    fun createCapsule(
        projection: ViewProjection,
        teamId: String,
        ownerKeyId: String,
        targetEmailHash: String,
        targetDeviceKeyId: String,
        dataScope: String,
        contentKey: ByteArray,
        nonce: ByteArray,
        ownerPrivateKey: ECPrivateKey,
        expiresAtMillis: Long,
    ): ViewCapsule {
        val capsuleId = UUID.randomUUID().toString()
        val issuedAt = System.currentTimeMillis()
        val projectionBytes = serializeProjection(projection)
        val encryptedProjection = encryptAesGcm(projectionBytes, contentKey, nonce)
        val ciphertextHash = sha256(encryptedProjection)

        val manifest = ViewCapsuleManifest(
            formatVersion = FORMAT_VERSION,
            capsuleId = capsuleId,
            teamId = teamId,
            ownerKeyId = ownerKeyId,
            targetEmailHash = targetEmailHash,
            targetDeviceKeyId = targetDeviceKeyId,
            issuedAt = issuedAt,
            expiresAt = expiresAtMillis,
            dataScope = dataScope,
            ciphertextHash = ciphertextHash,
        )

        val manifestBytes = serializeManifest(manifest)
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(ownerPrivateKey)
            update(manifestBytes)
            update(nonce)
            update(ciphertextHash.toByteArray(Charsets.UTF_8))
            sign()
        }

        return ViewCapsule(
            manifest = manifest,
            encryptedContentKey = contentKey,
            nonce = nonce,
            encryptedProjection = encryptedProjection,
            ownerSignature = signature,
        )
    }

    fun verifyCapsule(capsule: ViewCapsule, ownerPublicKey: ECPublicKey): Boolean {
        return runCatching {
            val computedHash = sha256(capsule.encryptedProjection)
            if (computedHash != capsule.manifest.ciphertextHash) return false

            val manifestBytes = serializeManifest(capsule.manifest)
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(ownerPublicKey)
            sig.update(manifestBytes)
            sig.update(capsule.nonce)
            sig.update(capsule.manifest.ciphertextHash.toByteArray(Charsets.UTF_8))
            sig.verify(capsule.ownerSignature)
        }.getOrDefault(false)
    }

    fun decryptProjection(capsule: ViewCapsule, contentKey: ByteArray): ByteArray? {
        return runCatching {
            val computedHash = sha256(capsule.encryptedProjection)
            if (computedHash != capsule.manifest.ciphertextHash) return null
            decryptAesGcm(capsule.encryptedProjection, contentKey, capsule.nonce)
        }.getOrNull()
    }

    fun encodeToString(capsule: ViewCapsule): String {
        val bytes = serializeCapsule(capsule)
        return BASE64.encodeToString(bytes)
    }

    fun decodeFromString(encoded: String): ViewCapsule? {
        return runCatching {
            val bytes = Base64.getUrlDecoder().decode(encoded)
            deserializeCapsule(bytes)
        }.getOrNull()
    }

    fun serializeCapsule(capsule: ViewCapsule): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeInt(FORMAT_VERSION)
                writeManifest(out, capsule.manifest)
                writeBytes(out, capsule.encryptedContentKey)
                writeBytes(out, capsule.nonce)
                writeBytes(out, capsule.encryptedProjection)
                writeBytes(out, capsule.ownerSignature)
            }
            baos.toByteArray()
        }
    }

    fun deserializeCapsule(bytes: ByteArray): ViewCapsule {
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val magic = ByteArray(MAGIC.length).also(input::readFully)
            require(magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) { "Invalid capsule magic" }
            val version = input.readInt()
            require(version == FORMAT_VERSION) { "Unsupported format version: $version" }
            val manifest = readManifest(input)
            val encryptedContentKey = readBytes(input)
            val nonce = readBytes(input)
            val encryptedProjection = readBytes(input)
            val ownerSignature = readBytes(input)
            ViewCapsule(manifest, encryptedContentKey, nonce, encryptedProjection, ownerSignature)
        }
    }

    private fun serializeManifest(m: ViewCapsuleManifest): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                writeManifest(out, m)
            }
            baos.toByteArray()
        }
    }

    private fun writeManifest(out: DataOutputStream, m: ViewCapsuleManifest) {
        out.writeInt(m.formatVersion)
        out.writeUTF(m.capsuleId)
        out.writeUTF(m.teamId)
        out.writeUTF(m.ownerKeyId)
        out.writeUTF(m.targetEmailHash)
        out.writeUTF(m.targetDeviceKeyId)
        out.writeLong(m.issuedAt)
        out.writeLong(m.expiresAt)
        out.writeUTF(m.dataScope)
        out.writeUTF(m.ciphertextHash)
    }

    private fun readManifest(input: DataInputStream): ViewCapsuleManifest {
        return ViewCapsuleManifest(
            formatVersion = input.readInt(),
            capsuleId = input.readUTF(),
            teamId = input.readUTF(),
            ownerKeyId = input.readUTF(),
            targetEmailHash = input.readUTF(),
            targetDeviceKeyId = input.readUTF(),
            issuedAt = input.readLong(),
            expiresAt = input.readLong(),
            dataScope = input.readUTF(),
            ciphertextHash = input.readUTF(),
        )
    }

    private fun serializeProjection(p: ViewProjection): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.writeLong(p.generatedAt.toEpochMilli())
                out.writeLong(p.periodStart)
                out.writeLong(p.periodEnd)
                out.writeUTF(p.summaryJson)
                out.writeUTF(p.transactionsJson)
                out.writeUTF(p.budgetsJson)
            }
            baos.toByteArray()
        }
    }

    fun deserializeProjection(bytes: ByteArray): ViewProjection {
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            ViewProjection(
                generatedAt = java.time.Instant.ofEpochMilli(input.readLong()),
                periodStart = input.readLong(),
                periodEnd = input.readLong(),
                summaryJson = input.readUTF(),
                transactionsJson = input.readUTF(),
                budgetsJson = input.readUTF(),
            )
        }
    }

    private fun writeBytes(out: DataOutputStream, data: ByteArray) {
        out.writeInt(data.size)
        out.write(data)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size >= 0 && size < 10_000_000) { "Invalid blob size: $size" }
        return ByteArray(size).also(input::readFully)
    }

    private fun encryptAesGcm(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        return cipher.doFinal(plaintext)
    }

    private fun decryptAesGcm(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(key, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, nonce),
        )
        return cipher.doFinal(ciphertext)
    }

    fun sha256(data: ByteArray): String {
        return MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }

    fun generateNonce(): ByteArray {
        val nonce = ByteArray(12)
        java.security.SecureRandom().nextBytes(nonce)
        return nonce
    }

    fun generateContentKey(): ByteArray {
        val key = ByteArray(32)
        java.security.SecureRandom().nextBytes(key)
        return key
    }
}
