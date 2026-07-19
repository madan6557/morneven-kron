package com.morneven.kron.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSyncDecisionEngineTest {
    private val account = GoogleAccountIdentity("subject-a", "owner@example.com")
    private val local = LocalDatasetSnapshot("dataset-a", generation = 3, schemaVersion = 6, hasFinancialData = true)

    @Test
    fun firstLocalDatasetUploadsWhenDriveIsEmpty() {
        val decision = DriveSyncDecisionEngine.decide(state(), local, account, emptyList())
        assertEquals(SyncDecision.Upload(null), decision)
    }

    @Test
    fun cleanLocalDownloadsNewRemoteButTwoChangesConflict() {
        val last = remote("file-3", "snapshot-3", generation = 3)
        val newer = remote("file-4", "snapshot-4", generation = 4, parent = "snapshot-3")
        val cleanState = state(lastSnapshot = "snapshot-3", syncedGeneration = 3)

        assertEquals(SyncDecision.Download(newer), DriveSyncDecisionEngine.decide(cleanState, local, account, listOf(last, newer)))

        val changedLocal = local.copy(generation = 4)
        val conflict = DriveSyncDecisionEngine.decide(cleanState, changedLocal, account, listOf(last, newer))
        assertTrue(conflict is SyncDecision.Conflict)
        assertEquals(SyncConflictReason.BOTH_SIDES_CHANGED, (conflict as SyncDecision.Conflict).reason)
    }

    @Test
    fun switchingAccountWithLocalDataNeverUploadsSilently() {
        val switchedState = state(accountSubject = "subject-lama")
        val decision = DriveSyncDecisionEngine.decide(switchedState, local, account, emptyList())
        assertTrue(decision is SyncDecision.Conflict)
        assertEquals(SyncConflictReason.ACCOUNT_CHANGED, (decision as SyncDecision.Conflict).reason)
    }

    @Test
    fun retentionKeepsTenSnapshotsAcrossActiveAndRecoveryKinds() {
        val active = (1L..8L).map { generation ->
            remote("file-$generation", "snapshot-$generation", generation)
        }
        val recovery = (9L..13L).map { generation ->
            remote("recovery-$generation", "recovery-$generation", generation, kind = SnapshotKind.RECOVERY)
        }
        val otherDataset = remote("other", "other", generation = 1, dataset = "dataset-b")

        val deleted = SnapshotRetention.filesToDelete(active + recovery + otherDataset, "dataset-a")
        assertEquals(setOf("file-1", "file-2", "file-3"), deleted.map { it.fileId }.toSet())
    }

    @Test
    fun retentionPreservesActiveHeadParentAndConflictSnapshot() {
        val activeHead = remote("active-head", "active-head", generation = 50, parent = "active-parent")
            .copy(createdAt = Instant.ofEpochMilli(1))
        val activeParent = remote("active-parent-file", "active-parent", generation = 49)
            .copy(createdAt = Instant.ofEpochMilli(2))
        val conflict = remote("conflict-file", "conflict", generation = 1, kind = SnapshotKind.RECOVERY)
            .copy(createdAt = Instant.ofEpochMilli(3))
        val newerRecovery = (10L..20L).map { generation ->
            remote("recovery-$generation", "recovery-$generation", generation, kind = SnapshotKind.RECOVERY)
        }

        val deleted = SnapshotRetention.filesToDelete(
            snapshots = newerRecovery + activeHead + activeParent + conflict,
            datasetId = "dataset-a",
            protectedFileIds = setOf(conflict.fileId),
        )

        assertTrue(deleted.none { it.fileId in setOf(activeHead.fileId, activeParent.fileId, conflict.fileId) })
        assertEquals(4, deleted.size)
    }

    private fun state(
        lastSnapshot: String? = null,
        syncedGeneration: Long = -1,
        accountSubject: String? = account.subjectId,
    ) = SyncState(
        datasetId = "dataset-a",
        deviceId = "device-a",
        localGeneration = local.generation,
        lastSyncedGeneration = syncedGeneration,
        lastSnapshotId = lastSnapshot,
        accountSubject = accountSubject,
    )

    private fun remote(
        fileId: String,
        snapshotId: String,
        generation: Long,
        parent: String? = null,
        kind: SnapshotKind = SnapshotKind.ACTIVE,
        dataset: String = "dataset-a",
    ): RemoteDriveSnapshot {
        val payload = byteArrayOf(generation.toByte())
        return RemoteDriveSnapshot(
            fileId = fileId,
            name = "$fileId.bin",
            manifest = DriveSnapshotManifest(
                datasetId = dataset,
                snapshotId = snapshotId,
                parentSnapshotId = parent,
                generation = generation,
                sourceDeviceId = "remote-device",
                schemaVersion = 6,
                minimumAppVersionCode = 22,
                createdAtEpochMillis = generation,
                payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
                kind = kind,
            ),
            createdAt = Instant.ofEpochMilli(generation),
            sizeBytes = 100,
        )
    }
}
