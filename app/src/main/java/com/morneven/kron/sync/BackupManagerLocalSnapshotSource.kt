package com.morneven.kron.sync

import android.content.Context
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.SyncStateEntity
import com.morneven.kron.security.DatabaseRuntime
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupManagerLocalSnapshotSource(
    private val backupManager: BackupManager,
    private val databaseRuntime: DatabaseRuntime,
    private val context: Context,
    private val initialDatasetId: String = UUID.randomUUID().toString(),
    private val initialDeviceId: String = UUID.randomUUID().toString(),
) : LocalSnapshotSource {
    private val database get() = databaseRuntime.current()

    override suspend fun describe(): LocalDatasetSnapshot = withContext(Dispatchers.IO) {
        val syncState = database.kronDao().syncState() ?: SyncStateEntity(
            datasetId = initialDatasetId,
            deviceId = initialDeviceId,
            lastSyncedGeneration = -1,
        ).also { database.kronDao().upsertSyncState(it) }
        val schemaVersion = database.openHelper.readableDatabase.query("PRAGMA user_version").use { cursor ->
            require(cursor.moveToFirst())
            cursor.getInt(0)
        }
        val eventCount = database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM activity_events",
        ).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }
        LocalDatasetSnapshot(
            datasetId = syncState.datasetId,
            generation = syncState.localGeneration,
            schemaVersion = schemaVersion,
            // The bootstrap account and categories are created on every fresh
            // install. They are not local financial data until a ledger event
            // exists, so they must not force a first-sync conflict.
            hasFinancialData = eventCount > 0,
        )
    }

    override suspend fun exportSnapshotPayload(): ByteArray {
        return backupManager.createPortableSnapshotPayload()
    }

    override suspend fun previewRemotePayload(
        payload: ByteArray,
        manifest: DriveSnapshotManifest,
    ): ConflictPreview = backupManager.previewPortableSnapshotPayload(
        payload = payload,
        localSnapshotId = database.kronDao().syncState()?.lastSnapshotId,
        remoteSnapshotId = manifest.snapshotId,
    )

    override suspend fun applyRemoteAtomically(
        payload: ByteArray,
        manifest: DriveSnapshotManifest,
        account: GoogleAccountIdentity,
    ): LocalApplyOutcome {
        require(AesGcmDriveSnapshotCryptor.sha256(payload) == manifest.payloadSha256) {
            "Checksum payload Drive tidak cocok"
        }
        val hasTeamInfo = backupManager.payloadContainsTeamRecovery(payload)
        DriveSyncSwitchGate.setRemoteTeamInfo(context, hasTeamInfo)
        try {
            backupManager.applyPortableSnapshotPayloadAtomically(
                payload = payload,
                datasetId = manifest.datasetId,
                generation = manifest.generation,
                parentSnapshotId = manifest.parentSnapshotId,
                snapshotId = manifest.snapshotId,
                accountSubject = account.subjectId,
                accountEmail = account.email,
            )
        } catch (error: Throwable) {
            DriveSyncSwitchGate.setRemoteTeamInfo(context, false)
            throw error
        }
        databaseRuntime.markPendingSyncActivation()
        return LocalApplyOutcome.APPLIED
    }
}
