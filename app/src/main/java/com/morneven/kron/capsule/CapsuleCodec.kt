package com.morneven.kron.capsule

import com.morneven.kron.audit.EvidenceSigningKeyManager
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

data class CapsuleEnvelope(
    val manifestJson: String,
    val ownerCertificateBase64: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val signatureBase64: String,
    val checksum: String,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CapsuleEnvelope) return false
        return manifestJson == other.manifestJson &&
            ownerCertificateBase64 == other.ownerCertificateBase64 &&
            nonce.contentEquals(other.nonce) &&
            ciphertext.contentEquals(other.ciphertext) &&
            signatureBase64 == other.signatureBase64 &&
            checksum == other.checksum
    }

    override fun hashCode(): Int {
        var result = manifestJson.hashCode()
        result = 31 * result + ownerCertificateBase64.hashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + ciphertext.contentHashCode()
        result = 31 * result + signatureBase64.hashCode()
        result = 31 * result + checksum.hashCode()
        return result
    }
}

object CapsuleCodec {
    private const val MAGIC = "KRONCAP2"
    private const val FORMAT_VERSION = 2
    private const val KEY_BYTES = 32
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val MAX_MANIFEST_BYTES = 64 * 1024
    private const val MAX_CERT_BYTES = 4 * 1024
    private const val MAX_SIG_BYTES = 512
    private const val MAX_PAYLOAD_BYTES = 128 * 1024 * 1024
    private const val MAX_ENVELOPE_BYTES = 32 * 1024 * 1024
    private const val COMPRESSION_THRESHOLD = 1024
    private val RANDOM = SecureRandom()

    fun generateSecret(): ByteArray = ByteArray(KEY_BYTES).also(RANDOM::nextBytes)

    fun generateNonce(): ByteArray = ByteArray(NONCE_BYTES).also(RANDOM::nextBytes)

    fun extractFileId(code: String): String? = runCatching {
        val encoded = code.removePrefix("KRONCAP2.")
        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
        decoded.split("|", limit = 3).let { parts ->
            require(parts.size == 3)
            parts[1]
        }
    }.getOrNull()

