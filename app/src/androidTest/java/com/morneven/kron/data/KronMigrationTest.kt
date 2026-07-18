package com.morneven.kron.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KronMigrationTest {
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
}
