package com.morneven.kron.sharing.onetime

import android.content.Context
import java.io.File

class OneTimeDriveRelay(private val context: Context) {

    private val exchangeDir: File get() =
        File(context.noBackupFilesDir, "one-time-exchange").also { it.mkdirs() }

    fun publishOffer(offer: OneTimeViewOffer, scope: String) {
        val dir = File(exchangeDir, offer.offerId).also { it.mkdirs() }
        File(dir, "offer.json").writeText(
            """{"offerId":"${offer.offerId}","scope":"$scope","expiry":${offer.expiry}}"""
        )
    }

    fun readOffer(offerId: String): OneTimeViewOffer? = runCatching {
        val dir = File(exchangeDir, offerId)
        val json = org.json.JSONObject(File(dir, "offer.json").readText())
        OneTimeViewOffer(
            offerId = json.getString("offerId"),
            teamId = json.optString("teamId", ""),
            targetEmailHash = "", requestedScope = json.getString("scope"),
            expiry = json.getLong("expiry"),
            ownerSigningPublicKey = ByteArray(0),
            randomChallenge = ByteArray(0), ownerSignature = ByteArray(0),
        )
    }.getOrNull()

    fun publishCapsule(offerId: String, capsule: ViewCapsule) {
        val dir = File(exchangeDir, offerId).also { it.mkdirs() }
        val bytes = ViewCapsuleCodec.serializeCapsule(capsule)
        File(dir, "capsule.bin").writeBytes(bytes)
        File(dir, "status.txt").writeText("CAPSULE_ISSUED")
    }

    fun readCapsule(offerId: String): ViewCapsule? = runCatching {
        val bytes = File(File(exchangeDir, offerId), "capsule.bin").readBytes()
        ViewCapsuleCodec.deserializeCapsule(bytes)
    }.getOrNull()

    fun cleanExchange(offerId: String) {
        File(exchangeDir, offerId).deleteRecursively()
    }

    fun listIncoming(): List<String> =
        exchangeDir.listFiles()?.filter { it.isDirectory }?.map { it.name } ?: emptyList()
}