    fun deriveKey(secret: ByteArray, salt: ByteArray): ByteArray {
        val prk = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(salt, "HmacSHA256"))
            doFinal(secret)
        }
        return Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(prk, "HmacSHA256"))
            doFinal("kron-capsule-aes-key".toByteArray())
        }
    }

    fun encrypt(plaintext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC.toByteArray())
        return cipher.doFinal(plaintext)
    }

    fun decrypt(ciphertext: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(MAGIC.toByteArray())
        return cipher.doFinal(ciphertext)
    }

    fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(data).joinToString("") { "%02x".format(it) }

    fun compress(data: ByteArray): ByteArray {
        if (data.size < COMPRESSION_THRESHOLD) return data
        return ByteArrayOutputStream().use { bos ->
            GZIPOutputStream(bos).use { it.write(data) }
            bos.toByteArray()
        }
    }

    fun decompress(data: ByteArray): ByteArray {
        if (data.size < 2 || data[0] != 0x1F.toByte() || data[1] != 0x8B.toByte()) return data
        return try {
            ByteArrayInputStream(data).use { bis -> GZIPInputStream(bis).readBytes() }
        } catch (_: Exception) { data }
    }

    fun serializeSnapshot(snapshot: CapsuleSnapshot): ByteArray {
        val root = JSONObject().apply {
            put("accountName", snapshot.accountName)
            put("sharingMode", snapshot.sharingMode)
            put("currency", snapshot.currency)
            put("snapshotEpochMillis", snapshot.snapshotEpochMillis)
            put("cashBalance", snapshot.cashBalance)
            put("vaultBalance", snapshot.vaultBalance)
            put("unallocatedBalance", snapshot.unallocatedBalance)
            put("categories", JSONArray(snapshot.categories.map { c ->
                JSONObject().apply { put("name", c.name); put("direction", c.direction); put("color", c.color); put("icon", c.icon) }
            }))
            put("portfolios", JSONArray(snapshot.portfolios.map { p ->
                JSONObject().apply { put("name", p.name); put("cadence", p.cadence); put("plannedIncome", p.plannedIncome) }
            }))
            put("periods", JSONArray(snapshot.periods.map { p ->
                JSONObject().apply { put("portfolioName", p.portfolioName); put("startEpochDay", p.startEpochDay); put("endEpochDay", p.endEpochDay); put("status", p.status) }
            }))
            put("allocations", JSONArray(snapshot.allocations.map { a ->
                JSONObject().apply {
                    put("categoryName", a.categoryName); put("portfolioName", a.portfolioName); put("periodStart", a.periodStart)
                    put("fundingChannel", a.fundingChannel); put("plannedAmount", a.plannedAmount)
                    put("spentAmount", a.spentAmount); put("availableAmount", a.availableAmount)
                }
            }))
            put("transactions", JSONArray(snapshot.transactions.map { t ->
                JSONObject().apply {
                    put("id", t.id); put("type", t.type); put("title", t.title)
                    put("effectiveEpochDay", t.effectiveEpochDay); put("amount", t.amount)
                    put("direction", t.direction); put("categoryName", t.categoryName ?: JSONObject.NULL)
                    put("ledgerDebit", t.ledgerDebit); put("ledgerCredit", t.ledgerCredit)
                }
            }))
            put("journals", JSONArray(snapshot.journals.map { j ->
                JSONObject().apply { put("entryId", j.entryId); put("side", j.side); put("amount", j.amount); put("description", j.description) }
            }))
            put("auditEntries", JSONArray(snapshot.auditEntries.map { a ->
                JSONObject().apply {
                    put("eventId", a.eventId); put("type", a.type); put("actor", a.actor ?: JSONObject.NULL)
                    put("deviceId", a.deviceId ?: JSONObject.NULL); put("sealedAt", a.sealedAt)
                    put("sealHash", a.sealHash ?: JSONObject.NULL)
                }
            }))
            put("receiptMetadata", JSONArray(snapshot.receiptMetadata.map { r ->
                JSONObject().apply {
                    put("eventId", r.eventId); put("fileName", r.fileName ?: JSONObject.NULL)
                    put("mimeType", r.mimeType ?: JSONObject.NULL); put("fileSize", r.fileSize)
                    put("fileHash", r.fileHash)
                }
            }))
        }
        return root.toString().toByteArray(Charsets.UTF_8)
    }

    fun deserializeSnapshot(bytes: ByteArray): CapsuleSnapshot {
        val root = JSONObject(String(bytes, Charsets.UTF_8))
        return CapsuleSnapshot(
            accountName = root.getString("accountName"),
            sharingMode = root.getString("sharingMode"),
            currency = root.optString("currency", "IDR"),
            snapshotEpochMillis = root.getLong("snapshotEpochMillis"),
            cashBalance = root.optLong("cashBalance"),
            vaultBalance = root.optLong("vaultBalance"),
            unallocatedBalance = root.optLong("unallocatedBalance"),
            categories = root.optJSONArray("categories")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsuleCategory(o.getString("name"), o.getString("direction"), o.getLong("color"), o.getString("icon"))
                }
            } ?: emptyList(),
            portfolios = root.optJSONArray("portfolios")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsulePortfolio(o.getString("name"), o.getString("cadence"), o.getLong("plannedIncome"))
                }
            } ?: emptyList(),
            periods = root.optJSONArray("periods")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsulePeriod(o.getString("portfolioName"), o.getLong("startEpochDay"), o.getLong("endEpochDay"), o.getString("status"))
                }
            } ?: emptyList(),
            allocations = root.optJSONArray("allocations")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsuleAllocation(o.getString("categoryName"), o.getString("portfolioName"), o.getLong("periodStart"), o.getString("fundingChannel"), o.getLong("plannedAmount"), o.getLong("spentAmount"), o.getLong("availableAmount"))
                }
            } ?: emptyList(),
            transactions = root.optJSONArray("transactions")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsuleTransaction(o.getLong("id"), o.getString("type"), o.getString("title"), o.getLong("effectiveEpochDay"), o.getLong("amount"), o.getString("direction"), o.optString("categoryName", null), o.optLong("ledgerDebit"), o.optLong("ledgerCredit"))
                }
            } ?: emptyList(),
            journals = root.optJSONArray("journals")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsuleJournalLine(o.getString("entryId"), o.getString("side"), o.getLong("amount"), o.getString("description"))
                }
            } ?: emptyList(),
            auditEntries = root.optJSONArray("auditEntries")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsuleAuditEntry(o.getLong("eventId"), o.getString("type"), o.optString("actor", null), o.optString("deviceId", null), o.getLong("sealedAt"), o.optString("sealHash", null))
                }
            } ?: emptyList(),
            receiptMetadata = root.optJSONArray("receiptMetadata")?.let { arr ->
                (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    CapsuleReceiptMeta(o.getLong("eventId"), o.optString("fileName", null), o.optString("mimeType", null), o.getLong("fileSize"), o.getString("fileHash"))
                }
            } ?: emptyList(),
        )
    }

    fun buildEnvelope(
        manifest: CapsuleManifest,
        snapshotBytes: ByteArray,
        ownerCertificateBase64: String,
        secret: ByteArray,
        signingManager: EvidenceSigningKeyManager,
    ): CapsuleEnvelope {
        val nonce = generateNonce()
        val salt = generateNonce()
        val key = deriveKey(secret, salt)
        val compressed = compress(snapshotBytes)
        val ciphertext = encrypt(compressed, key, nonce)
        val manifestJson = JSONObject().apply {
            put("formatVersion", manifest.formatVersion)
            put("capsuleId", manifest.capsuleId)
            put("fileId", manifest.fileId)
            put("sourceAccountId", manifest.sourceAccountId)
            put("sourceAccountName", manifest.sourceAccountName)
            put("sourceSharingMode", manifest.sourceSharingMode)
            put("ownerGoogleSubjectId", manifest.ownerGoogleSubjectId)
            put("ownerFingerprint", manifest.ownerFingerprint)
            put("targetEmail", manifest.targetEmail)
            put("createdAt", manifest.createdAt)
            put("claimExpiresAt", manifest.claimExpiresAt)
            put("dataScope", manifest.dataScope)
            put("payloadSha256", manifest.payloadSha256)
            put("salt", Base64.getEncoder().encodeToString(salt))
            put("envelopeChecksum", "") // placeholder
        }.toString()

        val toSign = (manifestJson + ownerCertificateBase64).toByteArray() + nonce + "\n".toByteArray()
        val signatureBase64 = signingManager.sign(toSign)

        val envelopeBytes = serializeEnvelope(manifestJson, ownerCertificateBase64, nonce, ciphertext, signatureBase64)
        val checksum = sha256(envelopeBytes)

        return CapsuleEnvelope(manifestJson, ownerCertificateBase64, nonce, ciphertext, signatureBase64, checksum)
    }

    fun verifyAndDecrypt(
        envelope: CapsuleEnvelope,
        secret: ByteArray,
        signingManager: EvidenceSigningKeyManager,
    ): ByteArray? = runCatching {
        val manifest = JSONObject(envelope.manifestJson)

        val toVerify = (envelope.manifestJson + envelope.ownerCertificateBase64).toByteArray() + envelope.nonce + "\n".toByteArray()
        val sigOk = signingManager.verify(toVerify, envelope.signatureBase64, envelope.ownerCertificateBase64)
        if (!sigOk) return null

        val salt = Base64.getDecoder().decode(manifest.getString("salt"))
        val key = deriveKey(secret, salt)
        val decrypted = decrypt(envelope.ciphertext, key, envelope.nonce)
        decompress(decrypted)
    }.getOrNull()

    fun serializeEnvelope(
        manifestJson: String, certBase64: String, nonce: ByteArray, ciphertext: ByteArray, sigBase64: String,
    ): ByteArray {
        val manifestBytes = manifestJson.toByteArray(Charsets.UTF_8)
        val certBytes = certBase64.toByteArray(Charsets.UTF_8)
        val sigBytes = sigBase64.toByteArray(Charsets.UTF_8)
        require(manifestBytes.size <= MAX_MANIFEST_BYTES) { "Manifest terlalu besar" }
        require(certBytes.size <= MAX_CERT_BYTES) { "Sertifikat terlalu besar" }
        require(sigBytes.size <= MAX_SIG_BYTES) { "Signature terlalu besar" }
        require(ciphertext.size <= MAX_PAYLOAD_BYTES) { "Payload terlalu besar" }
        return ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { out ->
                out.write(MAGIC.toByteArray(Charsets.US_ASCII))
                out.writeInt(FORMAT_VERSION)
                out.writeInt(manifestBytes.size); out.write(manifestBytes)
                out.writeInt(certBytes.size); out.write(certBytes)
                out.write(nonce)
                out.writeInt(ciphertext.size); out.write(ciphertext)
                out.writeInt(sigBytes.size); out.write(sigBytes)
            }
            val envelope = baos.toByteArray()
            require(envelope.size <= MAX_ENVELOPE_BYTES) { "Envelope Kapsul melebihi 32 MiB" }
            envelope
        }
    }

    fun deserializeEnvelope(data: ByteArray): CapsuleEnvelope? = runCatching {
        require(data.size in MAGIC.length + 20 .. MAX_ENVELOPE_BYTES) { "Ukuran envelope tidak valid" }
        DataInputStream(ByteArrayInputStream(data)).use { input ->
            val magic = ByteArray(MAGIC.length).also(input::readFully)
            require(magic.contentEquals(MAGIC.toByteArray(Charsets.US_ASCII))) { "Magic Kapsul tidak valid" }
            val version = input.readInt()
            require(version == FORMAT_VERSION) { "Versi protokol $version tidak didukung" }
            val manifestSize = input.readInt(); require(manifestSize in 1..MAX_MANIFEST_BYTES)
            val manifestBytes = ByteArray(manifestSize).also(input::readFully)
            val certSize = input.readInt(); require(certSize in 1..MAX_CERT_BYTES)
            val certBytes = ByteArray(certSize).also(input::readFully)
            val nonce = ByteArray(NONCE_BYTES).also(input::readFully)
            val ciphertextSize = input.readInt(); require(ciphertextSize in 16..MAX_PAYLOAD_BYTES)
            val ciphertext = ByteArray(ciphertextSize).also(input::readFully)
            val sigSize = input.readInt(); require(sigSize in 1..MAX_SIG_BYTES)
            val sigBytes = ByteArray(sigSize).also(input::readFully)
            require(input.read() == -1) { "Data tambahan terdeteksi" }
            CapsuleEnvelope(
                manifestJson = String(manifestBytes, Charsets.UTF_8),
                ownerCertificateBase64 = String(certBytes, Charsets.UTF_8),
                nonce = nonce,
                ciphertext = ciphertext,
                signatureBase64 = String(sigBytes, Charsets.UTF_8),
                checksum = sha256(data),
            )
        }
    }.getOrNull()
}
