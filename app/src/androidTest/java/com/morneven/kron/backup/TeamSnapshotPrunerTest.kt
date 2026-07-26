package com.morneven.kron.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.morneven.kron.data.KronDatabase
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TeamSnapshotPrunerTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KronDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun removesOtherAccountsAndDeletedPageContents() {
        val name = "team-snapshot-prune.db"
        createFixture(name, crossAccountCash = false)
        val file = ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name)

        TeamSnapshotPruner.prune(file, TeamSnapshotScope(1, TEAM_ID, 7))
        assertEquals(TeamSnapshotScope(1, TEAM_ID, 7), TeamSnapshotPruner.validateImported(file, TEAM_ID))
        assertTrue(runCatching { TeamSnapshotPruner.validateImported(file, "team-lain") }.isFailure)

        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM accounts"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM activity_events"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM team_event_proofs"))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM journal_seals"))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM team_members"))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM receipts WHERE localPath IS NOT NULL"))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
        }
        assertFalse(file.readText(StandardCharsets.ISO_8859_1).contains(PRIVATE_MARKER))
        assertFalse(File(file.path + "-wal").exists())
        assertFalse(File(file.path + "-shm").exists())
    }

    @Test
    fun rejectsAnEventThatTouchesAnotherAccount() {
        val name = "team-snapshot-cross-account.db"
        createFixture(name, crossAccountCash = true)
        val file = ApplicationProvider.getApplicationContext<Context>().getDatabasePath(name)

        assertTrue(runCatching { TeamSnapshotPruner.prune(file, TeamSnapshotScope(1, TEAM_ID, 7)) }.isFailure)
    }

    private fun createFixture(name: String, crossAccountCash: Boolean) {
        helper.createDatabase(name, KronDatabase.SCHEMA_VERSION).apply {
            execSQL("INSERT INTO accounts(id,name,isActive,isArchived,archivedAt,createdAt,sharingMode,teamId,revision,updatedAt,lastWriterId) VALUES(1,'Team',1,0,NULL,1,'TEAM',?,0,1,NULL)", arrayOf(TEAM_ID))
            execSQL("INSERT INTO accounts(id,name,isActive,isArchived,archivedAt,createdAt,sharingMode,teamId,revision,updatedAt,lastWriterId) VALUES(2,?,0,0,NULL,1,'PRIVATE',NULL,0,1,NULL)", arrayOf(PRIVATE_MARKER))
            execSQL("INSERT INTO team_workspaces(accountId,teamId,folderId,localRole,ownerSubjectHash,headSnapshotId,generation,status,canRead,canWrite,canShare,capabilitiesVerifiedAt,archivedAt,updatedAt) VALUES(1,?,'folder','OWNER','owner',NULL,7,'SYNCED',1,1,1,1,NULL,1)", arrayOf(TEAM_ID))
            execSQL("INSERT INTO categories(id,name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt,lastWriterId) VALUES(1,'Team category','EXPENSE',1,'category',0,1,'category-team',0,1,NULL)")
            execSQL("INSERT INTO categories(id,name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt,lastWriterId) VALUES(2,?,'EXPENSE',1,'category',0,2,'category-private',0,1,NULL)", arrayOf(PRIVATE_MARKER))
            execSQL("INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId,accountId) VALUES('event-team','SYSTEM','Team event','','USER',1,1,NULL,NULL,NULL,1)")
            execSQL("INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId,accountId) VALUES('event-private','SYSTEM',?,'','USER',1,1,NULL,NULL,NULL,2)", arrayOf(PRIVATE_MARKER))
            execSQL("INSERT INTO evidence_keys(id,alias,algorithm,publicKeyBase64,certificateBase64,fingerprint,securityLevel,createdAt,retiredAt) VALUES('key','alias','EC','public','certificate',?,'SOFTWARE',1,NULL)", arrayOf("a".repeat(64)))
            execSQL("INSERT INTO team_event_proofs(eventId,teamId,chainId,sequence,previousChainHash,payloadHash,chainHash,signatureBase64,recordedAtUtc,deviceId,actor,appVersion,keyId,canonicalVersion) VALUES('event-team',?,'chain',1,'previous','payload','chain-hash','signature',1,'device','actor','1.5.23','key',1)", arrayOf(TEAM_ID))
            execSQL("INSERT INTO journal_seals(id,eventId,sequence,previousChainHash,payloadHash,chainHash,signatureBase64,recordedAtUtc,timezoneId,deviceId,actor,appVersion,keyId,legacyBackfill) VALUES(1,'event-team',1,'previous','payload','chain-hash','signature',1,'UTC','device','actor','1.5.23','key',0)")
            execSQL("INSERT INTO receipts(id,eventId,localPath,storageId,displayName,mimeType,byteSize,sha256,encryptionNonce,encryptionVersion,createdAt,capturedAt,latitude,longitude,origin,evidenceEventId) VALUES(1,'event-team',?,'receipt-team','Team receipt','image/jpeg',1,?,NULL,0,1,NULL,NULL,NULL,'CAMERA','event-team')", arrayOf(PRIVATE_MARKER, "b".repeat(64)))
            execSQL("INSERT INTO team_members(permissionId,accountId,email,displayName,role,status,refreshedAt) VALUES('private-member',2,?,NULL,'VIEWER','ACTIVE',1)", arrayOf(PRIVATE_MARKER))
            execSQL("INSERT INTO actor_profiles(id,displayName,updatedAt) VALUES(1,?,1)", arrayOf(PRIVATE_MARKER))
            execSQL("INSERT INTO sync_state(id,datasetId,deviceId,accountSubject,accountEmail,localGeneration,lastSyncedGeneration,parentSnapshotId,lastSnapshotId,conflictRemoteFileId,lastSyncedAt,status,lastError,disabledDueToBilling,updatedAt) VALUES(1,'private-dataset','private-device',?,?,0,0,NULL,NULL,NULL,NULL,'DISCONNECTED',NULL,0,1)", arrayOf(PRIVATE_MARKER, PRIVATE_MARKER))
            if (crossAccountCash) {
                execSQL("INSERT INTO cash_journal_lines(id,eventId,accountId,fundingChannel,amount) VALUES(1,'event-team',2,'CASH',1)")
            }
            close()
        }
    }

    private fun scalar(db: SQLiteDatabase, sql: String): Int = db.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst())
        it.getInt(0)
    }

    companion object {
        private const val TEAM_ID = "team-1"
        private const val PRIVATE_MARKER = "PRIVATE_ACCOUNT_SHOULD_NOT_LEAK_42"
    }
}
