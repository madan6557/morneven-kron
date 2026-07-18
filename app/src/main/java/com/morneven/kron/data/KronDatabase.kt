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
    version = 5,
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
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_4_5).build().also { instance = it }
        }

        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE portfolios ADD COLUMN intervalCount INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN intervalCount INTEGER NOT NULL DEFAULT 1")
            }
        }

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_events ADD COLUMN targetAllocationId INTEGER")
            }
        }

        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE portfolios ADD COLUMN isArchived INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE portfolios ADD COLUMN archivedAt INTEGER")
                db.execSQL("ALTER TABLE accounts ADD COLUMN archivedAt INTEGER")
                db.execSQL("ALTER TABLE recurring_rules ADD COLUMN pausedByArchive INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
