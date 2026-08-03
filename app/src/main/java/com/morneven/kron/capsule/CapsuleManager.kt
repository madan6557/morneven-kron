package com.morneven.kron.capsule

import android.content.Context
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.data.AccountSharingMode
import com.morneven.kron.data.KronDao
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamRole
import com.morneven.kron.sync.DriveAccessTokenResult
import java.util.UUID

class CapsuleManager(
    private val context: Context,
    private val signingManager: EvidenceSigningKeyManager,
    private val driveClient: CapsuleDriveClient,
    private val store: CapsuleStore,
) {
    data class CreateResult(val capsuleId: String, val code: String, val fileId: String)
    data class OpenResult(val snapshot: CapsuleSnapshot?, val error: String?)

    private val dao: KronDao get() = com.morneven.kron.data.KronDatabase.getInstance(context).kronDao()

    suspend fun canCreateFor(account: AccountEntity): Boolean {
        if (account.sharingMode == AccountSharingMode.PRIVATE) return true
        val workspace = dao.teamWorkspace(account.id)
        return workspace?.localRole == TeamRole.OWNER
    }

    fun generateCode(capsuleId: String, fileId: String, secret: ByteArray): String {
        val data = "$capsuleId|$fileId|${android.util.Base64.encodeToString(secret, android.util.Base64.NO_WRAP)}"
        val encoded = android.util.Base64.encodeToString(data.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        return "KRONCAP2.$encoded"
    }

    private fun parseCode(code: String): CodeData? = runCatching {
        val encoded = code.removePrefix("KRONCAP2.")
        val decoded = String(android.util.Base64.decode(encoded, android.util.Base64.URL_SAFE), Charsets.UTF_8)
        val parts = decoded.split("|", limit = 3)
        CodeData(parts[0], parts[1], android.util.Base64.decode(parts[2], android.util.Base64.NO_WRAP))
    }.getOrNull()

    private data class CodeData(val capsuleId: String, val fileId: String, val secret: ByteArray)

    suspend fun create(
        account: AccountEntity,
        targetEmail: String,
        ownerSubjectId: String,
        getAccessToken: suspend () -> DriveAccessTokenResult,
    ): CreateResult? = runCatching {
        val capsuleId = UUID.randomUUID().toString()

        val categories = dao.categoriesForAccount(account.id)
        val portfolios = dao.portfoliosForAccount(account.id)
        val periods = if (portfolios.isEmpty()) emptyList() else dao.periodsForPortfolios(portfolios.map { it.id })
        val allocations = dao.allocationBalancesForAccount(account.id)
        val activities = dao.activitiesForAccount(account.id)
        val receipts = dao.receiptsForAccount(account.id)

        val txList = activities.map { a ->
            CapsuleTransaction(
                a.id.toLongOrNull() ?: 0, a.type, a.title, a.effectiveEpochDay, a.cashImpact,
                if (a.ledgerCredit > 0) "INCOME" else "EXPENSE", null, a.ledgerDebit, a.ledgerCredit,
            )
        }
        val receiptList = receipts.map { r ->
            CapsuleReceiptMeta(r.eventId.toLongOrNull() ?: 0, r.displayName, r.mimeType, r.byteSize, r.sha256)
        }

        val snapshot = CapsuleSnapshot(
            accountName = account.name,
            sharingMode = account.sharingMode,
            snapshotEpochMillis = System.currentTimeMillis(),
            categories = categories.map { CapsuleCategory(it.name, it.direction, it.color, it.icon) },
            portfolios = portfolios.map { CapsulePortfolio(it.name, it.cadence, it.plannedIncome) },
            periods = periods.map { CapsulePeriod("", it.startEpochDay, it.endEpochDay, it.status) },
            allocations = allocations.map { a ->
                CapsuleAllocation(a.categoryName, a.portfolioName, a.startEpochDay, a.fundingChannel, a.plannedAmount, a.spentAmount, a.availableAmount)
            },
            transactions = txList,
            receiptMetadata = receiptList,
        )

        val snapshotBytes = CapsuleCodec.serializeSnapshot(snapshot)
        val payloadSha256 = CapsuleCodec.sha256(snapshotBytes)
        val now = System.currentTimeMillis()
        val claimExpiresAt = now + 7L * 24 * 60 * 60 * 1000
        val fingerprint = signingManager.publicRecord().fingerprint
        val certBase64 = signingManager.publicRecord().certificateBase64
        val fileId = UUID.randomUUID().toString()
        val salt = CapsuleCodec.generateNonce()
        val nonce = CapsuleCodec.generateNonce()
        val secret = CapsuleCodec.generateSecret()

        val manifestJson = org.json.JSONObject().apply {
            put("formatVersion", 2)
            put("capsuleId", capsuleId)
            put("fileId", fileId)
            put("sourceAccountId", account.id)
            put("sourceAccountName", account.name)
            put("sourceSharingMode", account.sharingMode)
            put("ownerGoogleSubjectId", ownerSubjectId)
            put("ownerFingerprint", fingerprint)
            put("targetEmail", targetEmail.trim().lowercase())
            put("createdAt", now)
            put("claimExpiresAt", claimExpiresAt)
            put("dataScope", "CurrentFullSnapshot")
            put("payloadSha256", payloadSha256)
            put("salt", java.util.Base64.getEncoder().encodeToString(salt))
            put("envelopeChecksum", "")
        }.toString()

        val key = CapsuleCodec.deriveKey(secret, salt)
        val compressed = CapsuleCodec.compress(snapshotBytes)
        val ciphertext = CapsuleCodec.encrypt(compressed, key, nonce)

        val toSign = (manifestJson + certBase64).toByteArray() + nonce + "\n".toByteArray()
        val signatureBase64 = signingManager.sign(toSign)

        val envBytes = CapsuleCodec.serializeEnvelope(manifestJson, certBase64, nonce, ciphertext, signatureBase64)

        val tokenResult = getAccessToken()
        val token = when (tokenResult) {
            is DriveAccessTokenResult.Granted -> tokenResult.accessToken
            else -> return@runCatching null
        }

        val driveFile = driveClient.upload(token, targetEmail, envBytes)
        driveClient.grantReader(token, driveFile.fileId, targetEmail)

        val updatedManifest = org.json.JSONObject(manifestJson).apply { put("fileId", driveFile.fileId) }.toString()
        val updatedSig = signingManager.sign((updatedManifest + certBase64).toByteArray() + nonce + "\n".toByteArray())
        val finalEnvBytes = CapsuleCodec.serializeEnvelope(updatedManifest, certBase64, nonce, ciphertext, updatedSig)

        store.saveEnvelope(capsuleId, finalEnvBytes)
        store.saveSecret(capsuleId, secret)
        store.saveSent(CapsuleRecord(
            capsuleId = capsuleId,
            fileId = driveFile.fileId,
            sourceAccountName = account.name,
            targetEmail = targetEmail.trim().lowercase(),
            status = CapsuleLocalStatus.SENT,
            createdAt = now,
            claimExpiresAt = claimExpiresAt,
            dataScope = "CurrentFullSnapshot",
        ))

        CreateResult(capsuleId, generateCode(capsuleId, driveFile.fileId, secret), driveFile.fileId)
    }.getOrNull()

    suspend fun receive(
        code: String,
        getAccessToken: suspend () -> DriveAccessTokenResult,
    ): OpenResult = runCatching {
        val trimmed = code.trim()
        if (!trimmed.startsWith("KRONCAP2.")) return@runCatching OpenResult(null, "Kode Kapsul tidak valid")
        val codeData = parseCode(trimmed) ?: return@runCatching OpenResult(null, "Kode Kapsul rusak")

        val existing = store.loadReceived(codeData.capsuleId)
        if (existing != null) {
            val now = System.currentTimeMillis()
            if (existing.status == CapsuleLocalStatus.RECEIVED_EXPIRED) {
                return@runCatching OpenResult(null, "Kapsul sudah kedaluwarsa")
            }
            val envelope = CapsuleCodec.deserializeEnvelope(store.loadEnvelope(codeData.capsuleId) ?: return@runCatching OpenResult(null, "Envelope tidak ditemukan")) ?: return@runCatching OpenResult(null, "Envelope rusak")
            val plaintext = CapsuleCodec.verifyAndDecrypt(envelope, codeData.secret, signingManager)
                ?: return@runCatching OpenResult(null, "Kapsul rusak atau signature tidak valid")
            val snapshot = CapsuleCodec.deserializeSnapshot(plaintext)

            if (existing.openedAt != null && existing.expiresAt != null) {
                if (now > existing.expiresAt) return@runCatching OpenResult(null, "Kapsul sudah kedaluwarsa")
                return@runCatching OpenResult(snapshot, null)
            }
            val openedAt = now; val expiresAt = openedAt + 24 * 60 * 60 * 1000
            store.saveReceived(existing.copy(status = CapsuleLocalStatus.RECEIVED_ACTIVE, openedAt = openedAt, expiresAt = expiresAt))
            return@runCatching OpenResult(snapshot, null)
        }

        val token = when (val result = getAccessToken()) {
            is DriveAccessTokenResult.Granted -> result.accessToken
            else -> return@runCatching OpenResult(null, "Otorisasi Google Drive diperlukan")
        }
        val envelopeBytes = try {
            driveClient.download(token, codeData.fileId)
        } catch (_: Exception) {
            return@runCatching OpenResult(null, "Kapsul tidak ditemukan di Drive atau akses dicabut")
        }
        val envelope = CapsuleCodec.deserializeEnvelope(envelopeBytes) ?: return@runCatching OpenResult(null, "Envelope Kapsul rusak")

        val mf = org.json.JSONObject(envelope.manifestJson)
        if (java.lang.System.currentTimeMillis() > mf.getLong("claimExpiresAt")) {
            return@runCatching OpenResult(null, "Kapsul sudah kedaluwarsa (batas klaim 7 hari)")
        }

        val plaintext = CapsuleCodec.verifyAndDecrypt(envelope, codeData.secret, signingManager)
            ?: return@runCatching OpenResult(null, "Kapsul rusak atau signature tidak valid")
        val snapshot = CapsuleCodec.deserializeSnapshot(plaintext)

        store.saveEnvelope(codeData.capsuleId, envelopeBytes)
        store.saveSecret(codeData.capsuleId, codeData.secret)

        val now = System.currentTimeMillis()
        val openedAt = now; val expiresAt = openedAt + 24 * 60 * 60 * 1000
        store.saveReceived(CapsuleRecord(
            capsuleId = codeData.capsuleId,
            fileId = codeData.fileId,
            sourceAccountName = mf.optString("sourceAccountName", ""),
            targetEmail = mf.optString("targetEmail", ""),
            status = CapsuleLocalStatus.RECEIVED_ACTIVE,
            createdAt = now,
            claimExpiresAt = mf.getLong("claimExpiresAt"),
            openedAt = openedAt,
            expiresAt = expiresAt,
            dataScope = mf.optString("dataScope", "CurrentFullSnapshot"),
        ))

        OpenResult(snapshot, null)
    }.getOrNull() ?: OpenResult(null, "Gagal membuka Kapsul")

    suspend fun openLocally(capsuleId: String): CapsuleSnapshot? {
        val env = store.loadEnvelope(capsuleId) ?: return null
        val envelope = CapsuleCodec.deserializeEnvelope(env) ?: return null
        val secret = store.loadSecret(capsuleId) ?: return null
        val plaintext = CapsuleCodec.verifyAndDecrypt(envelope, secret, signingManager) ?: return null
        return CapsuleCodec.deserializeSnapshot(plaintext)
    }

    fun listSent(): List<CapsuleRecord> = store.listSent()
    fun listReceived(): List<CapsuleRecord> = store.listReceived()

    suspend fun revoke(capsuleId: String, getAccessToken: suspend () -> DriveAccessTokenResult) {
        val record = store.loadSent(capsuleId) ?: return
        val token = when (val result = getAccessToken()) {
            is DriveAccessTokenResult.Granted -> result.accessToken
            else -> return
        }
        driveClient.revoke(token, record.fileId)
        store.clearCapsule(capsuleId)
        store.deleteSent(capsuleId)
    }
}
