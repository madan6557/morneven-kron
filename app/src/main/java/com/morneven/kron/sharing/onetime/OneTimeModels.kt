package com.morneven.kron.sharing.onetime

import java.time.Instant

sealed interface AccessMode {
    data class TeamMember(val role: String) : AccessMode
    data object OneTimeCapsule : AccessMode
}

enum class CapsuleState {
    OFFER_RECEIVED, DEVICE_BOUND, CAPSULE_DOWNLOADED, ARMED, CONSUMING, CONSUMED, SESSION_CLOSED
}

enum class SingleUseTier {
    STRICT, SUPPORTED_BEST_EFFORT, UNSUPPORTED
}

data class ViewProjection(
    val generatedAt: Instant,
    val periodStart: Long,
    val periodEnd: Long,
    val summaryJson: String,
    val transactionsJson: String,
    val budgetsJson: String,
)

data class ViewCapsuleManifest(
    val formatVersion: Int,
    val capsuleId: String,
    val teamId: String,
    val ownerKeyId: String,
    val targetEmailHash: String,
    val targetDeviceKeyId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val dataScope: String,
    val ciphertextHash: String,
)

data class ViewCapsule(
    val manifest: ViewCapsuleManifest,
    val encryptedContentKey: ByteArray,
    val nonce: ByteArray,
    val encryptedProjection: ByteArray,
    val ownerSignature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ViewCapsule) return false
        return manifest == other.manifest &&
            encryptedContentKey.contentEquals(other.encryptedContentKey) &&
            nonce.contentEquals(other.nonce) &&
            encryptedProjection.contentEquals(other.encryptedProjection) &&
            ownerSignature.contentEquals(other.ownerSignature)
    }

    override fun hashCode(): Int {
        var result = manifest.hashCode()
        result = 31 * result + encryptedContentKey.contentHashCode()
        result = 31 * result + nonce.contentHashCode()
        result = 31 * result + encryptedProjection.contentHashCode()
        result = 31 * result + ownerSignature.contentHashCode()
        return result
    }
}

data class OneTimeViewOffer(
    val offerId: String,
    val teamId: String,
    val targetEmailHash: String,
    val requestedScope: String,
    val expiry: Long,
    val ownerSigningPublicKey: ByteArray,
    val randomChallenge: ByteArray,
    val ownerSignature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OneTimeViewOffer) return false
        return offerId == other.offerId && teamId == other.teamId &&
            targetEmailHash == other.targetEmailHash && requestedScope == other.requestedScope &&
            expiry == other.expiry &&
            ownerSigningPublicKey.contentEquals(other.ownerSigningPublicKey) &&
            randomChallenge.contentEquals(other.randomChallenge) &&
            ownerSignature.contentEquals(other.ownerSignature)
    }

    override fun hashCode(): Int {
        var result = offerId.hashCode()
        result = 31 * result + teamId.hashCode()
        result = 31 * result + targetEmailHash.hashCode()
        result = 31 * result + requestedScope.hashCode()
        result = 31 * result + (expiry xor (expiry ushr 32)).toInt()
        result = 31 * result + ownerSigningPublicKey.contentHashCode()
        result = 31 * result + randomChallenge.contentHashCode()
        result = 31 * result + ownerSignature.contentHashCode()
        return result
    }
}

data class OneTimeViewDeviceRequest(
    val offerId: String,
    val viewerEmailHash: String,
    val devicePublicKey: ByteArray,
    val attestationChain: List<ByteArray>,
    val challengeResponse: ByteArray,
    val viewerSigningKeyId: String,
    val viewerSignature: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OneTimeViewDeviceRequest) return false
        return offerId == other.offerId && viewerEmailHash == other.viewerEmailHash &&
            devicePublicKey.contentEquals(other.devicePublicKey) &&
            attestationChain.map { it.contentHashCode() } == other.attestationChain.map { it.contentHashCode() } &&
            challengeResponse.contentEquals(other.challengeResponse) &&
            viewerSigningKeyId == other.viewerSigningKeyId &&
            viewerSignature.contentEquals(other.viewerSignature)
    }

    override fun hashCode(): Int {
        var result = offerId.hashCode()
        result = 31 * result + viewerEmailHash.hashCode()
        result = 31 * result + devicePublicKey.contentHashCode()
        result = 31 * result + viewerSigningKeyId.hashCode()
        result = 31 * result + viewerSignature.contentHashCode()
        return result
    }
}

data class TrustedViewerDevice(
    val viewerEmailHash: String,
    val deviceKeyId: String,
    val publicKey: ByteArray,
    val attestationSummary: String,
    val firstVerifiedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TrustedViewerDevice) return false
        return viewerEmailHash == other.viewerEmailHash && deviceKeyId == other.deviceKeyId &&
            publicKey.contentEquals(other.publicKey) &&
            attestationSummary == other.attestationSummary && firstVerifiedAt == other.firstVerifiedAt
    }

    override fun hashCode(): Int {
        var result = viewerEmailHash.hashCode()
        result = 31 * result + deviceKeyId.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + attestationSummary.hashCode()
        result = 31 * result + (firstVerifiedAt xor (firstVerifiedAt ushr 32)).toInt()
        return result
    }
}
