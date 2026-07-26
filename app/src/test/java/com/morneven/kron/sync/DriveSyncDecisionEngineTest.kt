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
    fun concurrentRemoteHeadsAreNeverChosenSilently() {
        val root = remote("root-file", "root", 1)
        val left = remote("left-file", "left", 2, parent = "root")
        val right = remote("right-file", "right", 3, parent = "root")

        val decision = DriveSyncDecisionEngine.decide(
            state(lastSnapshot = "root", syncedGeneration = 3),
            local,
            account,
            listOf(root, left, right),
        )

        assertTrue(decision is SyncDecision.Conflict)
        decision as SyncDecision.Conflict
        assertEquals(SyncConflictReason.REMOTE_FORK_DETECTED, decision.reason)
        assertEquals(setOf("left", "right"), decision.remoteHeads.map { it.manifest.snapshotId }.toSet())
    }

    @Test
    fun cyclicRemoteGraphIsNeverChosenSilently() {
        val cyclic = remote("cycle-file", "cycle", generation = 2, parent = "cycle")

        val decision = DriveSyncDecisionEngine.decide(
            state(lastSnapshot = "cycle", syncedGeneration = 2),
            local,
            account,
            listOf(cyclic),
        )

        assertTrue(decision is SyncDecision.Conflict)
        assertEquals(
            SyncConflictReason.REMOTE_FORK_DETECTED,
            (decision as SyncDecision.Conflict).reason,
        )
    }

    @Test
    fun emptyLocalNeverDownloadsOneOfSeveralForeignRemoteHeads() {
        val root = remote("root-file", "root", 1, dataset = "dataset-b")
        val left = remote("left-file", "left", 2, parent = "root", dataset = "dataset-b")
        val right = remote("right-file", "right", 3, parent = "root", dataset = "dataset-b")

        val decision = DriveSyncDecisionEngine.decide(
            state(accountSubject = null),
            local.copy(hasFinancialData = false),
            account,
            listOf(root, left, right),
        )

        assertTrue(decision is SyncDecision.Conflict)
        assertEquals(
            SyncConflictReason.REMOTE_FORK_DETECTED,
            (decision as SyncDecision.Conflict).reason,
        )
    }

    @Test
    fun multipleForeignDatasetsRemainVisibleAsSeparateCandidates() {
        val first = remote("first-file", "first", 1, dataset = "dataset-b")
        val second = remote("second-file", "second", 2, dataset = "dataset-c")

        val decision = DriveSyncDecisionEngine.decide(
            state(accountSubject = null),
            local.copy(hasFinancialData = false),
            account,
            listOf(first, second),
        )

        assertTrue(decision is SyncDecision.Conflict)
        assertEquals(
            setOf("dataset-b", "dataset-c"),
            (decision as SyncDecision.Conflict).remoteHeads.map { it.manifest.datasetId }.toSet(),
        )
    }

    @Test
    fun retentionKeepsTenSnapshotsAcrossActiveAndRecoveryKinds() {
        val active = (1L..8L).map { generation ->
            remote(
                "file-$generation",
                "snapshot-$generation",
                generation,
                parent = if (generation == 1L) null else "snapshot-${generation - 1}",
            )
        }
        val recovery = (9L..13L).map { generation ->
            remote("recovery-$generation", "recovery-$generation", generation, kind = SnapshotKind.RECOVERY)
        }
        val otherDataset = remote("other", "other", generation = 1, dataset = "dataset-b")

        val deleted = SnapshotRetention.filesToDelete(active + recovery + otherDataset, "dataset-a")
        assertEquals(setOf("file-1", "file-2", "file-3"), deleted.map { it.fileId }.toSet())
    }

    @Test
    fun retentionNeverDeletesEitherForkHeadOrItsParent() {
        val root = remote("root-file", "root", 1)
        val left = remote("left-file", "left", 2, parent = "root")
        val right = remote("right-file", "right", 3, parent = "root")
        val recovery = (4L..20L).map { generation ->
            remote("recovery-$generation", "recovery-$generation", generation, kind = SnapshotKind.RECOVERY)
        }

        val deleted = SnapshotRetention.filesToDelete(recovery + root + left + right, "dataset-a")

        assertTrue(deleted.none { it.fileId in setOf("root-file", "left-file", "right-file") })
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
