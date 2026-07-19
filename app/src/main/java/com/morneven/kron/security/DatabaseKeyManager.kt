package com.morneven.kron.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DatabaseKeyManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val envelopeFile: File
        get() = File(context.noBackupFilesDir, "security/database-key-v1.bin")

    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val wrappingKey = getOrCreateWrappingKey()
        val file = envelopeFile
        if (file.exists()) return unwrap(file, wrappingKey)

        val key = ByteArray(DATA_KEY_BYTES).also(SecureRandom()::nextBytes)
        file.parentFile?.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.new")
        try {
            wrap(temporary, wrappingKey, key)
            atomicReplace(temporary, file)
            return key.copyOf()
        } finally {
            key.fill(0)
            temporary.delete()
        }
    }

    fun deriveSubkey(label: String): SecretKeySpec {
        val root = getOrCreateDatabasePassphrase()
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(root, "HmacSHA256"))
            SecretKeySpec(mac.doFinal(label.toByteArray(Charsets.UTF_8)), "AES")
        } finally {
            root.fill(0)
        }
    }

    fun isProvisioned(): Boolean = envelopeFile.exists()

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(target: File, wrappingKey: SecretKey, dataKey: ByteArray) {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(dataKey)
        target.outputStream().buffered().use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.write(nonce)
                data.writeInt(encrypted.size)
                data.write(encrypted)
            }
        }
    }

    private fun unwrap(source: File, wrappingKey: SecretKey): ByteArray =
        source.inputStream().buffered().use { input ->
            DataInputStream(input).use { data ->
                val magic = ByteArray(MAGIC.size).also(data::readFully)
                require(magic.contentEquals(MAGIC)) { "Penyimpanan kunci KRON tidak valid" }
                val nonce = ByteArray(GCM_NONCE_BYTES).also(data::readFully)
                val encryptedSize = data.readInt()
                require(encryptedSize in 1..MAX_ENVELOPE_BYTES) { "Ukuran penyimpanan kunci tidak valid" }
                val encrypted = ByteArray(encryptedSize).also(data::readFully)
                require(data.read() == -1) { "Penyimpanan kunci memiliki data tambahan" }
                val cipher = Cipher.getInstance(AES_GCM)
                cipher.init(Cipher.DECRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
                cipher.doFinal(encrypted).also {
                    require(it.size == DATA_KEY_BYTES) { "Panjang kunci database tidak valid" }
                }
            }
        }

    private fun atomicReplace(source: File, target: File) {
        target.parentFile?.mkdirs()
        if (!source.renameTo(target)) {
            source.inputStream().use { input ->
                target.outputStream().use { output ->
                    input.copyTo(output)
                    output.flush()
                    output.fd.sync()
                }
            }
            source.delete()
        }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "kron.database.wrap.v1"
        private const val AES_GCM = "AES/GCM/NoPadding"
        private const val DATA_KEY_BYTES = 32
        private const val GCM_NONCE_BYTES = 12
        private const val GCM_TAG_BITS = 128
        private const val MAX_ENVELOPE_BYTES = 1024
        private val MAGIC = "KRONKEY1".toByteArray(Charsets.US_ASCII)
    }
}
