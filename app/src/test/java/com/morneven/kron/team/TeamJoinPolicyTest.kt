package com.morneven.kron.team

import com.morneven.kron.data.TeamRole
import com.morneven.kron.sync.DriveSnapshotManifest
import com.morneven.kron.sync.RemoteDriveSnapshot
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamJoinPolicyTest {
    @Test
    fun roleMustMatchCurrentDriveCapabilities() {
        val editor = invitation(TeamRole.EDITOR)
        val viewer = invitation(TeamRole.VIEWER)
        try {
            TeamJoinPolicy.requireCapabilities(editor, workspace(canWrite = true))
            TeamJoinPolicy.requireCapabilities(viewer, workspace(canWrite = false))
            assertTrue(runCatching { TeamJoinPolicy.requireCapabilities(editor, workspace(canWrite = false)) }.isFailure)
            assertTrue(runCatching { TeamJoinPolicy.requireCapabilities(viewer, workspace(canWrite = true)) }.isFailure)
            assertTrue(
                runCatching {
                    TeamJoinPolicy.requireCapabilities(editor, workspace(canWrite = true, canShare = true))
                }.isFailure,
            )
        } finally {
            editor.clear()
            viewer.clear()
        }
    }

    @Test
    fun invitationMetadataMustMatchExactlyAndBeUnique() {
        val invitation = invitation(TeamRole.EDITOR)
        try {
            val valid = invitationFile(invitation)
            assertEquals(valid, TeamJoinPolicy.invitationFile(listOf(valid), invitation))
            assertTrue(runCatching { TeamJoinPolicy.invitationFile(listOf(valid, valid.copy(fileId = "duplicate")), invitation) }.isFailure)
            assertTrue(
                runCatching {
                    TeamJoinPolicy.invitationFile(
                        listOf(valid.copy(appProperties = valid.appProperties + ("role" to TeamRole.VIEWER))),
                        invitation,
                    )
                }.isFailure,
            )
        } finally {
            invitation.clear()
        }
    }

    @Test
    fun snapshotMustHaveOneValidDagHead() {
        val root = snapshot("root")
        val head = snapshot("head", listOf("root"))

        assertEquals(head, TeamJoinPolicy.snapshotHead(listOf(root, head), TEAM_ID))
        assertTrue(
            runCatching {
                TeamJoinPolicy.snapshotHead(listOf(root, head, snapshot("fork", listOf("root"))), TEAM_ID)
            }.isFailure,
        )
        assertTrue(runCatching { TeamJoinPolicy.snapshotHead(listOf(snapshot("cycle", listOf("cycle"))), TEAM_ID) }.isFailure)
    }

    @Test
    fun preflightResultNeverPrintsIdentifiers() {
        val result = TeamJoinPreflightResult(TEAM_ID, FOLDER_ID, TeamRole.EDITOR, "head", 1)
        assertEquals("TeamJoinPreflightResult(redacted)", result.toString())
    }

    private fun invitation(role: String) = TeamInvitationCodec.create(
        teamId = TEAM_ID,
        folderId = FOLDER_ID,
        targetEmail = "member@example.com",
        role = role,
        ownerKeyFingerprint = "ab".repeat(32),
        nowEpochMillis = 1,
        inviteId = "invite-1",
    )

    private fun workspace(canWrite: Boolean, canShare: Boolean = false) = TeamDriveWorkspace(
        folderId = FOLDER_ID,
        capabilities = TeamDriveCapabilities(canRead = true, canWrite = canWrite, canShare = canShare),
        writersCanShare = false,
    )

    private fun invitationFile(invitation: TeamInvitation): TeamDriveFile {
        val inviteHash = TeamInvitationCodec.sha256(invitation.inviteId.toByteArray())
        return TeamDriveFile(
            fileId = "invitation-file",
            name = "invitation.kronteam",
            sizeBytes = 100,
            appProperties = mapOf(
                "product" to "KRON",
                "teamId" to invitation.teamId,
                "kind" to "invitation",
                "invite" to inviteHash,
                "target" to invitation.targetEmailHash,
                "role" to invitation.role,
                "expires" to invitation.expiresAtEpochMillis.toString(),
                "owner" to invitation.ownerKeyFingerprint,
            ),
        )
    }

    private fun snapshot(snapshotId: String, parents: List<String> = emptyList()): RemoteDriveSnapshot {
        val manifest = DriveSnapshotManifest(
            protocolVersion = 2,
            datasetId = TEAM_ID,
            snapshotId = snapshotId,
            parentSnapshotId = parents.firstOrNull(),
            parentSnapshotIds = parents,
            generation = 1,
            sourceDeviceId = "device",
            schemaVersion = 15,
            minimumAppVersionCode = 1,
            createdAtEpochMillis = 1,
            payloadSha256 = "00".repeat(32),
        )
        return RemoteDriveSnapshot(snapshotId, "$snapshotId.kronteam", manifest, Instant.EPOCH, 100)
    }

    private companion object {
        const val TEAM_ID = "team-1"
        const val FOLDER_ID = "folder-1"
    }
}
