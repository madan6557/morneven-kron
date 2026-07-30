package com.morneven.kron.team

import android.content.Context
import com.morneven.kron.BuildConfig
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.backup.TeamSnapshotHistoryException
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.AccountSharingMode
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TeamCapability
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.TeamWorkspaceStatus
import com.morneven.kron.data.TeamRole
import com.morneven.kron.security.EncryptedAttachmentStore
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveApiException
import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.RemoteDriveSnapshot
import com.morneven.kron.sync.SnapshotKind
import com.morneven.kron.sync.SnapshotDag
import com.morneven.kron.sync.ConflictPreview
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

class TeamSnapshotConflictException : IllegalStateException(
    "Head snapshot Team berubah atau memiliki fork. Buka Pusat Konflik sebelum melanjutkan.",
)

sealed interface TeamSyncResult {
    data object NoChanges : TeamSyncResult
    data object NoData : TeamSyncResult
    data object SnapshotRemoved : TeamSyncResult
    data class Uploaded(val snapshotId: String) : TeamSyncResult
    data class Applied(val snapshotId: String) : TeamSyncResult
    data class Conflict(val accountId: Long) : TeamSyncResult
}

internal fun teamSnapshotMissing(error: Throwable): Boolean =
    error is DriveApiException && error.statusCode == 404

data class TeamConflictPreview(
    val accountId: Long,
    val remoteSnapshotId: String,
    val preview: ConflictPreview,
)

@Singleton
class TeamMergeParentStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences("kron_team_merge", Context.MODE_PRIVATE)

    fun remember(teamId: String, remoteHead: String, localHead: String?) {
        if (localHead.isNullOrBlank() || localHead == remoteHead) return
        check(preferences.edit()
            .putString("$teamId.remote", remoteHead)
            .putString("$teamId.local", localHead)
            .commit()) { "Metadata merge Team tidak dapat disimpan" }
    }

    fun parents(teamId: String, remoteHead: String?): List<String> = buildList {
        remoteHead?.takeIf(String::isNotBlank)?.let(::add)
        if (preferences.getString("$teamId.remote", null) == remoteHead) {
            preferences.getString("$teamId.local", null)?.takeIf(String::isNotBlank)?.let(::add)
        }
    }.distinct()

    fun clear(teamId: String) {
        preferences.edit().remove("$teamId.remote").remove("$teamId.local").apply()
    }
}

