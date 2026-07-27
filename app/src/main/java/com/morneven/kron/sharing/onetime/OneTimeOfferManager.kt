package com.morneven.kron.sharing.onetime

import android.content.Context
import com.morneven.kron.ui.KronUiState
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

class OneTimeOfferManager @Inject constructor(
    private val context: Context,
) {
    private val capsuleStore = ViewCapsuleStore(context)
    private val keyManager = DeviceBindingKeyManager(context)
    private val offerStore = OneTimeOfferStore(context)
    private val codeChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    data class OfferResult(val code: String, val capsuleId: String)

    fun createOffer(state: KronUiState, scope: String, ownerGoogleSubjectId: String? = null): OfferResult? = runCatching {
        val code = "KRONCP1.${(1..8).map { codeChars.random() }.joinToString("")}"
        val capsuleId = code

        val projection = ViewProjectionBuilder.buildProjection(state, scope)
        val contentKey = keyManager.createOneTimeContentKey(capsuleId) ?: return@runCatching null
        val nonce = ViewCapsuleCodec.generateNonce()
        val signingKey = generateSigningKey()
        val expiresAt = System.currentTimeMillis() + 24 * 60 * 60 * 1000

        val teamId = state.teamWorkspace?.teamId ?: "local-${state.activeAccount?.id ?: 0}"

        val capsule = ViewCapsuleCodec.createCapsule(
            projection = projection,
            teamId = teamId,
            ownerKeyId = ownerGoogleSubjectId?.let { "drive-sync:$it" }
                ?: "local-${state.activeAccount?.id ?: 0}",
            targetEmailHash = "",
            targetDeviceKeyId = capsuleId,
            dataScope = scope,
            contentKey = contentKey,
            nonce = nonce,
            ownerPrivateKey = signingKey.private as ECPrivateKey,
            expiresAtMillis = expiresAt,
        )

        if (!capsuleStore.save(capsule)) return@runCatching null

        offerStore.save(OfferRecord(
            code = code, teamId = teamId, dataScope = scope,
            status = OfferStatus.PENDING, createdAt = System.currentTimeMillis(),
            expiresAt = expiresAt, capsuleId = capsuleId,
        ))

        OfferResult(code, capsuleId)
    }.getOrNull()

    fun approveAndIssue(code: String): Boolean {
        val record = offerStore.load(code) ?: return false
        if (record.status != OfferStatus.REQUEST_RECEIVED) return false
        offerStore.save(record.copy(status = OfferStatus.CAPSULE_ISSUED))
        return true
    }

    fun consume(code: String): ViewCapsule? {
        val record = offerStore.load(code) ?: return null
        if (record.status == OfferStatus.CONSUMED) return null
        if (record.status == OfferStatus.PENDING) {
            offerStore.save(record.copy(status = OfferStatus.CAPSULE_ISSUED))
        }
        val capsule = capsuleStore.load(code) ?: return null
        offerStore.save(record.copy(status = OfferStatus.CONSUMED))
        return capsule
    }

    fun openCapsule(code: String): ViewCapsule? = capsuleStore.load(code)

    fun getPendingApprovals() = offerStore.listPending()

    fun deleteCapsule(code: String) {
        capsuleStore.delete(code)
        offerStore.delete(code)
    }

    private fun generateSigningKey(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }
}
