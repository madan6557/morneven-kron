package com.morneven.kron.data

import org.junit.Assert.assertTrue
import org.junit.Test

class TeamAccessPolicyTest {
    @Test
    fun viewerCannotWriteAndEditorCannotManageMembers() {
        val viewer = workspace(TeamRole.VIEWER, canWrite = false, canShare = false)
        val editor = workspace(TeamRole.EDITOR, canWrite = true, canShare = false)
        val owner = workspace(TeamRole.OWNER, canWrite = true, canShare = true)

        assertTrue(runCatching { TeamAccessPolicy.require(viewer, TeamCapability.WRITE) }.isFailure)
        assertTrue(runCatching { TeamAccessPolicy.require(editor, TeamCapability.MANAGE_MEMBERS) }.isFailure)
        assertTrue(runCatching { TeamAccessPolicy.require(editor.copy(canRead = false), TeamCapability.WRITE) }.isFailure)
        TeamAccessPolicy.require(viewer, TeamCapability.READ)
        TeamAccessPolicy.require(editor, TeamCapability.WRITE)
        TeamAccessPolicy.require(owner, TeamCapability.MANAGE_MEMBERS)
    }

    private fun workspace(role: String, canWrite: Boolean, canShare: Boolean) = TeamWorkspaceEntity(
        accountId = 1,
        teamId = "team",
        folderId = "folder",
        localRole = role,
        ownerSubjectHash = "hash",
        canRead = true,
        canWrite = canWrite,
        canShare = canShare,
        capabilitiesVerifiedAt = 1,
    )
}
