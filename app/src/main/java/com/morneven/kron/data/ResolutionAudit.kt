package com.morneven.kron.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

data class ResolutionAuditNode(
    val label: String,
    val detail: String,
    val before: Long,
    val after: Long,
)

data class ResolutionAuditDetail(
    val account: String,
    val channel: String,
    val amount: Long,
    val source: ResolutionAuditNode,
    val target: ResolutionAuditNode,
)

/** Compact, versioned audit data for budget-resolution events. */
object ResolutionAudit {
    private const val VERSION = 1
    private val json = Json { isLenient = false }

    fun snapshots(
        account: String,
        channel: String,
        amount: Long,
        sourceLabel: String,
        sourceDetail: String,
        sourceBefore: Long,
        sourceAfter: Long,
        targetLabel: String,
        targetDetail: String,
        targetBefore: Long,
        targetAfter: Long,
    ): Pair<String, String> {
        fun node(label: String, detail: String, balance: Long) = buildJsonObject {
            put("label", label)
            put("detail", detail)
            put("balance", balance)
        }
        fun root(sourceBalance: Long, targetBalance: Long) = buildJsonObject {
            put("resolutionVersion", VERSION)
            put("account", account)
            put("channel", channel)
            put("amount", amount)
            put("source", node(sourceLabel, sourceDetail, sourceBalance))
            put("target", node(targetLabel, targetDetail, targetBalance))
        }
        return root(sourceBefore, targetBefore).toString() to root(sourceAfter, targetAfter).toString()
    }

    fun parse(beforeJson: String, afterJson: String): ResolutionAuditDetail? = runCatching {
        val before = json.parseToJsonElement(beforeJson).jsonObject
        val after = json.parseToJsonElement(afterJson).jsonObject
        fun JsonObject.string(name: String): String = requireNotNull(this[name]?.jsonPrimitive?.content)
        fun JsonObject.long(name: String): Long = requireNotNull(this[name]?.jsonPrimitive?.longOrNull)
        fun JsonObject.node(name: String): JsonObject = requireNotNull(this[name]?.jsonObject)
        require(before.long("resolutionVersion") == VERSION.toLong() && after.long("resolutionVersion") == VERSION.toLong())
        val sourceBefore = before.node("source")
        val targetBefore = before.node("target")
        val sourceAfter = after.node("source")
        val targetAfter = after.node("target")
        ResolutionAuditDetail(
            account = after.string("account"),
            channel = after.string("channel"),
            amount = after.long("amount"),
            source = ResolutionAuditNode(
                label = sourceAfter.string("label"),
                detail = sourceAfter.string("detail"),
                before = sourceBefore.long("balance"),
                after = sourceAfter.long("balance"),
            ),
            target = ResolutionAuditNode(
                label = targetAfter.string("label"),
                detail = targetAfter.string("detail"),
                before = targetBefore.long("balance"),
                after = targetAfter.long("balance"),
            ),
        )
    }.getOrNull()
}
