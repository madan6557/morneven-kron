package com.morneven.kron.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.security.KeyStore
import java.security.MessageDigest
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

    private val profileFile: File
        get() = File(context.noBackupFilesDir, "security/database-key-profile-v1.bin")

    private val initializationMarkerFile: File
        get() = File(context.noBackupFilesDir, "security/database-key-initialization-v1.pending")

    @Synchronized
    fun getOrCreateDatabasePassphrase(): ByteArray {
        val file = envelopeFile
        if (file.exists()) {
            return loadAndVerify(file)
        }

        check(!profileFile.exists()) {
            "Profil kunci KRON tersedia tanpa envelope kunci. Pembuatan kunci baru diblokir."
        }
        check(!hasProtectedDataArtifacts()) {
            "Data KRON lama ditemukan tanpa envelope kunci. Pembuatan kunci baru diblokir."
        }

        markNewDatabaseInitialization()
        return createDatabasePassphrase()
    }

    /**
     * Creates a key only after the caller has positively validated the primary
     * database as a readable legacy plaintext or empty-key database.
     */
    @Synchronized
    fun getOrCreateForValidatedLegacyDatabase(): ByteArray {
        if (envelopeFile.exists()) return loadAndVerify(envelopeFile)
        check(!profileFile.exists()) {
            "Profil kunci KRON tersedia tanpa envelope kunci. Migrasi diblokir."
        }
        check(!hasEncryptedAttachmentArtifacts()) {
            "Lampiran terenkripsi ditemukan tanpa envelope kunci. Migrasi diblokir."
        }
        return createDatabasePassphrase()
    }

    @Synchronized
    fun confirmRawKeyProfile() {
        val root = loadExistingDatabasePassphrase() ?: throw DatabaseKeyUnavailableException(
            "Envelope kunci database KRON tidak tersedia.",
        )
        try {
            if (profileFile.exists()) {
                verifyProfile(profileFile, root)
                initializationMarkerFile.delete()
                return
            }
            profileFile.parentFile?.mkdirs()
            val temporary = File(profileFile.parentFile, "${profileFile.name}.new")
            try {
                val fingerprint = fingerprint(root)
                FileOutputStream(temporary).use { output ->
                    DataOutputStream(output).use { data ->
                        data.write(PROFILE_MAGIC)
                        data.writeByte(RAW_HEX_SQLCIPHER_4_ENCODING)
                        data.write(fingerprint)
                        data.flush()
                        output.fd.sync()
                    }
                }
                atomicReplace(temporary, profileFile)
                verifyProfile(profileFile, root)
                initializationMarkerFile.delete()
            } finally {
                temporary.delete()
            }
        } finally {
            root.fill(0)
        }
    }

    fun isRawKeyProfileProvisioned(): Boolean = profileFile.exists()

    fun isNewDatabaseInitializationPending(): Boolean =
        initializationMarkerFile.exists() && runCatching {
            initializationMarkerFile.readBytes().contentEquals(INITIALIZATION_MAGIC)
        }.getOrDefault(false)

    fun isExistingRawKeyProfileValid(): Boolean {
        if (!profileFile.exists()) return false
        val root = runCatching { loadExistingDatabasePassphrase() }.getOrNull() ?: return false
        return try {
            runCatching { verifyProfile(profileFile, root) }.isSuccess
        } finally {
            root.fill(0)
        }
    }

    private fun createDatabasePassphrase(): ByteArray {
        val wrappingKey = getOrCreateWrappingKey()
        val key = ByteArray(DATA_KEY_BYTES).also(SecureRandom()::nextBytes)
        envelopeFile.parentFile?.mkdirs()
        val temporary = File(envelopeFile.parentFile, "${envelopeFile.name}.new")
        try {
            wrap(temporary, wrappingKey, key)
            atomicReplace(temporary, envelopeFile)
            return key.copyOf()
        } finally {
            key.fill(0)
            temporary.delete()
        }
    }

    private fun markNewDatabaseInitialization() {
        if (isNewDatabaseInitializationPending()) return
        initializationMarkerFile.parentFile?.mkdirs()
        val temporary = File(initializationMarkerFile.parentFile, "${initializationMarkerFile.name}.new")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(INITIALIZATION_MAGIC)
                output.flush()
                output.fd.sync()
            }
            atomicReplace(temporary, initializationMarkerFile)
        } finally {
            temporary.delete()
        }
    }

    /**
     * Reads the current device key without generating a replacement. Existing
     * encrypted data must never cause a new key envelope to be created.
     */
    @Synchronized
    fun loadExistingDatabasePassphrase(): ByteArray? {
        val file = envelopeFile
        if (!file.exists()) return null
        return loadAndVerify(file)
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

    private fun loadAndVerify(file: File): ByteArray {
        val wrappingKey = existingWrappingKey() ?: throw DatabaseKeyUnavailableException(
            "Kunci perangkat untuk database KRON tidak tersedia.",
        )
        val root = unwrap(file, wrappingKey)
        try {
            if (profileFile.exists()) verifyProfile(profileFile, root)
            return root
        } catch (error: Exception) {
            root.fill(0)
            throw error
        }
    }

    private fun verifyProfile(source: File, root: ByteArray) {
        val expectedFingerprint = fingerprint(root)
        source.inputStream().buffered().use { input ->
            DataInputStream(input).use { data ->
                val magic = ByteArray(PROFILE_MAGIC.size).also(data::readFully)
                require(magic.contentEquals(PROFILE_MAGIC)) { "Profil kunci KRON tidak valid" }
                require(data.readUnsignedByte() == RAW_HEX_SQLCIPHER_4_ENCODING) {
                    "Encoding kunci database KRON tidak didukung"
                }
                val storedFingerprint = ByteArray(KEY_FINGERPRINT_BYTES).also(data::readFully)
                require(data.read() == -1) { "Profil kunci KRON memiliki data tambahan" }
                require(MessageDigest.isEqual(storedFingerprint, expectedFingerprint)) {
                    "Envelope dan profil kunci database KRON tidak cocok"
                }
            }
        }
    }

    private fun fingerprint(root: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(root)

    private fun hasProtectedDataArtifacts(): Boolean {
        val primary = context.getDatabasePath(PRIMARY_DATABASE_NAME)
        val databaseArtifacts = primary.parentFile?.listFiles()?.any { candidate ->
            candidate.name.startsWith(primary.name) || candidate.name.startsWith(".${primary.name}")
        } == true
        return databaseArtifacts ||
            File(context.filesDir, PENDING_RESTORE_DIRECTORY).exists() ||
            hasEncryptedAttachmentArtifacts()
    }

    private fun hasEncryptedAttachmentArtifacts(): Boolean =
        File(context.noBackupFilesDir, RECEIPTS_DIRECTORY)
            .listFiles()
            ?.any { it.isFile } == true

    private fun existingWrappingKey(): SecretKey? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return keyStore.getKey(KEY_ALIAS, null) as? SecretKey
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        existingWrappingKey()?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private fun wrap(target: File, wrappingKey: SecretKey, dataKey: ByteArray) {
        val nonce = ByteArray(GCM_NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey, GCMParameterSpec(GCM_TAG_BITS, nonce))
        val encrypted = cipher.doFinal(dataKey)
        FileOutputStream(target).use { output ->
            DataOutputStream(output).use { data ->
                data.write(MAGIC)
                data.write(nonce)
                data.writeInt(encrypted.size)
                data.write(encrypted)
                data.flush()
                output.fd.sync()
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
        private const val PRIMARY_DATABASE_NAME = "kron-v4.db"
        private const val PENDING_RESTORE_DIRECTORY = "pending-restore-v2"
        private const val RECEIPTS_DIRECTORY = "receipts"
        private const val RAW_HEX_SQLCIPHER_4_ENCODING = 1
        private const val KEY_FINGERPRINT_BYTES = 32
        private val MAGIC = "KRONKEY1".toByteArray(Charsets.US_ASCII)
        private val PROFILE_MAGIC = "KRONDBP1".toByteArray(Charsets.US_ASCII)
        private val INITIALIZATION_MAGIC = "KRONINIT1".toByteArray(Charsets.US_ASCII)
    }
}

class DatabaseKeyUnavailableException(message: String) : IllegalStateException(message)
