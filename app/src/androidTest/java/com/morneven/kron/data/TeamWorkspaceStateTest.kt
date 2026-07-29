package com.morneven.kron.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TeamWorkspaceStateTest {
    @Test
    fun teamChangesDoNotAdvancePrivateDriveGeneration() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "team-private-generation.db"
        context.deleteDatabase(name)
        val database = KronDatabase.openPlaintextValidationDatabase(context, name)
        try {
            database.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO accounts(id,name,isActive,isArchived,createdAt,sharingMode,teamId,revision,updatedAt) VALUES(1,'Team',1,0,1,'TEAM','team-1',0,1)")
                execSQL("INSERT INTO accounts(id,name,isActive,isArchived,createdAt,sharingMode,teamId,revision,updatedAt) VALUES(2,'Private',0,0,1,'PRIVATE',NULL,0,1)")
                execSQL("INSERT INTO sync_state(id,datasetId,deviceId,localGeneration,lastSyncedGeneration,status,disabledDueToBilling,updatedAt) VALUES(1,'private','device',0,0,'IDLE',0,1)")
                execSQL("INSERT INTO categories(name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt) VALUES('Team','EXPENSE',1,'x',0,1,'team-category',0,1)")
                assertEquals(0, query("SELECT localGeneration FROM sync_state").use { it.moveToFirst(); it.getLong(0) })
                execSQL("INSERT INTO categories(name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt) VALUES('Private','EXPENSE',1,'x',0,2,'private-category',0,1)")
                assertEquals(1, query("SELECT localGeneration FROM sync_state").use { it.moveToFirst(); it.getLong(0) })
            }
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun deletingCollaboratorCacheNeverDeletesOwner() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "team-member-cache.db"
        context.deleteDatabase(name)
        val database = KronDatabase.openPlaintextValidationDatabase(context, name)
        try {
            database.openHelper.writableDatabase.execSQL(
                "INSERT INTO accounts(id,name,isActive,isArchived,createdAt,sharingMode,teamId,revision,updatedAt) " +
                    "VALUES(1,'Team',1,0,1,'TEAM','team-1',0,1)",
            )
            val dao = database.kronDao()
            dao.upsertTeamMembers(
                listOf(
                    TeamMemberEntity("owner", 1, "owner@example.com", role = TeamRole.OWNER, status = "ACTIVE"),
                    TeamMemberEntity("member", 1, "member@example.com", role = TeamRole.EDITOR, status = "ACTIVE"),
                ),
            )

            dao.deleteTeamMember(1, "member")

            assertEquals(listOf("owner"), dao.teamMembers(1).map(TeamMemberEntity::permissionId))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun invitationReplayMarkerIsIdempotent() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "team-invitation-replay.db"
        context.deleteDatabase(name)
        val database = KronDatabase.openPlaintextValidationDatabase(context, name)
        try {
            val dao = database.kronDao()
            val marker = TeamInvitationUseEntity("invite-hash", "team-1", 1)

            assertFalse(dao.teamInvitationWasUsed(marker.inviteIdHash))
            assertTrue(dao.insertTeamInvitationUse(marker) != -1L)
            assertTrue(dao.teamInvitationWasUsed(marker.inviteIdHash))
            assertEquals(-1L, dao.insertTeamInvitationUse(marker))
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun publishedHeadUsesGenerationAndParentCompareAndSet() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "team-workspace-state.db"
        context.deleteDatabase(name)
        val database = KronDatabase.openPlaintextValidationDatabase(context, name)
        try {
            database.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO accounts(id,name,isActive,isArchived,createdAt,sharingMode,teamId,revision,updatedAt) VALUES(1,'Team',1,0,1,'TEAM','team-1',0,1)")
                execSQL("INSERT INTO team_workspaces(accountId,teamId,folderId,localRole,ownerSubjectHash,generation,status,canRead,canWrite,canShare,capabilitiesVerifiedAt,updatedAt) VALUES(1,'team-1','folder-1','OWNER','owner',0,'LOCAL_ONLY',1,1,1,1,1)")
            }
            val dao = database.kronDao()

            assertEquals(
                0,
                dao.markTeamSnapshotPublished(1, "team-1", 1, null, "snapshot-1", TeamWorkspaceStatus.SYNCED, 2),
            )
            assertNull(dao.teamWorkspace(1)?.headSnapshotId)
            assertEquals(
                1,
                dao.markTeamSnapshotPublished(1, "team-1", 0, null, "snapshot-1", TeamWorkspaceStatus.SYNCED, 2),
            )
            assertEquals("snapshot-1", dao.teamWorkspace(1)?.headSnapshotId)
            assertEquals(
                0,
                dao.markTeamSnapshotStatus(1, "team-1", 0, null, TeamWorkspaceStatus.CONFLICT, 3),
            )
            assertEquals(TeamWorkspaceStatus.SYNCED, dao.teamWorkspace(1)?.status)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }

    @Test
    fun teamHeadCanAdvanceWhilePrivateSyncRuns() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "team-workspace-private-sync.db"
        context.deleteDatabase(name)
        val database = KronDatabase.openPlaintextValidationDatabase(context, name)
        try {
            database.openHelper.writableDatabase.apply {
                execSQL("INSERT INTO accounts(id,name,isActive,isArchived,createdAt,sharingMode,teamId,revision,updatedAt) VALUES(1,'Team',1,0,1,'TEAM','team-1',0,1)")
                execSQL("INSERT INTO team_workspaces(accountId,teamId,folderId,localRole,ownerSubjectHash,generation,status,canRead,canWrite,canShare,capabilitiesVerifiedAt,updatedAt) VALUES(1,'team-1','folder-1','OWNER','owner',0,'LOCAL_ONLY',1,1,1,1,1)")
                execSQL("INSERT INTO sync_state(id,datasetId,deviceId,localGeneration,lastSyncedGeneration,status,disabledDueToBilling,updatedAt) VALUES(1,'private','device',0,0,'SYNCING',0,1)")
            }
            val dao = database.kronDao()

            assertEquals(1, dao.markTeamSnapshotPublished(1, "team-1", 0, null, "snapshot-1", TeamWorkspaceStatus.SYNCED, 2))
            assertTrue(runCatching { database.openHelper.writableDatabase.execSQL("UPDATE accounts SET name='blocked' WHERE id=1") }.isFailure)
        } finally {
            database.close()
            context.deleteDatabase(name)
        }
    }
}
