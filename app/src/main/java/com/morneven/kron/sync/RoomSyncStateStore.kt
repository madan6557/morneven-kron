package com.morneven.kron.sync

import androidx.room.withTransaction
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.SyncStateEntity
import com.morneven.kron.security.DatabaseRuntime
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RoomSyncStateStore(
    private val databaseRuntime: DatabaseRuntime,
    private val initialDatasetId: String = UUID.randomUUID().toString(),
    private val initialDeviceId: String = UUID.randomUUID().toString(),
) : SyncStateStore {
    private val mutex = Mutex()
    private val database get() = databaseRuntime.current()

    override suspend fun read(): SyncState = mutex.withLock {
        database.withTransaction { readOrCreate().toModel() }
    }

    override suspend fun update(transform: (SyncState) -> SyncState): SyncState = mutex.withLock {
        val updated = database.withTransaction {
            val current = transform(readOrCreate().toModel())
            database.kronDao().upsertSyncState(current.toEntity())
            current
        }
        database.openHelper.writableDatabase.execSQL(
            "UPDATE sync_state SET updatedAt = ? WHERE id = 1",
            arrayOf(System.currentTimeMillis()),
        )
        val entity = updated.toEntity()
        SyncStateBridge.emit(entity)
        database.invalidationTracker.refreshAsync()
        updated
    }

    private suspend fun readOrCreate(): SyncStateEntity = database.kronDao().syncState() ?: SyncStateEntity(
        datasetId = initialDatasetId,
        deviceId = initialDeviceId,
        lastSyncedGeneration = -1,
    ).also { database.kronDao().upsertSyncState(it) }

    private fun SyncStateEntity.toModel() = SyncState(
        datasetId = datasetId,
        deviceId = deviceId,
        localGeneration = localGeneration,
        lastSyncedGeneration = lastSyncedGeneration,
        parentSnapshotId = parentSnapshotId,
        lastSnapshotId = lastSnapshotId,
        lastSyncedAtEpochMillis = lastSyncedAt,
        status = runCatching { SyncStatus.valueOf(status) }.getOrDefault(SyncStatus.ERROR),
        lastError = lastError,
        accountSubject = accountSubject,
        accountEmail = accountEmail,
        disabledDueToBilling = disabledDueToBilling,
        conflictRemoteFileId = conflictRemoteFileId,
    )

    private fun SyncState.toEntity() = SyncStateEntity(
        datasetId = datasetId,
        deviceId = deviceId,
        accountSubject = accountSubject,
        accountEmail = accountEmail,
        localGeneration = localGeneration,
        lastSyncedGeneration = lastSyncedGeneration,
        parentSnapshotId = parentSnapshotId,
        lastSnapshotId = lastSnapshotId,
        conflictRemoteFileId = conflictRemoteFileId,
        lastSyncedAt = lastSyncedAtEpochMillis,
        status = status.name,
        lastError = lastError,
        disabledDueToBilling = disabledDueToBilling,
        updatedAt = System.currentTimeMillis(),
    )
}
