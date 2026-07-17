package com.morneven.kron.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AccountEntity::class,
        CategoryEntity::class,
        PortfolioEntity::class,
        BudgetPeriodEntity::class,
        AllocationEntity::class,
        PortfolioAllocationTemplateEntity::class,
        ActivityEventEntity::class,
        CashJournalLineEntity::class,
        BudgetJournalLineEntity::class,
        TransactionSplitEntity::class,
        RecurringRuleEntity::class,
        RecurringOccurrenceEntity::class,
        AuditSnapshotEntity::class,
        ReceiptEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class KronDatabase : RoomDatabase() {
    abstract fun kronDao(): KronDao

    companion object {
        @Volatile private var instance: KronDatabase? = null

        fun getInstance(context: Context): KronDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KronDatabase::class.java,
                "kron-v4.db",
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build().also { instance = it }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE portfolios ADD COLUMN intervalCount INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE recurring_rules ADD COLUMN intervalCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE activity_events ADD COLUMN targetAllocationId INTEGER")
            }
        }
    }
}
