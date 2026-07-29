package com.morneven.kron.team

import com.morneven.kron.BuildConfig
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TeamCapability
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.TeamWorkspaceStatus
import com.morneven.kron.data.TeamRole
import com.morneven.kron.security.EncryptedAttachmentStore
import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.RemoteDriveSnapshot
import com.morneven.kron.sync.SnapshotKind
import com.morneven.kron.sync.SnapshotDag
import com.morneven.kron.sync.ConflictPreview
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class TeamSnapshotConflictException : IllegalStateException(
    "Head snapshot Team berubah atau memiliki fork. Buka Pusat Konflik sebelum melanjutkan.",
)

sealed interface TeamSyncResult {
    data object NoChanges : TeamSyncResult
    data object NoData : TeamSyncResult
    data class Uploaded(val snapshotId: String) : TeamSyncResult
    data class RestartRequired(val snapshotId: String) : TeamSyncResult
    data class Conflict(val accountId: Long) : TeamSyncResult
}

data class TeamConflictPreview(
    val accountId: Long,
    val remoteSnapshotId: String,
    val preview: ConflictPreview,
)

@Singleton
class TeamSnapshotCoordinator @Inject constructor(
    private val database: KronDatabase,
    private val ledgerPostingEngine: LedgerPostingEngine,
    private val backupManager: BackupManager,
    private val accessGuard: TeamAccessGuard,
    private val keyStore: TeamKeyStore,
    private val drive: TeamDriveRestClient,
    private val cryptor: TeamSnapshotCryptor,
    private val attachmentStore: EncryptedAttachmentStore,
    private val blobStore: TeamBlobStore? = null,
) {
    suspend fun sync(accessToken: String, accountId: Long): TeamSyncResult {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        val dao = database.kronDao()
        val account = requireNotNull(dao.accountById(accountId)) { "Team Account tidak ditemukan" }
        val workspace = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(account.teamId == workspace.teamId) { "Workspace Team belum siap untuk sync" }
        val canWrite = workspace.localRole != TeamRole.VIEWER
        val head = workspace.liveFileId?.let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
        if (head == null) return if (canWrite) TeamSyncResult.Uploaded(publish(accessToken, accountId).manifest.snapshotId) else TeamSyncResult.NoData
        return when (
            TeamSyncPolicy.decide(
                canWrite = canWrite,
                localHeadId = workspace.headSnapshotId,
                localGeneration = workspace.generation,
                remoteHeadId = head.manifest.snapshotId,
                remoteGeneration = head.manifest.generation,
                baseGeneration = workspace.headSnapshotId?.let { head.manifest.parentSnapshotIds.takeIf { parents -> it in parents }?.let { workspace.generation } },
                remoteDescendsFromBase = workspace.headSnapshotId in head.manifest.parentSnapshotIds,
            )
        ) {
            TeamSyncDecision.NO_CHANGES -> {
                dao.markTeamSnapshotStatus(accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId, TeamWorkspaceStatus.SYNCED, System.currentTimeMillis())
                TeamSyncResult.NoChanges
            }
            TeamSyncDecision.UPLOAD -> TeamSyncResult.Uploaded(publish(accessToken, accountId).manifest.snapshotId)
            TeamSyncDecision.PULL -> {
                applyRemoteForRestart(accessToken, workspace, head)
                markPrivateRecoveryChanged()
                TeamSyncResult.RestartRequired(head.manifest.snapshotId)
            }
            TeamSyncDecision.CONFLICT -> conflict(workspace)
        }
    }

    private suspend fun applyRemoteForRestart(
        accessToken: String,
        workspace: com.morneven.kron.data.TeamWorkspaceEntity,
        remote: RemoteDriveSnapshot,
    ) {
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var envelope = ByteArray(0)
        var payload = ByteArray(0)
        try {
            envelope = drive.download(accessToken, remote.fileId)
            val opened = cryptor.decrypt(envelope, teamKey)
            payload = opened.payload
            require(opened.manifest == remote.manifest) { "Metadata snapshot Team tidak cocok" }
            backupManager.stageExistingTeamAccountForRestart(
                payload = payload,
                teamId = workspace.teamId,
                folderId = workspace.folderId,
                liveFileId = workspace.liveFileId,
                role = workspace.localRole,
                headSnapshotId = remote.manifest.snapshotId,
                generation = remote.manifest.generation,
            )
        } finally {
            teamKey.fill(0)
            envelope.fill(0)
            payload.fill(0)
        }
    }

    suspend fun previewConflict(accessToken: String, accountId: Long): TeamConflictPreview {
        val workspace = requireNotNull(database.kronDao().teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        val head = requireNotNull(workspace.liveFileId) { "File snapshot Team belum tersedia" }
            .let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var envelope = ByteArray(0)
        var payload = ByteArray(0)
        try {
            envelope = drive.download(accessToken, head.fileId)
            val opened = cryptor.decrypt(envelope, teamKey)
            payload = opened.payload
            require(opened.manifest == head.manifest) { "Metadata snapshot Team tidak cocok" }
            return TeamConflictPreview(
                accountId = accountId,
                remoteSnapshotId = head.manifest.snapshotId,
                preview = backupManager.previewTeamSnapshotPayload(
                    payload, accountId, workspace.headSnapshotId, head.manifest.snapshotId,
                ),
            )
        } finally {
            teamKey.fill(0)
            envelope.fill(0)
            payload.fill(0)
        }
    }

    suspend fun resolveUseTeam(
        accessToken: String,
        accountId: Long,
        expectedRemoteSnapshotId: String,
    ): TeamSyncResult.RestartRequired {
        val workspace = requireNotNull(database.kronDao().teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        val head = requireNotNull(workspace.liveFileId) { "File snapshot Team belum tersedia" }
            .let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
        require(head.manifest.snapshotId == expectedRemoteSnapshotId) {
            "Snapshot Team berubah. Muat ulang Pusat Konflik."
        }
        applyRemoteForRestart(accessToken, workspace, head)
        markPrivateRecoveryChanged()
        return TeamSyncResult.RestartRequired(head.manifest.snapshotId)
    }

    private suspend fun conflict(workspace: com.morneven.kron.data.TeamWorkspaceEntity): TeamSyncResult.Conflict {
        markConflict(workspace.accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId)
        return TeamSyncResult.Conflict(workspace.accountId)
    }

    suspend fun publish(accessToken: String, accountId: Long): RemoteDriveSnapshot {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        accessGuard.require(accountId, TeamCapability.WRITE)
        val dao = database.kronDao()
        val account = requireNotNull(dao.accountById(accountId)) { "Team Account tidak ditemukan" }
        val workspace = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(account.teamId == workspace.teamId) {
            "Workspace Team belum siap untuk sync"
        }
        val deviceId = requireNotNull(dao.syncState()?.deviceId?.takeIf(String::isNotBlank)) {
            "Identitas perangkat Team tidak tersedia"
        }
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var payload = ByteArray(0)
        var envelope = ByteArray(0)
        try {
            val before = workspace.liveFileId?.let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
            if (workspace.liveFileId != null && before?.manifest?.snapshotId != workspace.headSnapshotId) {
                markConflict(workspace.accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId)
                throw TeamSnapshotConflictException()
            }
            ledgerPostingEngine.finalizeUnsealedEvents()
            payload = backupManager.createTeamSnapshotPayload(accountId)
            val current = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team berubah selama snapshot" }
            if (current.generation != workspace.generation || current.headSnapshotId != workspace.headSnapshotId) {
                throw TeamSnapshotConflictException()
            }
            val manifest = DriveSnapshotManifest(
                protocolVersion = 2,
                datasetId = workspace.teamId,
                snapshotId = UUID.randomUUID().toString(),
                parentSnapshotId = workspace.headSnapshotId,
                parentSnapshotIds = listOfNotNull(workspace.headSnapshotId),
                generation = workspace.generation,
                sourceDeviceId = deviceId,
                schemaVersion = KronDatabase.SCHEMA_VERSION,
                minimumAppVersionCode = BuildConfig.VERSION_CODE,
                createdAtEpochMillis = System.currentTimeMillis(),
                payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
            )
            envelope = cryptor.encrypt(manifest, payload, teamKey)
            val uploaded = drive.uploadSnapshot(
                accessToken,
                workspace.folderId,
                workspace.teamId,
                manifest,
                envelope,
                workspace.liveFileId,
            )
            val after = drive.stableSnapshot(accessToken, uploaded.fileId, workspace.teamId)
            if (after.manifest.snapshotId != uploaded.manifest.snapshotId) {
                markConflict(workspace.accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId)
                throw TeamSnapshotConflictException()
            }
            if (workspace.liveFileId == null) {
                require(dao.setTeamLiveFileId(accountId, workspace.teamId, uploaded.fileId, System.currentTimeMillis()) == 1) {
                    "File snapshot Team tidak dapat disimpan"
                }
            }
            val updated = dao.markTeamSnapshotPublished(
                accountId = workspace.accountId,
                teamId = workspace.teamId,
                generation = workspace.generation,
                expectedHead = workspace.headSnapshotId,
                snapshotId = uploaded.manifest.snapshotId,
                status = TeamWorkspaceStatus.SYNCED,
                updatedAt = System.currentTimeMillis(),
            )
            if (updated != 1) throw TeamSnapshotConflictException()
            markPrivateRecoveryChanged()
            try {
                uploadReceiptBlobs(accessToken, accountId, teamKey)
            } catch (_: Exception) {
                // ponytail: gagal upload bukti bukan kegagalan snapshot
            }
            return uploaded
        } finally {
            teamKey.fill(0)
            payload.fill(0)
            envelope.fill(0)
        }
    }

    suspend fun uploadReceiptBlobs(
        accessToken: String,
        accountId: Long,
        teamKey: ByteArray,
    ): Map<String, TeamBlobReference> {
        val blobStore = requireNotNull(blobStore) { "Blob store belum dikonfigurasi" }
        val workspace = requireNotNull(database.kronDao().teamWorkspace(accountId)) { "Workspace tidak ditemukan" }
        val receipts = database.kronDao().receiptsForAccount(accountId)
            .filter { !it.localPath.isNullOrBlank() }
        val refs = mutableMapOf<String, TeamBlobReference>()
        for (receipt in receipts) {
            val plaintext = decryptReceipt(receipt)
            val ref = blobStore.upload(accessToken, workspace.folderId, workspace.teamId, plaintext, teamKey)
            refs[receipt.storageId] = ref
        }
        return refs
    }

    private fun decryptReceipt(receipt: ReceiptEntity): ByteArray {
        val file = java.io.File(requireNotNull(receipt.localPath) { "File bukti ${receipt.storageId} tidak ditemukan" })
        if (receipt.encryptionVersion != EncryptedAttachmentStore.ENCRYPTION_VERSION) return file.readBytes()
        val bos = ByteArrayOutputStream()
        attachmentStore.decrypt(file, bos)
        return bos.toByteArray()
    }

    private suspend fun markConflict(accountId: Long, teamId: String, generation: Long, expectedHead: String?) {
        database.kronDao().markTeamSnapshotStatus(
            accountId,
            teamId,
            generation,
            expectedHead,
            TeamWorkspaceStatus.CONFLICT,
            System.currentTimeMillis(),
        )
    }

    private suspend fun markPrivateRecoveryChanged() {
        database.kronDao().markPrivateRecoveryChanged(System.currentTimeMillis())
    }
}

internal object TeamSnapshotHeadPolicy {
    fun matches(snapshots: List<RemoteDriveSnapshot>, expectedHead: String?): Boolean {
        val normalizedExpectedHead = expectedHead?.takeIf(String::isNotBlank)
        val datasetIds = snapshots.asSequence()
            .filter { it.manifest.kind == SnapshotKind.ACTIVE }
            .map { it.manifest.datasetId }
            .distinct()
            .toList()
        if (datasetIds.size > 1) return false
        val datasetId = datasetIds.singleOrNull()
        val dag = datasetId?.let { SnapshotDag.inspect(snapshots, it) }
            ?: SnapshotDag.Inspection(emptyList(), valid = true)
        if (!dag.valid) return false
        val heads = dag.heads
        return when {
            normalizedExpectedHead == null -> heads.isEmpty()
            else -> heads.singleOrNull()?.manifest?.snapshotId == normalizedExpectedHead
        }
    }
}

internal enum class TeamSyncDecision { NO_CHANGES, UPLOAD, PULL, CONFLICT }

internal object TeamSyncPolicy {
    fun decide(
        canWrite: Boolean,
        localHeadId: String?,
        localGeneration: Long,
        remoteHeadId: String,
        remoteGeneration: Long,
        baseGeneration: Long?,
        remoteDescendsFromBase: Boolean,
    ): TeamSyncDecision {
        if (localHeadId == remoteHeadId) {
            return when {
                localGeneration == remoteGeneration -> TeamSyncDecision.NO_CHANGES
                localGeneration > remoteGeneration && canWrite -> TeamSyncDecision.UPLOAD
                else -> TeamSyncDecision.CONFLICT
            }
        }
        if (baseGeneration == null || !remoteDescendsFromBase || remoteGeneration < baseGeneration) {
            return TeamSyncDecision.CONFLICT
        }
        return if (localGeneration == baseGeneration) TeamSyncDecision.PULL else TeamSyncDecision.CONFLICT
    }
}
