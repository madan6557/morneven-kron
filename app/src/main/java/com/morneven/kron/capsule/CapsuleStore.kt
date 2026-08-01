package com.morneven.kron.capsule

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

class CapsuleStore(private val context: Context) {
    private val sentDir: File get() = File(context.noBackupFilesDir, "capsule/sent").also { it.mkdirs() }
    private val receivedDir: File get() = File(context.noBackupFilesDir, "capsule/received").also { it.mkdirs() }
    private val secretDir: File get() = File(context.noBackupFilesDir, "capsule/secrets").also { it.mkdirs() }
    private val envelopeDir: File get() = File(context.noBackupFilesDir, "capsule/envelopes").also { it.mkdirs() }

    private val keyStore: KeyStore by lazy { KeyStore.getInstance(ANDROID_KEYSTORE).also { it.load(null) } }
    private val keyAlias = "kron.capsule.wrap.v1"

    private fun ensureWrapKey() {
        if (keyStore.containsAlias(keyAlias)) return
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        kg.init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        kg.generateKey()
    }

    fun wrapSecret(secret: ByteArray): ByteArray? = runCatching {
        ensureWrapKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, keyStore.getKey(keyAlias, null) as javax.crypto.SecretKey)
        val iv = cipher.iv
        val encrypted = cipher.doFinal(secret)
        iv + encrypted
    }.getOrNull()

    fun unwrapSecret(wrapped: ByteArray): ByteArray? = runCatching {
        ensureWrapKey()
        val iv = wrapped.copyOfRange(0, 12)
        val encrypted = wrapped.copyOfRange(12, wrapped.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, keyStore.getKey(keyAlias, null) as javax.crypto.SecretKey, GCMParameterSpec(128, iv))
        cipher.doFinal(encrypted)
    }.getOrNull()

    fun saveSecret(capsuleId: String, secret: ByteArray) {
        val wrapped = wrapSecret(secret) ?: return
        File(secretDir, "$capsuleId.enc").writeBytes(wrapped)
    }

    fun loadSecret(capsuleId: String): ByteArray? {
        val file = File(secretDir, "$capsuleId.enc")
        if (!file.exists()) return null
        return unwrapSecret(file.readBytes())
    }

    fun deleteSecret(capsuleId: String) { File(secretDir, "$capsuleId.enc").delete() }

    fun saveEnvelope(capsuleId: String, data: ByteArray) {
        File(envelopeDir, "$capsuleId.env").writeBytes(data)
    }

    fun loadEnvelope(capsuleId: String): ByteArray? {
        val file = File(envelopeDir, "$capsuleId.env")
        return if (file.exists()) file.readBytes() else null
    }

    fun deleteEnvelope(capsuleId: String) { File(envelopeDir, "$capsuleId.env").delete() }

    fun saveSent(record: CapsuleRecord) {
        val obj = JSONObject().apply {
            put("capsuleId", record.capsuleId)
            put("fileId", record.fileId)
            put("sourceAccountName", record.sourceAccountName)
            put("targetEmail", record.targetEmail)
            put("status", record.status.name)
            put("createdAt", record.createdAt)
            put("claimExpiresAt", record.claimExpiresAt)
            put("dataScope", record.dataScope)
        }
        File(sentDir, "${record.capsuleId}.json").writeText(obj.toString())
    }

    fun loadSent(capsuleId: String): CapsuleRecord? = runCatching {
        val obj = JSONObject(File(sentDir, "$capsuleId.json").readText())
        parseRecord(obj, CapsuleLocalStatus.SENT)
    }.getOrNull()

    fun listSent(): List<CapsuleRecord> = sentDir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { runCatching { parseRecord(JSONObject(it.readText()), CapsuleLocalStatus.SENT) }.getOrNull() }
        ?: emptyList()

    fun saveReceived(record: CapsuleRecord) {
        val obj = JSONObject().apply {
            put("capsuleId", record.capsuleId)
            put("fileId", record.fileId)
            put("sourceAccountName", record.sourceAccountName)
            put("targetEmail", record.targetEmail)
            put("status", record.status.name)
            put("createdAt", record.createdAt)
            put("claimExpiresAt", record.claimExpiresAt)
            put("openedAt", record.openedAt ?: 0)
            put("expiresAt", record.expiresAt ?: 0)
            put("dataScope", record.dataScope)
        }
        File(receivedDir, "${record.capsuleId}.json").writeText(obj.toString())
    }

    fun loadReceived(capsuleId: String): CapsuleRecord? = runCatching {
        val file = File(receivedDir, "$capsuleId.json")
        if (!file.exists()) return@runCatching null
        val obj = JSONObject(file.readText())
        parseRecord(obj, CapsuleLocalStatus.valueOf(obj.getString("status")))
    }.getOrNull()

    fun listReceived(): List<CapsuleRecord> = receivedDir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { f ->
            runCatching {
                val obj = JSONObject(f.readText())
                parseRecord(obj, CapsuleLocalStatus.valueOf(obj.getString("status")))
            }.getOrNull()
        }
        ?: emptyList()

    fun deleteSent(capsuleId: String) { File(sentDir, "$capsuleId.json").delete() }
    fun deleteReceived(capsuleId: String) { File(receivedDir, "$capsuleId.json").delete() }

    fun clearCapsule(capsuleId: String) {
        deleteSecret(capsuleId)
        deleteEnvelope(capsuleId)
    }

    private fun parseRecord(obj: JSONObject, defaultStatus: CapsuleLocalStatus): CapsuleRecord = CapsuleRecord(
        capsuleId = obj.getString("capsuleId"),
        fileId = obj.optString("fileId", ""),
        sourceAccountName = obj.optString("sourceAccountName", ""),
        targetEmail = obj.optString("targetEmail", ""),
        status = obj.optString("status", defaultStatus.name).let { runCatching { CapsuleLocalStatus.valueOf(it) }.getOrDefault(defaultStatus) },
        createdAt = obj.getLong("createdAt"),
        claimExpiresAt = obj.optLong("claimExpiresAt"),
        openedAt = obj.optLong("openedAt").takeIf { it > 0 },
        expiresAt = obj.optLong("expiresAt").takeIf { it > 0 },
        dataScope = obj.optString("dataScope", "CurrentFullSnapshot"),
    )

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}
