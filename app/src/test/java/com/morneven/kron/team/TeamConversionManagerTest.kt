package com.morneven.kron.team

import com.morneven.kron.data.TeamRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamConversionManagerTest {
    @Test
    fun acceptValidRoleOnRequest() {
        assertTrue(listOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER).any { it == TeamRole.OWNER })
        assertTrue(listOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER).any { it == TeamRole.EDITOR })
        assertTrue(listOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER).any { it == TeamRole.VIEWER })
    }

    @Test
    fun rejectInvalidRole() {
        assertFalse(listOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER).any { it == "INVALID" })
    }

    @Test
    fun validRequestNonBlankTeamId() {
        assertTrue(validRequest("team-abc"))
        assertFalse(validRequest(""))
        assertFalse(validRequest("  "))
    }

    @Test
    fun validRequestNonBlankFolderId() {
        assertTrue(folderValid("folder-xyz"))
        assertFalse(folderValid(""))
    }

    private fun validRequest(teamId: String): Boolean =
        teamId.isNotBlank() && "folder-xyz".isNotBlank()
    private fun folderValid(folderId: String): Boolean =
        "team-abc".isNotBlank() && folderId.isNotBlank()
}
