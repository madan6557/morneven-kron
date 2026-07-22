package com.morneven.kron.audit

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.morneven.kron.data.EvidenceKeyEntity
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.X509Certificate
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EvidenceSigningKeyManager @Inject constructor() {
    fun hasKey(): Boolean = keyStore().containsAlias(KEY_ALIAS)

    fun publicRecord(): EvidenceKeyEntity {
        val certificate = certificate()
        val publicKey = certificate.publicKey.encoded
        val fingerprint = sha256(certificate.encoded)
        return EvidenceKeyEntity(
            id = "ecdsa-p256:$fingerprint",
            alias = KEY_ALIAS,
            algorithm = SIGNATURE_ALGORITHM,
            publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey),
            certificateBase64 = Base64.getEncoder().encodeToString(certificate.encoded),
            fingerprint = fingerprint,
            securityLevel = "ANDROID_KEYSTORE",
        )
    }

    fun sign(payload: ByteArray): String {
        ensureKey()
        val keyStore = keyStore()
        val privateKey = requireNotNull(keyStore.getKey(KEY_ALIAS, null))
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
            initSign(privateKey as java.security.PrivateKey)
            update(payload)
        }
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    fun verify(payload: ByteArray, signatureBase64: String, certificateBase64: String): Boolean = runCatching {
        val certificateFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val certificate = certificateFactory.generateCertificate(
            Base64.getDecoder().decode(certificateBase64).inputStream(),
        ) as X509Certificate
        Signature.getInstance(SIGNATURE_ALGORITHM).run {
            initVerify(certificate.publicKey)
            update(payload)
            verify(Base64.getDecoder().decode(signatureBase64))
        }
    }.getOrDefault(false)

    private fun certificate(): X509Certificate {
        ensureKey()
        return keyStore().getCertificate(KEY_ALIAS) as X509Certificate
    }

    private fun ensureKey() {
        if (hasKey()) return
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE).apply {
            initialize(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
                )
                    .setAlgorithmParameterSpec(java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build(),
            )
            generateKeyPair()
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val KEY_ALIAS = "kron.evidence.sign.v1"
        const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
