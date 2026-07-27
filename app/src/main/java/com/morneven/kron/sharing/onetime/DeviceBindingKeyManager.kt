package com.morneven.kron.sharing.onetime

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PublicKey
import javax.crypto.Cipher
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceBindingKeyManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) }
    }

    fun getTier(): SingleUseTier {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return SingleUseTier.UNSUPPORTED
        val pm = context.packageManager
        return if (pm.hasSystemFeature(FEATURE_SINGLE_USE_KEY) ||
            pm.hasSystemFeature(FEATURE_LIMITED_USE_KEY)
        ) {
            SingleUseTier.STRICT
        } else {
            SingleUseTier.SUPPORTED_BEST_EFFORT
        }
    }

    fun hasDeviceKey(keyId: String): Boolean = keyStore.containsAlias(alias(keyId))

    fun getOrCreateDeviceKey(keyId: String): KeyPair {
        if (hasDeviceKey(keyId)) {
            return loadDeviceKey(keyId)
        }
        return createDeviceKey(keyId)
    }

    fun getPublicKey(keyId: String): PublicKey? {
        if (!hasDeviceKey(keyId)) return null
        return (keyStore.getCertificate(alias(keyId)) ?: return null).publicKey
    }

    fun deleteDeviceKey(keyId: String) {
        if (hasDeviceKey(keyId)) {
            keyStore.deleteEntry(alias(keyId))
        }
    }

    fun deleteAllDeviceKeys() {
        val aliases = keyStore.aliases().asSequence().filter {
            it.startsWith(KEY_PREFIX)
        }.toList()
        aliases.forEach { keyStore.deleteEntry(it) }
    }

    private fun loadDeviceKey(keyId: String): KeyPair {
        val entry = keyStore.getEntry(alias(keyId), null) as? KeyStore.PrivateKeyEntry
            ?: throw IllegalStateException("Device key tidak ditemukan: $keyId")
        return KeyPair(entry.certificate.publicKey, entry.privateKey)
    }

    private fun createDeviceKey(keyId: String): KeyPair {
        val spec = KeyGenParameterSpec.Builder(
            alias(keyId), KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        ).apply {
            setAlgorithmParameterSpec(
                java.security.spec.ECGenParameterSpec("secp256r1")
            )
            setDigests(KeyProperties.DIGEST_SHA256)
            setKeySize(256)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                setUserAuthenticationRequired(true)
                setUserAuthenticationValidityDurationSeconds(300)
                setUnlockedDeviceRequired(true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setMaxUsageCount(-1)
            }
        }.build()

        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC, ANDROID_KEYSTORE
        )
        generator.initialize(spec)
        return generator.generateKeyPair()
    }

    private val keysDir: java.io.File get() =
        java.io.File(context.noBackupFilesDir, "one-time-keys").also { it.mkdirs() }

    fun createOneTimeContentKey(capsuleId: String): ByteArray? = runCatching {
        val rawKey = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val keyFile = java.io.File(keysDir, "$capsuleId.key")
        keyFile.writeBytes(rawKey)
        rawKey
    }.getOrNull()

    fun decryptWithContentKey(capsuleId: String, ciphertext: ByteArray, nonce: ByteArray): ByteArray? {
        return runCatching {
            val keyFile = java.io.File(keysDir, "$capsuleId.key")
            if (!keyFile.exists()) return null
            val rawKey = keyFile.readBytes()
            val secretKey = javax.crypto.spec.SecretKeySpec(rawKey, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE, secretKey,
                javax.crypto.spec.GCMParameterSpec(128, nonce)
            )
            cipher.doFinal(ciphertext)
        }.getOrNull()
    }

    fun deleteContentKey(capsuleId: String) {
        java.io.File(keysDir, "$capsuleId.key").delete()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_PREFIX = "kron/one-time-view/"
        private const val FEATURE_SINGLE_USE_KEY = "android.hardware.keystore.single_use_key"
        private const val FEATURE_LIMITED_USE_KEY = "android.hardware.keystore.limited_use_key"

        fun alias(keyId: String): String = "$KEY_PREFIX$keyId"
    }
}