@Singleton
class TeamSnapshotCoordinator @Inject constructor(
    private val databaseRuntime: DatabaseRuntime,
    private val ledgerPostingEngine: LedgerPostingEngine,
    private val backupManager: BackupManager,
    private val accessGuard: TeamAccessGuard,
    private val keyStore: TeamKeyStore,
    private val drive: TeamDriveRestClient,
    private val cryptor: TeamSnapshotCryptor,
    private val conflictRecoveryStore: TeamConflictRecoveryStore,
    private val mergeParents: TeamMergeParentStore,
    private val attachmentStore: EncryptedAttachmentStore,
    private val blobStore: TeamBlobStore? = null,
) {
    private val database get() = databaseRuntime.current()

    suspend fun sync(accessToken: String, accountId: Long): TeamSyncResult {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        val dao = database.kronDao()
        val account = requireNotNull(dao.accountById(accountId)) { "Team Account tidak ditemukan" }
        val workspace = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(account.sharingMode == AccountSharingMode.TEAM) { "Akun bukan Team" }
        require(account.teamId == workspace.teamId) { "Workspace Team belum siap untuk sync" }
        val canWrite = workspace.localRole != TeamRole.VIEWER
        val head = try {
            workspace.liveFileId?.let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
        } catch (error: Throwable) {
            if (!teamSnapshotMissing(error)) throw error
            dao.markTeamWorkspaceStatus(
                accountId,
                workspace.teamId,
                TeamWorkspaceStatus.REVOKED,
                System.currentTimeMillis(),
            )
            return TeamSyncResult.SnapshotRemoved
        }
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
                recoveringFromPrivateCopy = workspace.status == TeamWorkspaceStatus.LOCAL_ONLY || workspace.headSnapshotId == null,
            )
        ) {
            TeamSyncDecision.NO_CHANGES -> {
                dao.markTeamSnapshotStatus(accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId, TeamWorkspaceStatus.SYNCED, System.currentTimeMillis())
                TeamSyncResult.NoChanges
            }
            TeamSyncDecision.UPLOAD -> TeamSyncResult.Uploaded(publish(accessToken, accountId).manifest.snapshotId)
            TeamSyncDecision.PULL -> {
                try {
                    applyRemoteForRestart(accessToken, workspace, head)
                    TeamSyncResult.Applied(head.manifest.snapshotId)
                } catch (_: TeamSnapshotHistoryException) {
                    conflict(workspace)
                }
            }
            TeamSyncDecision.CONFLICT -> autoResolveIdenticalConflict(accessToken, workspace, head) ?: conflict(workspace)
        }
    }

    private suspend fun autoResolveIdenticalConflict(
        accessToken: String,
        workspace: com.morneven.kron.data.TeamWorkspaceEntity,
        remote: RemoteDriveSnapshot,
    ): TeamSyncResult? = try {
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var envelope = ByteArray(0)
        var payload = ByteArray(0)
        try {
            envelope = drive.download(accessToken, remote.fileId)
            val opened = cryptor.decrypt(envelope, teamKey)
            payload = opened.payload
            require(opened.manifest == remote.manifest) { "Metadata snapshot Team tidak cocok" }
            if (!backupManager.previewTeamSnapshotPayload(
                    payload,
                    workspace.accountId,
                    workspace.headSnapshotId,
                    remote.manifest.snapshotId,
                ).isAlreadyResolved
            ) return null
            if (workspace.localRole == TeamRole.VIEWER) {
                applyRemoteForRestart(accessToken, workspace, remote)
            } else {
                mergeParents.remember(workspace.teamId, remote.manifest.snapshotId, workspace.headSnapshotId)
                mergeRemoteForRestart(accessToken, workspace, remote)
            }
            return TeamSyncResult.Applied(remote.manifest.snapshotId)
        } finally {
            teamKey.fill(0)
            envelope.fill(0)
            payload.fill(0)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Throwable) {
        null
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
            database.kronDao().markTeamSnapshotStatus(
                workspace.accountId,
                workspace.teamId,
                workspace.generation,
                workspace.headSnapshotId,
                TeamWorkspaceStatus.APPLY_PENDING,
                System.currentTimeMillis(),
            )
            databaseRuntime.markPendingSyncActivation()
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
    ): TeamSyncResult.Applied {
        val workspace = requireNotNull(database.kronDao().teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        val head = requireNotNull(workspace.liveFileId) { "File snapshot Team belum tersedia" }
            .let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
        require(head.manifest.snapshotId == expectedRemoteSnapshotId) {
            "Snapshot Team berubah. Muat ulang Pusat Konflik."
        }
        replaceRemoteForRestart(accessToken, workspace, head)
        return TeamSyncResult.Applied(head.manifest.snapshotId)
    }

    suspend fun resolveMerge(
        accessToken: String,
        accountId: Long,
        expectedRemoteSnapshotId: String,
    ): TeamSyncResult.Applied {
        val workspace = requireNotNull(database.kronDao().teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(workspace.localRole != TeamRole.VIEWER) { "Viewer tidak dapat menggabungkan perubahan Team" }
        val head = requireNotNull(workspace.liveFileId) { "File snapshot Team belum tersedia" }
            .let { drive.stableSnapshot(accessToken, it, workspace.teamId) }
        require(head.manifest.snapshotId == expectedRemoteSnapshotId) {
            "Snapshot Team berubah. Muat ulang Pusat Konflik."
        }
        mergeParents.remember(workspace.teamId, head.manifest.snapshotId, workspace.headSnapshotId)
        try {
            mergeRemoteForRestart(accessToken, workspace, head)
        } catch (error: Throwable) {
            mergeParents.clear(workspace.teamId)
            throw error
        }
        return TeamSyncResult.Applied(head.manifest.snapshotId)
    }

    private suspend fun replaceRemoteForRestart(
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
            conflictRecoveryStore.save(
                accountId = workspace.accountId,
                teamId = workspace.teamId,
                localHeadSnapshotId = workspace.headSnapshotId,
                generation = workspace.generation,
                teamKey = teamKey,
            )
            backupManager.stageReplaceExistingTeamAccountForRestart(
                payload = payload,
                teamId = workspace.teamId,
                folderId = workspace.folderId,
                liveFileId = workspace.liveFileId,
                role = workspace.localRole,
                headSnapshotId = remote.manifest.snapshotId,
                generation = remote.manifest.generation,
            )
            database.kronDao().markTeamSnapshotStatus(
                workspace.accountId,
                workspace.teamId,
                workspace.generation,
                workspace.headSnapshotId,
                TeamWorkspaceStatus.APPLY_PENDING,
                System.currentTimeMillis(),
            )
            databaseRuntime.markPendingSyncActivation()
        } finally {
            teamKey.fill(0)
            envelope.fill(0)
            payload.fill(0)
        }
    }

    private suspend fun mergeRemoteForRestart(
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
            backupManager.stageMergeExistingTeamAccountForRestart(
                payload = payload,
                teamId = workspace.teamId,
                folderId = workspace.folderId,
                liveFileId = workspace.liveFileId,
                role = workspace.localRole,
                remoteSnapshotId = remote.manifest.snapshotId,
                remoteGeneration = remote.manifest.generation,
            )
            database.kronDao().markTeamSnapshotStatus(
                workspace.accountId,
                workspace.teamId,
                workspace.generation,
                workspace.headSnapshotId,
                TeamWorkspaceStatus.APPLY_PENDING,
                System.currentTimeMillis(),
            )
            databaseRuntime.markPendingSyncActivation()
        } finally {
            teamKey.fill(0)
            envelope.fill(0)
            payload.fill(0)
        }
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
        require(account.sharingMode == AccountSharingMode.TEAM) { "Akun bukan Team" }
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
            val parentSnapshotIds = mergeParents.parents(workspace.teamId, workspace.headSnapshotId)
            val manifest = DriveSnapshotManifest(
                protocolVersion = 2,
                datasetId = workspace.teamId,
                snapshotId = UUID.randomUUID().toString(),
                parentSnapshotId = parentSnapshotIds.firstOrNull(),
                parentSnapshotIds = parentSnapshotIds,
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
            mergeParents.clear(workspace.teamId)
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
        recoveringFromPrivateCopy: Boolean = false,
    ): TeamSyncDecision {
        if (recoveringFromPrivateCopy) return TeamSyncDecision.PULL
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
