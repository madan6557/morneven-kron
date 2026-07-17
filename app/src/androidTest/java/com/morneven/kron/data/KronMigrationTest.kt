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
}
