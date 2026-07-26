package com.morneven.kron.team

import com.morneven.kron.BuildConfig
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TeamCapability
import com.morneven.kron.data.TeamWorkspaceStatus
import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.RemoteDriveSnapshot
import com.morneven.kron.sync.SnapshotKind
import com.morneven.kron.sync.SnapshotDag
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

class TeamSnapshotConflictException : IllegalStateException(
    "Head snapshot Team berubah atau memiliki fork. Buka Pusat Konflik sebelum melanjutkan.",
)

@Singleton
class TeamSnapshotCoordinator @Inject constructor(
    private val database: KronDatabase,
    private val backupManager: BackupManager,
    private val accessGuard: TeamAccessGuard,
    private val keyStore: TeamKeyStore,
    private val drive: TeamDriveRestClient,
    private val cryptor: TeamSnapshotCryptor,
) {
    suspend fun publish(accessToken: String, accountId: Long): RemoteDriveSnapshot {
        check(BuildConfig.TEAM_ACCOUNT_ENABLED) { "Team Account belum aktif pada build ini" }
        accessGuard.require(accountId, TeamCapability.WRITE)
        val dao = database.kronDao()
        val account = requireNotNull(dao.accountById(accountId)) { "Team Account tidak ditemukan" }
        val workspace = requireNotNull(dao.teamWorkspace(accountId)) { "Workspace Team tidak ditemukan" }
        require(account.teamId == workspace.teamId && workspace.status != TeamWorkspaceStatus.CONFLICT) {
            "Workspace Team belum siap untuk sync"
        }
        val remoteWorkspace = drive.workspace(accessToken, workspace.folderId)
        require(remoteWorkspace.capabilities.canRead && remoteWorkspace.capabilities.canWrite && !remoteWorkspace.writersCanShare) {
            "Izin workspace Drive tidak sesuai"
        }
        val before = drive.listSnapshots(accessToken, workspace.folderId, workspace.teamId)
        if (!TeamSnapshotHeadPolicy.matches(before, workspace.headSnapshotId)) {
            markConflict(workspace.accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId)
            throw TeamSnapshotConflictException()
        }

        val deviceId = requireNotNull(dao.syncState()?.deviceId?.takeIf(String::isNotBlank)) {
            "Identitas perangkat Team tidak tersedia"
        }
        val teamKey = requireNotNull(keyStore.acquire(workspace.teamId)) { "Team key tidak tersedia" }
        var payload = ByteArray(0)
        var envelope = ByteArray(0)
        try {
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
            )
            val after = drive.listSnapshots(accessToken, workspace.folderId, workspace.teamId)
            if (!TeamSnapshotHeadPolicy.matches(after, uploaded.manifest.snapshotId)) {
                markConflict(workspace.accountId, workspace.teamId, workspace.generation, workspace.headSnapshotId)
                throw TeamSnapshotConflictException()
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
            return uploaded
        } finally {
            teamKey.fill(0)
            payload.fill(0)
            envelope.fill(0)
        }
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
}

internal object TeamSnapshotHeadPolicy {
    fun matches(snapshots: List<RemoteDriveSnapshot>, expectedHead: String?): Boolean {
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
            expectedHead == null -> heads.isEmpty()
            else -> heads.singleOrNull()?.manifest?.snapshotId == expectedHead
        }
    }
}
