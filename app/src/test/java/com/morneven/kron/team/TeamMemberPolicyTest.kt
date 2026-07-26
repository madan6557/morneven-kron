package com.morneven.kron.team

import com.morneven.kron.data.TeamMemberEntity
import com.morneven.kron.data.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamMemberPolicyTest {
    @Test
    fun driveAclMapsToAnAuthoritativeCache() {
        val members = TeamMemberPolicy.toEntities(
            accountId = 7,
            members = listOf(
                member("owner", "owner@example.com", TeamRole.OWNER),
                member("editor", "EDITOR@example.com", TeamRole.EDITOR),
            ),
            refreshedAt = 9,
        )

        assertEquals(listOf(TeamRole.OWNER, TeamRole.EDITOR), members.map(TeamMemberEntity::role))
        assertEquals("editor@example.com", members.last().email)
        assertEquals(9, members.last().refreshedAt)
    }

    @Test
    fun malformedAclAndOwnerMutationFailClosed() {
        val owner = member("owner", "owner@example.com", TeamRole.OWNER)
        val editor = member("editor", "editor@example.com", TeamRole.EDITOR)
        assertTrue(runCatching { TeamMemberPolicy.toEntities(7, listOf(editor)) }.isFailure)
        assertTrue(runCatching { TeamMemberPolicy.toEntities(7, listOf(owner, owner)) }.isFailure)
        assertTrue(
            runCatching {
                TeamMemberPolicy.requireMutable(TeamMemberPolicy.toEntity(7, owner), TeamRole.VIEWER)
            }.isFailure,
        )
        TeamMemberPolicy.requireMutable(TeamMemberPolicy.toEntity(7, editor), TeamRole.VIEWER)
    }

    private fun member(permissionId: String, email: String, role: String) = TeamDriveMember(
        permissionId = permissionId,
        email = email,
        displayName = null,
        role = role,
    )
}
