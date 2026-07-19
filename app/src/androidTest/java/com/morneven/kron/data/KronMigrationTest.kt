package com.morneven.kron.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.testing.MigrationTestHelper
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KronMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        KronDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test
    fun migrationAddsIntervalsWithoutDroppingExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE portfolios (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, cadence TEXT NOT NULL, plannedIncome INTEGER NOT NULL, rolloverEnabled INTEGER NOT NULL, fundingPriority INTEGER NOT NULL, startEpochDay INTEGER NOT NULL, endMode TEXT NOT NULL, endValue INTEGER, isPaused INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE recurring_rules (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, direction TEXT NOT NULL, amount INTEGER NOT NULL, accountId INTEGER NOT NULL, categoryId INTEGER, allocationId INTEGER, cadence TEXT NOT NULL, anchorMonth INTEGER NOT NULL, anchorDay INTEGER NOT NULL, startEpochDay INTEGER NOT NULL, nextEpochDay INTEGER NOT NULL, endEpochDay INTEGER, remainingOccurrences INTEGER, isPaused INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO portfolios(id,name,cadence,plannedIncome,rolloverEnabled,fundingPriority,startEpochDay,endMode,isPaused,createdAt) VALUES(1,'Lama','MONTHLY',100,0,100,1,'CONTINUOUS',0,1)")
                        db.execSQL("INSERT INTO recurring_rules(id,title,direction,amount,accountId,cadence,anchorMonth,anchorDay,startEpochDay,nextEpochDay,isPaused,createdAt) VALUES('rule-1','Rutin','INCOME',10,1,'MONTHLY',1,1,1,1,0,1)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val database = helper.writableDatabase
        KronDatabase.MIGRATION_1_2.migrate(database)
        database.query("SELECT name, intervalCount FROM portfolios WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Lama", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        database.query("SELECT title, intervalCount FROM recurring_rules WHERE id='rule-1'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Rutin", cursor.getString(0))
            assertEquals(1, cursor.getInt(1))
        }
        helper.close()
    }

    @Test
    fun migrationFourToFiveAddsRecoverableArchiveStateWithoutDroppingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(4) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, isActive INTEGER NOT NULL, isArchived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE portfolios (id INTEGER PRIMARY KEY NOT NULL, name TEXT NOT NULL, cadence TEXT NOT NULL, intervalCount INTEGER NOT NULL, plannedIncome INTEGER NOT NULL, rolloverEnabled INTEGER NOT NULL, fundingPriority INTEGER NOT NULL, startEpochDay INTEGER NOT NULL, endMode TEXT NOT NULL, endValue INTEGER, isPaused INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE recurring_rules (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, direction TEXT NOT NULL, amount INTEGER NOT NULL, accountId INTEGER NOT NULL, fundingChannel TEXT NOT NULL, categoryId INTEGER, allocationId INTEGER, cadence TEXT NOT NULL, intervalCount INTEGER NOT NULL, anchorMonth INTEGER NOT NULL, anchorDay INTEGER NOT NULL, startEpochDay INTEGER NOT NULL, nextEpochDay INTEGER NOT NULL, endEpochDay INTEGER, remainingOccurrences INTEGER, isPaused INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("INSERT INTO accounts VALUES(1,'Utama',1,0,10)")
                        db.execSQL("INSERT INTO portfolios VALUES(1,'RAB Lama','MONTHLY',1,0,0,100,1,'CONTINUOUS',NULL,0,10)")
                        db.execSQL("INSERT INTO recurring_rules VALUES('rule-4','Rutin','EXPENSE',100,1,'CASH',NULL,NULL,'MONTHLY',1,1,1,1,1,NULL,NULL,0,10)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val database = helper.writableDatabase
        KronDatabase.MIGRATION_4_5.migrate(database)
        database.query("SELECT name, archivedAt FROM accounts WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Utama", cursor.getString(0))
            assertTrue(cursor.isNull(1))
        }
        database.query("SELECT name, isArchived, archivedAt FROM portfolios WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("RAB Lama", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
            assertTrue(cursor.isNull(2))
        }
        database.query("SELECT title, pausedByArchive FROM recurring_rules WHERE id='rule-4'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("Rutin", cursor.getString(0))
            assertEquals(0, cursor.getInt(1))
        }
        helper.close()
    }

    @Test
    fun migrationThreeToFourPreservesAccountsJournalAndRuleChannels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                    override fun onConfigure(db: SupportSQLiteDatabase) {
                        db.setForeignKeyConstraintsEnabled(true)
                    }

                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, type TEXT NOT NULL, fundingChannel TEXT NOT NULL, isArchived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, direction TEXT NOT NULL, color INTEGER NOT NULL, icon TEXT NOT NULL, isArchived INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE allocations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, periodId INTEGER NOT NULL, categoryId INTEGER NOT NULL, fundingChannel TEXT NOT NULL, plannedAmount INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE activity_events (id TEXT NOT NULL PRIMARY KEY, type TEXT NOT NULL, title TEXT NOT NULL, note TEXT NOT NULL, source TEXT NOT NULL, effectiveEpochDay INTEGER NOT NULL, createdAt INTEGER NOT NULL, relatedEventId TEXT, reversedByEventId TEXT, targetAllocationId INTEGER)")
                        db.execSQL("CREATE TABLE cash_journal_lines (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId TEXT NOT NULL, accountId INTEGER NOT NULL, amount INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES activity_events(id) ON DELETE RESTRICT, FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT)")
                        db.execSQL("CREATE INDEX index_cash_journal_lines_eventId ON cash_journal_lines(eventId)")
                        db.execSQL("CREATE INDEX index_cash_journal_lines_accountId ON cash_journal_lines(accountId)")
                        db.execSQL("CREATE TABLE recurring_rules (id TEXT NOT NULL PRIMARY KEY, title TEXT NOT NULL, direction TEXT NOT NULL, amount INTEGER NOT NULL, accountId INTEGER NOT NULL, categoryId INTEGER, allocationId INTEGER, cadence TEXT NOT NULL, intervalCount INTEGER NOT NULL, anchorMonth INTEGER NOT NULL, anchorDay INTEGER NOT NULL, startEpochDay INTEGER NOT NULL, nextEpochDay INTEGER NOT NULL, endEpochDay INTEGER, remainingOccurrences INTEGER, isPaused INTEGER NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(accountId) REFERENCES accounts(id) ON DELETE RESTRICT, FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE RESTRICT, FOREIGN KEY(allocationId) REFERENCES allocations(id) ON DELETE RESTRICT)")
                        db.execSQL("CREATE INDEX index_recurring_rules_accountId ON recurring_rules(accountId)")
                        db.execSQL("CREATE INDEX index_recurring_rules_categoryId ON recurring_rules(categoryId)")
                        db.execSQL("CREATE INDEX index_recurring_rules_allocationId ON recurring_rules(allocationId)")
                        db.execSQL("CREATE TABLE recurring_occurrences (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, ruleId TEXT NOT NULL, dueEpochDay INTEGER NOT NULL, eventId TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(ruleId) REFERENCES recurring_rules(id) ON DELETE RESTRICT)")
                        db.execSQL("INSERT INTO accounts VALUES(1,'Kas Lama','CASH','CASH',0,10)")
                        db.execSQL("INSERT INTO accounts VALUES(2,'Digital Lama','E_WALLET','EBUDGET',0,11)")
                        db.execSQL("INSERT INTO activity_events VALUES('event-3','INCOME','Masuk','','MANUAL',1,10,NULL,NULL,NULL)")
                        db.execSQL("INSERT INTO cash_journal_lines VALUES(1,'event-3',2,100)")
                        db.execSQL("INSERT INTO recurring_rules VALUES('rule-3','Rutin','INCOME',100,2,NULL,NULL,'MONTHLY',1,1,1,1,1,NULL,NULL,0,10)")
                        db.execSQL("INSERT INTO recurring_occurrences VALUES(1,'rule-3',1,'event-3',10)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val database = helper.writableDatabase
        database.beginTransaction()
        try {
            KronDatabase.MIGRATION_3_4.migrate(database)
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        database.query("SELECT id, isActive FROM accounts ORDER BY id").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(1))
            assertTrue(cursor.moveToNext())
            assertEquals(0, cursor.getInt(1))
        }
        database.query("SELECT fundingChannel, amount FROM cash_journal_lines WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EBUDGET", cursor.getString(0))
            assertEquals(100L, cursor.getLong(1))
        }
        database.query("SELECT fundingChannel FROM recurring_rules WHERE id='rule-3'").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("EBUDGET", cursor.getString(0))
        }
        database.query("PRAGMA foreign_key_check").use { cursor -> assertTrue(!cursor.moveToFirst()) }
        helper.close()
    }

    @Test
    fun migrationFiveToSixAddsSyncAndPortableReceiptMetadata() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null)
                .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE portfolios (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE budget_periods (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE allocations (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE portfolio_allocation_templates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                        db.execSQL("CREATE TABLE activity_events (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE recurring_rules (id TEXT NOT NULL PRIMARY KEY)")
                        db.execSQL("CREATE TABLE receipts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, eventId TEXT NOT NULL, localPath TEXT NOT NULL, mimeType TEXT NOT NULL, createdAt INTEGER NOT NULL, FOREIGN KEY(eventId) REFERENCES activity_events(id) ON DELETE RESTRICT)")
                        db.execSQL("CREATE INDEX index_receipts_eventId ON receipts(eventId)")
                        db.execSQL("INSERT INTO activity_events VALUES('event-5')")
                        db.execSQL("INSERT INTO receipts VALUES(1,'event-5','/old/proof.jpg','image/jpeg',10)")
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        val database = helper.writableDatabase
        KronDatabase.MIGRATION_5_6.migrate(database)
        database.query("SELECT eventId, storageId, displayName, byteSize, sha256, encryptionVersion FROM receipts WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("event-5", cursor.getString(0))
            assertEquals("legacy_0000000000000000000000001", cursor.getString(1))
            assertEquals("Bukti-1", cursor.getString(2))
            assertEquals(0L, cursor.getLong(3))
            assertEquals("", cursor.getString(4))
            assertEquals(0, cursor.getInt(5))
        }
        database.execSQL("INSERT INTO sync_state VALUES(1,'dataset','device',NULL,NULL,0,0,NULL,NULL,NULL,NULL,'IDLE',NULL,0,10)")
        database.execSQL("INSERT INTO categories(id) VALUES(1)")
        database.query("SELECT localGeneration FROM sync_state WHERE id=1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1L, cursor.getLong(0))
        }
        helper.close()
    }

    @Test
    fun roomValidatesProductionSchemaFiveToSixAndPreservesJournalBalances() {
        val name = "kron-production-5-to-6.db"
        migrationHelper.createDatabase(name, 5).apply {
            execSQL("INSERT INTO accounts(id,name,isActive,isArchived,archivedAt,createdAt) VALUES(1,'Utama',1,0,NULL,10)")
            execSQL("INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId) VALUES('event-5','INCOME','Saldo awal','','MANUAL',1,10,NULL,NULL,NULL)")
            execSQL("INSERT INTO cash_journal_lines(id,eventId,accountId,fundingChannel,amount) VALUES(1,'event-5',1,'CASH',125000)")
            execSQL("INSERT INTO budget_journal_lines(id,eventId,allocationId,bucket,fundingChannel,amount) VALUES(1,'event-5',NULL,'VAULT','CASH',125000)")
            execSQL("INSERT INTO receipts(id,eventId,localPath,mimeType,createdAt) VALUES(1,'event-5','/legacy/proof.jpg','image/jpeg',10)")
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            name,
            6,
            true,
            KronDatabase.MIGRATION_5_6,
        ).apply {
            query("SELECT SUM(amount) FROM cash_journal_lines").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(125000L, cursor.getLong(0))
            }
            query("SELECT SUM(amount) FROM budget_journal_lines").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(125000L, cursor.getLong(0))
            }
            query("SELECT COUNT(*) FROM activity_events WHERE id='event-5'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
            query("SELECT encryptionVersion, storageId FROM receipts WHERE id=1").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
                assertEquals(32, cursor.getString(1).length)
            }
            query("PRAGMA foreign_key_check").use { cursor -> assertTrue(!cursor.moveToFirst()) }
            close()
        }
    }
}
