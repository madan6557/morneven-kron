package com.morneven.kron.team

import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.RemoteDriveSnapshot
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSnapshotHeadPolicyTest {
    @Test
    fun acceptsOnlyTheExpectedSingleDagHead() {
        val root = remote("root")
        val head = remote("head", listOf("root"))

        assertTrue(TeamSnapshotHeadPolicy.matches(emptyList(), null))
        assertTrue(TeamSnapshotHeadPolicy.matches(listOf(root, head), "head"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(root, head), "root"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(root, head, remote("fork", listOf("root"))), "head"))
        assertFalse(TeamSnapshotHeadPolicy.matches(listOf(root), null))
    }

    private fun remote(snapshotId: String, parents: List<String> = emptyList()): RemoteDriveSnapshot {
        val manifest = DriveSnapshotManifest(
            protocolVersion = 2,
            datasetId = "team-1",
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
