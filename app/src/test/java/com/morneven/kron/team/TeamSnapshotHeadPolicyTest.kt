package com.morneven.kron.team

import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.RemoteDriveSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSnapshotHeadPolicyTest {
    @Test
    fun syncPolicyUsesTrackedGenerationInsteadOfUnstablePackageBytes() {
        fun decide(
            canWrite: Boolean = true,
            localHead: String? = "base",
            localGeneration: Long = 4,
            remoteHead: String = "base",
            remoteGeneration: Long = 4,
            baseGeneration: Long? = 4,
            descends: Boolean = false,
        ) = TeamSyncPolicy.decide(
            canWrite, localHead, localGeneration, remoteHead, remoteGeneration, baseGeneration, descends,
        )

        assertEquals(TeamSyncDecision.NO_CHANGES, decide())
        assertEquals(TeamSyncDecision.UPLOAD, decide(localGeneration = 5))
        assertEquals(TeamSyncDecision.CONFLICT, decide(canWrite = false, localGeneration = 5))
        assertEquals(TeamSyncDecision.PULL, decide(remoteHead = "child", remoteGeneration = 6, descends = true))
        assertEquals(
            TeamSyncDecision.PULL,
            TeamSyncPolicy.decide(true, "recovery-head", 9, "remote-head", 10, null, false, recoveringFromPrivateCopy = true),
        )
        assertEquals(TeamSyncDecision.CONFLICT, decide(remoteHead = "child", remoteGeneration = 6, localGeneration = 5, descends = true))
        assertEquals(TeamSyncDecision.CONFLICT, decide(remoteHead = "child", remoteGeneration = 6, baseGeneration = null))
    }

    @Test
    fun acceptsOnlyTheExpectedSingleDagHead() {
        val root = remote("root")
        val head = remote("head", listOf("root"))

        assertTrue(TeamSnapshotHeadPolicy.matches(emptyList(), null))
        assertTrue(TeamSnapshotHeadPolicy.matches(emptyList(), ""))
        assertTrue(TeamSnapshotHeadPolicy.matches(listOf(root, head), "head"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(root, head), "root"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(root, head, remote("fork", listOf("root"))), "head"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(head, remote("foreign", dataset = "team-2")), "head"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(remote("cycle", listOf("cycle"))), "cycle"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(root), null))
    }

    private fun remote(
        snapshotId: String,
        parents: List<String> = emptyList(),
        dataset: String = "team-1",
    ): RemoteDriveSnapshot {
        val manifest = DriveSnapshotManifest(
            protocolVersion = 2,
            datasetId = dataset,
            snapshotId = snapshotId,
            parentSnapshotId = parents.firstOrNull(),
            parentSnapshotIds = parents,
            generation = 1,
            sourceDeviceId = "device-1",
            schemaVersion = 15,
            minimumAppVersionCode = 1,
            createdAtEpochMillis = 1,
            payloadSha256 = "00".repeat(32),
        )
        return RemoteDriveSnapshot(snapshotId, "$snapshotId.kronteam", manifest, Instant.EPOCH, 1)
    }
}
