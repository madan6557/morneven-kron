package com.morneven.kron.team

import com.morneven.kron.data.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamInvitationCodecTest {
    @Test
    fun roundTripKeepsTargetAndRejectsExpiredCode() {
        val now = 1_000_000L
        val invitation = TeamInvitationCodec.create(
            teamId = "team-1",
            folderId = "folder_1",
            targetEmail = " Member@Example.com ",
            role = TeamRole.EDITOR,
            ownerKeyFingerprint = "a".repeat(64),
            nowEpochMillis = now,
            inviteId = "invite-1",
        )
        val code = TeamInvitationCodec.encode(invitation)
        val decoded = TeamInvitationCodec.decode(code, now + 1)

        assertEquals("team-1", decoded.teamId)
        assertEquals(TeamRole.EDITOR, decoded.role)
        assertTrue(TeamInvitationCodec.emailMatches(decoded, "member@example.com"))
        assertFalse(TeamInvitationCodec.emailMatches(decoded, "other@example.com"))
        assertTrue(runCatching { TeamInvitationCodec.decode(code, decoded.expiresAtEpochMillis) }.isFailure)
        assertEquals("TeamInvitation(redacted)", decoded.toString())
    }

    @Test
    fun stableFileCodeCarriesOnlyTheSelectedSnapshotReference() {
        val invitation = TeamInvitationCodec.create(
            teamId = "team-1",
            folderId = "folder_1",
            targetEmail = "member@example.com",
            role = TeamRole.VIEWER,
            ownerKeyFingerprint = "b".repeat(64),
            nowEpochMillis = 1_000_000L,
            inviteId = "invite-2",
            liveFileId = "team-live-file",
        )
        val decoded = TeamInvitationCodec.decode(TeamInvitationCodec.encode(invitation, byteArrayOf(7, 8, 9)), 1_000_001L)
        try {
            assertEquals("team-live-file", decoded.liveFileId)
            assertTrue(decoded.embeddedEnvelopeCopy()!!.contentEquals(byteArrayOf(7, 8, 9)))
        } finally {
            invitation.clear()
            decoded.clear()
        }
    }
}
