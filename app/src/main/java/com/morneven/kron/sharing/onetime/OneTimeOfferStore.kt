package com.morneven.kron.sharing.onetime

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class OfferStatus { PENDING, REQUEST_RECEIVED, CAPSULE_ISSUED, CONSUMED, EXPIRED }

data class OfferRecord(
    val code: String,
    val teamId: String,
    val dataScope: String,
    val status: OfferStatus,
    val createdAt: Long,
    val expiresAt: Long,
    val viewerEmailHash: String = "",
    val viewerDeviceKeyId: String = "",
    val capsuleId: String = "",
)

class OneTimeOfferStore(private val dir: File) {

    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "one-time-offers").also { it.mkdirs() }
    )

    fun save(record: OfferRecord) {
        val obj = JSONObject().apply {
            put("code", record.code)
            put("teamId", record.teamId)
            put("dataScope", record.dataScope)
            put("status", record.status.name)
            put("createdAt", record.createdAt)
            put("expiresAt", record.expiresAt)
            put("viewerEmailHash", record.viewerEmailHash)
            put("viewerDeviceKeyId", record.viewerDeviceKeyId)
            put("capsuleId", record.capsuleId)
        }
        File(dir, "${record.code}.json").writeText(obj.toString())
    }

    fun load(code: String): OfferRecord? = runCatching {
        val obj = JSONObject(File(dir, "$code.json").readText())
        OfferRecord(
            code = obj.getString("code"),
            teamId = obj.getString("teamId"),
            dataScope = obj.getString("dataScope"),
            status = OfferStatus.valueOf(obj.getString("status")),
            createdAt = obj.getLong("createdAt"),
            expiresAt = obj.getLong("expiresAt"),
            viewerEmailHash = obj.optString("viewerEmailHash", ""),
            viewerDeviceKeyId = obj.optString("viewerDeviceKeyId", ""),
            capsuleId = obj.optString("capsuleId", ""),
        )
    }.getOrNull()

    fun listPending(): List<OfferRecord> = list().filter {
        it.status == OfferStatus.PENDING || it.status == OfferStatus.REQUEST_RECEIVED
    }

    fun listIssued(): List<OfferRecord> = list().filter {
        it.status == OfferStatus.CAPSULE_ISSUED && !isExpired(it)
    }

    private fun list(): List<OfferRecord> = dir.listFiles()
        ?.filter { it.extension == "json" }
        ?.mapNotNull { load(it.nameWithoutExtension) }
        ?: emptyList()

    fun delete(code: String) = File(dir, "$code.json").delete()

    private fun isExpired(r: OfferRecord) = r.expiresAt > 0 && System.currentTimeMillis() > r.expiresAt
}
