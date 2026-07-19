package com.morneven.kron.sync

import java.time.Instant

const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"

data class GoogleAccountIdentity(
    val subjectId: String,
    val email: String,
    val displayName: String? = null,
) {
    init {
        require(subjectId.isNotBlank()) { "Identitas akun Google tidak valid" }
        require(email.isNotBlank()) { "Email akun Google tidak valid" }
    }
}

enum class SyncStatus {
    DISABLED,
    DISCONNECTED,
    IDLE,
    SYNCING,
    SYNCED,
    WAITING_FOR_NETWORK,
    AUTHORIZATION_REQUIRED,
    PASSPHRASE_REQUIRED,
    CONFLICT,
    RESTART_REQUIRED,
    FREE_ONLY_BLOCKED,
    ERROR,
}

data class SyncState(
    val datasetId: String,
    val deviceId: String,
    val localGeneration: Long = 0,
    val lastSyncedGeneration: Long = -1,
    val parentSnapshotId: String? = null,
    val lastSnapshotId: String? = null,
    val lastSyncedAtEpochMillis: Long? = null,
    val status: SyncStatus = SyncStatus.DISCONNECTED,
    val lastError: String? = null,
    val accountSubject: String? = null,
    val accountEmail: String? = null,
    val disabledDueToBilling: Boolean = false,
    val conflictRemoteFileId: String? = null,
) {
    init {
        require(datasetId.isNotBlank()) { "Dataset ID tidak boleh kosong" }
        require(deviceId.isNotBlank()) { "Device ID tidak boleh kosong" }
        require(localGeneration >= 0) { "Generasi lokal tidak valid" }
        require(lastSyncedGeneration >= -1) { "Generasi sinkron terakhir tidak valid" }
    }
}

enum class SnapshotKind {
    ACTIVE,
    RECOVERY,
}

data class DriveSnapshotManifest(
    val protocolVersion: Int = 1,
    val datasetId: String,
    val snapshotId: String,
    val parentSnapshotId: String?,
    val generation: Long,
    val sourceDeviceId: String,
    val schemaVersion: Int,
    val minimumAppVersionCode: Int,
    val createdAtEpochMillis: Long,
    val payloadSha256: String,
    val kdfIterations: Int = 310_000,
    val kind: SnapshotKind = SnapshotKind.ACTIVE,
) {
    init {
        require(protocolVersion == 1) { "Versi protokol snapshot tidak didukung" }
        require(datasetId.isNotBlank() && snapshotId.isNotBlank() && sourceDeviceId.isNotBlank())
        require(generation >= 0 && schemaVersion > 0 && minimumAppVersionCode > 0)
        require(payloadSha256.matches(Regex("[0-9a-f]{64}"))) { "Checksum snapshot tidak valid" }
        require(kdfIterations in 210_000..2_000_000) { "Parameter derivasi kunci tidak valid" }
    }

    fun toAppProperties(): Map<String, String> = mapOf(
        "protocol" to protocolVersion.toString(),
        "dataset" to datasetId,
        "snapshot" to snapshotId,
        "parent" to parentSnapshotId.orEmpty(),
        "generation" to generation.toString(),
        "device" to sourceDeviceId,
        "schema" to schemaVersion.toString(),
        "minApp" to minimumAppVersionCode.toString(),
        "created" to createdAtEpochMillis.toString(),
        "sha256" to payloadSha256,
        "kdf" to kdfIterations.toString(),
        "kind" to kind.name,
        "product" to "KRON",
    )

    companion object {
        fun fromAppProperties(properties: Map<String, String>): DriveSnapshotManifest? = runCatching {
            if (properties["product"] != "KRON") return null
            DriveSnapshotManifest(
                protocolVersion = properties.getValue("protocol").toInt(),
                datasetId = properties.getValue("dataset"),
                snapshotId = properties.getValue("snapshot"),
                parentSnapshotId = properties["parent"]?.takeIf(String::isNotBlank),
                generation = properties.getValue("generation").toLong(),
                sourceDeviceId = properties.getValue("device"),
                schemaVersion = properties.getValue("schema").toInt(),
                minimumAppVersionCode = properties.getValue("minApp").toInt(),
                createdAtEpochMillis = properties.getValue("created").toLong(),
                payloadSha256 = properties.getValue("sha256"),
                kdfIterations = properties.getValue("kdf").toInt(),
                kind = SnapshotKind.valueOf(properties.getValue("kind")),
            )
        }.getOrNull()
    }
}

data class RemoteDriveSnapshot(
    val fileId: String,
    val name: String,
    val manifest: DriveSnapshotManifest,
    val createdAt: Instant,
    val sizeBytes: Long,
) {
    init {
        require(fileId.isNotBlank() && name.isNotBlank() && sizeBytes >= 0)
    }
}

data class LocalDatasetSnapshot(
    val datasetId: String,
    val generation: Long,
    val schemaVersion: Int,
    val hasFinancialData: Boolean,
) {
    init {
        require(datasetId.isNotBlank() && generation >= 0 && schemaVersion > 0)
    }
}

enum class SyncConflictReason {
    FIRST_CONNECTION_WITH_TWO_DATASETS,
    BOTH_SIDES_CHANGED,
    ACCOUNT_CHANGED,
    DATASET_MISMATCH,
    REMOTE_CHANGED_DURING_RESOLUTION,
}

data class SyncConflict(
    val reason: SyncConflictReason,
    val local: LocalDatasetSnapshot,
    val remote: RemoteDriveSnapshot?,
    val expectedLastSnapshotId: String?,
)

enum class ConflictResolution {
    KEEP_BOTH,
    USE_THIS_DEVICE,
    USE_DRIVE,
}

sealed interface SyncRunResult {
    data class Synchronized(val snapshotId: String, val uploaded: Boolean) : SyncRunResult
    data class RestartRequired(val snapshotId: String) : SyncRunResult
    data object NoChanges : SyncRunResult
    data object NoData : SyncRunResult
    data object Disabled : SyncRunResult
    data object AuthorizationRequired : SyncRunResult
    data object PassphraseRequired : SyncRunResult
    data object FreeOnlyBlocked : SyncRunResult
    data class Conflict(val value: SyncConflict) : SyncRunResult
    data class Error(val message: String, val retryable: Boolean) : SyncRunResult
}

interface SyncStateStore {
    suspend fun read(): SyncState
    suspend fun update(transform: (SyncState) -> SyncState): SyncState
}

interface LocalSnapshotSource {
    suspend fun describe(): LocalDatasetSnapshot
    suspend fun exportSnapshotPayload(): ByteArray

    /** Must validate and replace local data atomically. */
    suspend fun applyRemoteAtomically(
        payload: ByteArray,
        manifest: DriveSnapshotManifest,
        account: GoogleAccountIdentity,
    ): LocalApplyOutcome
}

enum class LocalApplyOutcome {
    APPLIED,
    RESTART_REQUIRED,
}

fun interface SyncSecretProvider {
    /** Returns a new mutable copy. The caller clears it immediately after use. */
    suspend fun acquirePassphrase(): CharArray?
}
