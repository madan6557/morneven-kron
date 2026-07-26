package com.morneven.kron.sync

import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.SyncStateEntity
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class BackupManagerLocalSnapshotSource(
    private val backupManager: BackupManager,
    private val database: KronDatabase,
    private val initialDatasetId: String = UUID.randomUUID().toString(),
    private val initialDeviceId: String = UUID.randomUUID().toString(),
) : LocalSnapshotSource {
    override suspend fun describe(): LocalDatasetSnapshot = withContext(Dispatchers.IO) {
        require(database.kronDao().teamAccountCount() == 0) {
            "Sinkronisasi privat tidak boleh memuat data Team"
        }
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
            hasFinancialData = eventCount > 0,
        )
    }

    override suspend fun exportSnapshotPayload(): ByteArray {
        require(database.kronDao().teamAccountCount() == 0) {
            "Sinkronisasi privat tidak boleh memuat data Team"
        }
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
        require(database.kronDao().teamAccountCount() == 0) {
            "Snapshot privat tidak boleh mengganti database yang memuat data Team"
        }
        require(AesGcmDriveSnapshotCryptor.sha256(payload) == manifest.payloadSha256) {
            "Checksum payload Drive tidak cocok"
        }
        backupManager.applyPortableSnapshotDirectly(
            payload = payload,
            datasetId = manifest.datasetId,
            generation = manifest.generation,
            parentSnapshotId = manifest.parentSnapshotId,
            snapshotId = manifest.snapshotId,
            accountSubject = account.subjectId,
            accountEmail = account.email,
        )
        return LocalApplyOutcome.APPLIED
    }
}
