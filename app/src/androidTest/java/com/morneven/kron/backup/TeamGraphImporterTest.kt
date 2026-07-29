package com.morneven.kron.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TeamGraphImporterTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KronDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun mergesOneTeamAccountAndPreservesPrivateGraph() {
        val target = createTarget("team-import-target.db")
        val source = createSource("team-import-source.db")

        val accountId = TeamGraphImporter.merge(target, source, metadata())

        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertNotEquals(1L, accountId)
            assertEquals(2, scalar(db, "SELECT COUNT(*) FROM accounts"))
            assertEquals(PRIVATE_MARKER, text(db, "SELECT name FROM accounts WHERE id=1"))
            assertEquals(1, scalar(db, "SELECT isActive FROM accounts WHERE id=$accountId"))
            assertEquals(0, scalar(db, "SELECT isActive FROM accounts WHERE id=1"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM categories WHERE accountId=$accountId AND syncId='team-category'"))
            val allocationId = scalar(db, "SELECT id FROM allocations WHERE syncId='team-allocation'")
            assertNotEquals(1L, allocationId)
            assertEquals(allocationId, scalar(db, "SELECT targetAllocationId FROM activity_events WHERE id='team-event'"))
            assertEquals(allocationId, scalar(db, "SELECT allocationId FROM recurring_rules WHERE id='team-rule'"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM portfolio_allocation_templates WHERE syncId='team-template'"))
            assertEquals(100, scalar(db, "SELECT SUM(amount) FROM cash_journal_lines WHERE accountId=$accountId"))
            assertEquals(
                100,
                scalar(
                    db,
                    "SELECT SUM(amount) FROM budget_journal_lines WHERE accountId=$accountId " +
                        "AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                ),
            )
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM ledger_accounts WHERE id='asset:$accountId:CASH'"))
            assertEquals(2, scalar(db, "SELECT COUNT(*) FROM ledger_lines WHERE eventId='team-event'"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM team_event_proofs WHERE eventId='team-event'"))
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM team_invitation_uses WHERE inviteIdHash='${INVITE_HASH}'"))
            assertEquals(TeamRole.EDITOR, text(db, "SELECT localRole FROM team_workspaces WHERE accountId=$accountId"))
            assertEquals("snapshot-7", text(db, "SELECT headSnapshotId FROM team_workspaces WHERE accountId=$accountId"))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
            assertEquals("ok", text(db, "PRAGMA integrity_check").lowercase())
        }
    }

    @Test
    fun refreshesRemoteMutableDataWithoutTouchingPrivateAccount() {
        val target = createTarget("team-refresh-target.db")
        val source = createSource("team-refresh-source.db")
        val accountId = TeamGraphImporter.merge(target, source, metadata())
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE categories SET name='Remote category',revision=1,updatedAt=2 WHERE syncId='team-category'")
            db.execSQL("UPDATE team_workspaces SET generation=8")
        }

        TeamGraphRefresher.refresh(target, source, "team-1", "folder-1", TeamRole.EDITOR, "snapshot-8", 8)

        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals("Remote category", text(db, "SELECT name FROM categories WHERE accountId=$accountId AND syncId='team-category'"))
            assertEquals(PRIVATE_MARKER, text(db, "SELECT name FROM accounts WHERE id=1"))
            assertEquals(8, scalar(db, "SELECT generation FROM team_workspaces WHERE accountId=$accountId"))
            assertEquals("snapshot-8", text(db, "SELECT headSnapshotId FROM team_workspaces WHERE accountId=$accountId"))
            assertFalse(db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() })
        }
    }

    @Test
    fun preservationImportKeepsPrivateAccountActiveAndAddsNoInvitationUse() {
        val target = createTarget("team-preserve-target.db")
        val source = createSource("team-preserve-source.db")

        val accountId = TeamGraphImporter.merge(
            target,
            source,
            metadata().copy(
                localRole = TeamRole.OWNER,
                inviteIdHash = null,
                activateImported = false,
            ),
        )

        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, scalar(db, "SELECT isActive FROM accounts WHERE id=1"))
            assertEquals(0, scalar(db, "SELECT isActive FROM accounts WHERE id=$accountId"))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM team_invitation_uses WHERE inviteIdHash='${INVITE_HASH}'"))
            assertEquals(TeamRole.OWNER, text(db, "SELECT localRole FROM team_workspaces WHERE accountId=$accountId"))
        }
    }

    @Test
    fun collisionRollsBackWithoutChangingPrivateDatabase() {
        val target = createTarget("team-import-collision-target.db", collidingEvent = true)
        val source = createSource("team-import-collision-source.db")

        assertTrue(runCatching { TeamGraphImporter.merge(target, source, metadata()) }.isFailure)

        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM accounts"))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM team_workspaces"))
            assertEquals(0, scalar(db, "SELECT COUNT(*) FROM team_invitation_uses"))
            assertEquals(PRIVATE_MARKER, text(db, "SELECT name FROM accounts WHERE id=1"))
        }
    }

    @Test
    fun financialInvariantFailureRollsBackWithoutChangingPrivateDatabase() {
        val target = createTarget("team-import-invariant-target.db")
        val source = createSource("team-import-invariant-source.db")
        SQLiteDatabase.openDatabase(source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE budget_journal_lines SET amount=99 WHERE id=1")
        }

        assertTrue(runCatching { TeamGraphImporter.merge(target, source, metadata()) }.isFailure)

        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(1, scalar(db, "SELECT COUNT(*) FROM accounts"))
            assertEquals(PRIVATE_MARKER, text(db, "SELECT name FROM accounts WHERE id=1"))
        }
    }

    private fun createTarget(name: String, collidingEvent: Boolean = false): java.io.File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        helper.createDatabase(name, KronDatabase.SCHEMA_VERSION).apply {
            execSQL(
                "INSERT INTO accounts(id,name,isActive,isArchived,archivedAt,createdAt,sharingMode,teamId,revision,updatedAt,lastWriterId) " +
                    "VALUES(1,?,1,0,NULL,1,'PRIVATE',NULL,0,1,NULL)",
                arrayOf(PRIVATE_MARKER),
            )
            execSQL(
                "INSERT INTO categories(id,name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt,lastWriterId) " +
                    "VALUES(1,?,'EXPENSE',1,'category',0,1,'private-category',0,1,NULL)",
                arrayOf(PRIVATE_MARKER),
            )
            execSQL("INSERT INTO portfolios(id,name,cadence,intervalCount,plannedIncome,rolloverEnabled,fundingPriority,startEpochDay,endMode,endValue,isPaused,isArchived,archivedAt,createdAt,accountId,syncId,revision,updatedAt,lastWriterId) VALUES(1,?,'MONTHLY',1,0,0,1,1,'CONTINUOUS',NULL,0,0,NULL,1,1,'private-portfolio',0,1,NULL)", arrayOf(PRIVATE_MARKER))
            execSQL("INSERT INTO budget_periods(id,portfolioId,startEpochDay,endEpochDay,status,createdAt,syncId,revision,updatedAt,lastWriterId) VALUES(1,1,1,30,'ACTIVE',1,'private-period',0,1,NULL)")
            execSQL("INSERT INTO allocations(id,periodId,categoryId,fundingChannel,plannedAmount,syncId,revision,updatedAt,lastWriterId) VALUES(1,1,1,'CASH',0,'private-allocation',0,1,NULL)")
            if (collidingEvent) {
                execSQL(
                    "INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId,accountId) " +
                        "VALUES('team-event','SYSTEM',?,'','USER',1,1,NULL,NULL,NULL,1)",
                    arrayOf(PRIVATE_MARKER),
                )
            }
            close()
        }
        return context.getDatabasePath(name)
    }

    private fun createSource(name: String): java.io.File {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(name)
        helper.createDatabase(name, KronDatabase.SCHEMA_VERSION).apply {
            execSQL("INSERT INTO accounts(id,name,isActive,isArchived,archivedAt,createdAt,sharingMode,teamId,revision,updatedAt,lastWriterId) VALUES(1,'Team',1,0,NULL,1,'TEAM','team-1',0,1,'owner-device')")
            execSQL("INSERT INTO team_workspaces(accountId,teamId,folderId,localRole,ownerSubjectHash,headSnapshotId,generation,status,canRead,canWrite,canShare,capabilitiesVerifiedAt,archivedAt,updatedAt) VALUES(1,'team-1','folder-1','OWNER','owner-subject',NULL,7,'SYNCED',1,1,1,1,NULL,1)")
            execSQL("INSERT INTO categories(id,name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt,lastWriterId) VALUES(1,'Team category','EXPENSE',1,'category',0,1,'team-category',0,1,'owner-device')")
            execSQL("INSERT INTO portfolios(id,name,cadence,intervalCount,plannedIncome,rolloverEnabled,fundingPriority,startEpochDay,endMode,endValue,isPaused,isArchived,archivedAt,createdAt,accountId,syncId,revision,updatedAt,lastWriterId) VALUES(1,'Team budget','MONTHLY',1,100,0,1,1,'CONTINUOUS',NULL,0,0,NULL,1,1,'team-portfolio',0,1,'owner-device')")
            execSQL("INSERT INTO budget_periods(id,portfolioId,startEpochDay,endEpochDay,status,createdAt,syncId,revision,updatedAt,lastWriterId) VALUES(1,1,1,30,'ACTIVE',1,'team-period',0,1,'owner-device')")
            execSQL("INSERT INTO allocations(id,periodId,categoryId,fundingChannel,plannedAmount,syncId,revision,updatedAt,lastWriterId) VALUES(1,1,1,'CASH',100,'team-allocation',0,1,'owner-device')")
            execSQL("INSERT INTO portfolio_allocation_templates(id,portfolioId,categoryId,plannedAmount,cashPercentage,syncId,revision,updatedAt,lastWriterId) VALUES(1,1,1,100,100,'team-template',0,1,'owner-device')")
            execSQL("INSERT INTO recurring_rules(id,title,direction,amount,accountId,fundingChannel,categoryId,allocationId,cadence,intervalCount,anchorMonth,anchorDay,startEpochDay,nextEpochDay,endEpochDay,remainingOccurrences,isPaused,pausedByArchive,createdAt,syncId,revision,updatedAt,lastWriterId) VALUES('team-rule','Rule','EXPENSE',100,1,'CASH',1,1,'MONTHLY',1,1,1,1,1,NULL,NULL,0,0,1,'team-rule-sync',0,1,'owner-device')")
            execSQL("INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId,accountId) VALUES('team-event','OPENING_BALANCE','Team event','','USER',1,1,NULL,NULL,1,1)")
            execSQL("INSERT INTO cash_journal_lines(id,eventId,accountId,fundingChannel,amount) VALUES(1,'team-event',1,'CASH',100)")
            execSQL("INSERT INTO budget_journal_lines(id,eventId,allocationId,bucket,fundingChannel,amount,accountId) VALUES(1,'team-event',1,NULL,'CASH',100,1)")
            execSQL("INSERT INTO budget_journal_lines(id,eventId,allocationId,bucket,fundingChannel,amount,accountId) VALUES(2,'team-event',NULL,'EQUITY','CASH',-100,1)")
            execSQL("INSERT INTO transaction_splits(id,eventId,categoryId,allocationId,amount) VALUES(1,'team-event',1,1,100)")
            execSQL("INSERT INTO recurring_occurrences(id,ruleId,dueEpochDay,eventId,createdAt) VALUES(1,'team-rule',1,'team-event',1)")
            execSQL("INSERT INTO audit_snapshots(id,eventId,reason,beforeJson,afterJson) VALUES(1,'team-event','Audit','{}','{}')")
            execSQL("INSERT INTO receipts(id,eventId,localPath,storageId,displayName,mimeType,byteSize,sha256,encryptionNonce,encryptionVersion,createdAt,capturedAt,latitude,longitude,origin,evidenceEventId) VALUES(1,'team-event',NULL,'teamreceipt1','Receipt','image/jpeg',1,?,NULL,0,1,NULL,NULL,NULL,'CAMERA','team-event')", arrayOf("b".repeat(64)))
            execSQL("INSERT INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt) VALUES('asset:1:CASH','100000101','Team Cash','ASSET',1,'CASH',NULL,1)")
            execSQL("INSERT INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt) VALUES('equity:opening','3000','Modal awal','EQUITY',NULL,NULL,NULL,1)")
            execSQL("INSERT INTO ledger_lines(id,eventId,ledgerAccountId,side,amount,accountId,fundingChannel,categoryId,correlationId,legacyBackfill) VALUES(1,'team-event','asset:1:CASH','DEBIT',100,1,'CASH',NULL,NULL,0)")
            execSQL("INSERT INTO ledger_lines(id,eventId,ledgerAccountId,side,amount,accountId,fundingChannel,categoryId,correlationId,legacyBackfill) VALUES(2,'team-event','equity:opening','CREDIT',100,NULL,NULL,NULL,NULL,0)")
            execSQL("INSERT INTO evidence_keys(id,alias,algorithm,publicKeyBase64,certificateBase64,fingerprint,securityLevel,createdAt,retiredAt) VALUES('team-key','alias','EC','public','certificate',?,'SOFTWARE',1,NULL)", arrayOf("a".repeat(64)))
            execSQL("INSERT INTO team_event_proofs(eventId,teamId,chainId,sequence,previousChainHash,payloadHash,chainHash,signatureBase64,recordedAtUtc,deviceId,actor,appVersion,keyId,canonicalVersion) VALUES('team-event','team-1','chain',1,'previous','payload','chain-hash','signature',1,'owner-device','owner','1.5.23','team-key',1)")
            close()
        }
        return context.getDatabasePath(name)
    }

    private fun metadata() = TeamImportMetadata(
        teamId = "team-1",
        folderId = "folder-1",
        localRole = TeamRole.EDITOR,
        headSnapshotId = "snapshot-7",
        generation = 7,
        inviteIdHash = INVITE_HASH,
        importedAt = 10,
    )

    private fun scalar(db: SQLiteDatabase, sql: String): Long = db.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst())
        it.getLong(0)
    }

    private fun text(db: SQLiteDatabase, sql: String): String = db.rawQuery(sql, null).use {
        assertTrue(it.moveToFirst())
        it.getString(0)
    }

    private companion object {
        const val PRIVATE_MARKER = "PRIVATE_ACCOUNT_MUST_SURVIVE"
        val INVITE_HASH = "c".repeat(64)
    }
}
