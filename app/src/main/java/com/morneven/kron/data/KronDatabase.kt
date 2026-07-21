package com.morneven.kron.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.morneven.kron.security.DatabaseEncryptionManager
import com.morneven.kron.security.DatabaseKeyManager
import com.morneven.kron.security.EncryptedAttachmentStore
import com.morneven.kron.security.LegacyReceiptEncryption
import com.morneven.kron.security.SqlCipherLibrary

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
        SyncStateEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
abstract class KronDatabase : RoomDatabase() {
    abstract fun kronDao(): KronDao

    companion object {
        @Volatile private var instance: KronDatabase? = null

        fun getInstance(context: Context): KronDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val encryption = DatabaseEncryptionManager(appContext, DatabaseKeyManager(appContext))
                val dbPath = appContext.getDatabasePath(DATABASE_NAME).absolutePath
                val guard = encryption.preparePrimaryDatabase(appContext.getDatabasePath(DATABASE_NAME))
                try {
                    val opened = Room.databaseBuilder(appContext, KronDatabase::class.java, DATABASE_NAME)
                        .openHelperFactory(encryption.openHelperFactory(dbPath))
                        .addMigrations(*ALL_MIGRATIONS)
                        .addCallback(SYNC_TRIGGER_CALLBACK)
                        .build()
                    val writableDatabase = opened.openHelper.writableDatabase
                    validateOpenedDatabase(writableDatabase)
                    LegacyReceiptEncryption.migrate(
                        appContext,
                        writableDatabase,
                        EncryptedAttachmentStore(appContext, DatabaseKeyManager(appContext)),
                    )
                    guard?.commit()
                    opened.also { instance = it }
                } catch (error: Exception) {
                    guard?.rollback()
                    throw error
                }
            }
        }

        internal fun openPlaintextValidationDatabase(context: Context, name: String): KronDatabase {
            SqlCipherLibrary.ensureLoaded()
            return Room.databaseBuilder(context.applicationContext, KronDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(SYNC_TRIGGER_CALLBACK)
                .build()
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

        val MIGRATION_3_4: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                db.execSQL("CREATE TEMP TABLE account_channels (id INTEGER PRIMARY KEY NOT NULL, fundingChannel TEXT NOT NULL)")
                db.execSQL("INSERT INTO account_channels(id, fundingChannel) SELECT id, CASE WHEN fundingChannel = 'EBUDGET' THEN 'EBUDGET' ELSE 'CASH' END FROM accounts")

                db.execSQL("CREATE TABLE accounts_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, isActive INTEGER NOT NULL, isArchived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL("""
                    INSERT INTO accounts_new(id, name, isActive, isArchived, createdAt)
                    SELECT id, name,
                           CASE WHEN isArchived = 0 AND id = (SELECT MIN(id) FROM accounts WHERE isArchived = 0) THEN 1 ELSE 0 END,
                           isArchived, createdAt
                    FROM accounts
                """.trimIndent())
                db.execSQL("DROP TABLE accounts")
                db.execSQL("ALTER TABLE accounts_new RENAME TO accounts")

                db.execSQL("""
                    CREATE TABLE cash_journal_lines_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        accountId INTEGER NOT NULL,
                        fundingChannel TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        FOREIGN KEY(eventId) REFERENCES activity_events(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO cash_journal_lines_new(id, eventId, accountId, fundingChannel, amount)
                    SELECT line.id, line.eventId, line.accountId, channel.fundingChannel, line.amount
                    FROM cash_journal_lines line
                    JOIN account_channels channel ON channel.id = line.accountId
                """.trimIndent())
                db.execSQL("DROP TABLE cash_journal_lines")
                db.execSQL("ALTER TABLE cash_journal_lines_new RENAME TO cash_journal_lines")
                db.execSQL("CREATE INDEX index_cash_journal_lines_eventId ON cash_journal_lines(eventId)")
                db.execSQL("CREATE INDEX index_cash_journal_lines_accountId ON cash_journal_lines(accountId)")

                db.execSQL("""
                    CREATE TABLE recurring_rules_new (
                        id TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        direction TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        accountId INTEGER NOT NULL,
                        fundingChannel TEXT NOT NULL,
                        categoryId INTEGER,
                        allocationId INTEGER,
                        cadence TEXT NOT NULL,
                        intervalCount INTEGER NOT NULL,
                        anchorMonth INTEGER NOT NULL,
                        anchorDay INTEGER NOT NULL,
                        startEpochDay INTEGER NOT NULL,
                        nextEpochDay INTEGER NOT NULL,
                        endEpochDay INTEGER,
                        remainingOccurrences INTEGER,
                        isPaused INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(allocationId) REFERENCES allocations(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO recurring_rules_new(
                        id, title, direction, amount, accountId, fundingChannel, categoryId, allocationId,
                        cadence, intervalCount, anchorMonth, anchorDay, startEpochDay, nextEpochDay,
                        endEpochDay, remainingOccurrences, isPaused, createdAt
                    )
                    SELECT rule.id, rule.title, rule.direction, rule.amount, rule.accountId,
                           channel.fundingChannel, rule.categoryId, rule.allocationId,
                           rule.cadence, rule.intervalCount, rule.anchorMonth, rule.anchorDay,
                           rule.startEpochDay, rule.nextEpochDay, rule.endEpochDay,
                           rule.remainingOccurrences, rule.isPaused, rule.createdAt
                    FROM recurring_rules rule
                    JOIN account_channels channel ON channel.id = rule.accountId
                """.trimIndent())
                db.execSQL("DROP TABLE recurring_rules")
                db.execSQL("ALTER TABLE recurring_rules_new RENAME TO recurring_rules")
                db.execSQL("CREATE INDEX index_recurring_rules_accountId ON recurring_rules(accountId)")
                db.execSQL("CREATE INDEX index_recurring_rules_categoryId ON recurring_rules(categoryId)")
                db.execSQL("CREATE INDEX index_recurring_rules_allocationId ON recurring_rules(allocationId)")
                db.execSQL("DROP TABLE account_channels")
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

        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA defer_foreign_keys=ON")
                db.execSQL("""
                    CREATE TABLE sync_state (
                        id INTEGER NOT NULL PRIMARY KEY,
                        datasetId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        accountSubject TEXT,
                        accountEmail TEXT,
                        localGeneration INTEGER NOT NULL,
                        lastSyncedGeneration INTEGER NOT NULL,
                        parentSnapshotId TEXT,
                        lastSnapshotId TEXT,
                        conflictRemoteFileId TEXT,
                        lastSyncedAt INTEGER,
                        status TEXT NOT NULL,
                        lastError TEXT,
                        disabledDueToBilling INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE receipts_new (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        localPath TEXT NOT NULL,
                        storageId TEXT NOT NULL,
                        displayName TEXT NOT NULL,
                        mimeType TEXT NOT NULL,
                        byteSize INTEGER NOT NULL,
                        sha256 TEXT NOT NULL,
                        encryptionNonce TEXT,
                        encryptionVersion INTEGER NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(eventId) REFERENCES activity_events(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                """.trimIndent())
                db.execSQL("""
                    INSERT INTO receipts_new(
                        id, eventId, localPath, storageId, displayName, mimeType,
                        byteSize, sha256, encryptionNonce, encryptionVersion, createdAt
                    )
                    SELECT id, eventId, localPath, 'legacy_' || printf('%025d', id),
                           'Bukti-' || id, mimeType, 0, '', NULL, 0, createdAt
                    FROM receipts
                """.trimIndent())
                db.execSQL("DROP TABLE receipts")
                db.execSQL("ALTER TABLE receipts_new RENAME TO receipts")
                db.execSQL("CREATE INDEX index_receipts_eventId ON receipts(eventId)")
                db.execSQL("CREATE UNIQUE INDEX index_receipts_storageId ON receipts(storageId)")
                createSyncGenerationTriggers(db)
            }
        }

        val MIGRATION_6_7: Migration = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE receipts ADD COLUMN capturedAt INTEGER")
                db.execSQL("ALTER TABLE receipts ADD COLUMN latitude REAL")
                db.execSQL("ALTER TABLE receipts ADD COLUMN longitude REAL")
            }
        }

        val MIGRATION_7_8: Migration = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE activity_events ADD COLUMN accountId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE portfolios ADD COLUMN accountId INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE budget_journal_lines ADD COLUMN accountId INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_8_9: Migration = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    UPDATE activity_events SET accountId = COALESCE(
                        (SELECT accountId FROM cash_journal_lines WHERE eventId = activity_events.id LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE portfolios SET accountId = COALESCE(
                        (SELECT id FROM accounts WHERE isActive = 1 LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE budget_journal_lines SET accountId = COALESCE(
                        (SELECT accountId FROM cash_journal_lines WHERE eventId = budget_journal_lines.eventId LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE budget_journal_lines SET accountId = COALESCE(
                        (SELECT p.accountId FROM allocations al
                         JOIN budget_periods per ON per.id = al.periodId
                         JOIN portfolios p ON p.id = per.portfolioId
                         WHERE al.id = budget_journal_lines.allocationId),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE budget_journal_lines SET accountId = COALESCE(
                        (SELECT accountId FROM budget_journal_lines b2
                         WHERE b2.eventId = budget_journal_lines.eventId AND b2.accountId != 0
                         LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE activity_events SET accountId = COALESCE(
                        (SELECT accountId FROM budget_journal_lines WHERE eventId = activity_events.id AND accountId != 0 LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
            }
        }

        val MIGRATION_9_10: Migration = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    UPDATE budget_journal_lines SET accountId = COALESCE(
                        (SELECT accountId FROM budget_journal_lines b2
                         WHERE b2.eventId = budget_journal_lines.eventId AND b2.accountId != 0
                         LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
                db.execSQL("""
                    UPDATE activity_events SET accountId = COALESCE(
                        (SELECT accountId FROM budget_journal_lines WHERE eventId = activity_events.id AND accountId != 0 LIMIT 1),
                        0
                    ) WHERE accountId = 0
                """.trimIndent())
            }
        }

        val MIGRATION_10_11: Migration = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("UPDATE portfolios SET accountId = 0 WHERE accountId != 0")
                db.execSQL("UPDATE activity_events SET accountId = 0")
                db.execSQL("UPDATE budget_journal_lines SET accountId = 0")
            }
        }

        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
        )

        private val SYNC_TRIGGER_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createSyncGenerationTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                createSyncGenerationTriggers(db)
            }
        }

        private fun createSyncGenerationTriggers(db: SupportSQLiteDatabase) {
            val generationTables = listOf(
                "accounts",
                "categories",
                "portfolios",
                "budget_periods",
                "allocations",
                "portfolio_allocation_templates",
                "activity_events",
                "recurring_rules",
                "receipts",
            )
            generationTables.forEach { table ->
                listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                    val suffix = operation.lowercase()
                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS sync_generation_${table}_$suffix
                        AFTER $operation ON $table
                        BEGIN
                            UPDATE sync_state
                            SET localGeneration = localGeneration + 1,
                                updatedAt = strftime('%s','now') * 1000
                            WHERE id = 1;
                        END
                    """.trimIndent())
                }
            }
            val guardedTables = generationTables + listOf(
                "cash_journal_lines",
                "budget_journal_lines",
                "transaction_splits",
                "recurring_occurrences",
                "audit_snapshots",
            )
            guardedTables.forEach { table ->
                listOf("INSERT", "UPDATE", "DELETE").forEach { operation ->
                    val suffix = operation.lowercase()
                    db.execSQL("""
                        CREATE TRIGGER IF NOT EXISTS sync_write_guard_${table}_$suffix
                        BEFORE $operation ON $table
                        WHEN EXISTS(
                            SELECT 1 FROM sync_state
                            WHERE id = 1 AND status IN ('SYNCING','RESTART_REQUIRED')
                        )
                        BEGIN
                            SELECT RAISE(ABORT, 'KRON sedang menyinkronkan atau menunggu restart');
                        END
                    """.trimIndent())
                }
            }
        }

        private fun validateOpenedDatabase(db: SupportSQLiteDatabase) {
            val integrityOk = db.query("PRAGMA integrity_check").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            require(integrityOk) { "Integritas database KRON tidak valid" }
            val hasForeignKeyViolation = db.query("PRAGMA foreign_key_check").use { it.moveToFirst() }
            require(!hasForeignKeyViolation) { "Relasi database KRON tidak valid" }

            val cashTotal = scalar(db, "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines")
            val budgetTotal = scalar(
                db,
                "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL",
            )
            require(cashTotal == budgetTotal) { "Invariant total aset database tidak seimbang" }
            listOf(FundingChannel.CASH, FundingChannel.EBUDGET).forEach { channel ->
                val cash = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE fundingChannel='$channel'",
                )
                val available = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE fundingChannel='$channel' AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                )
                require(cash == available) { "Invariant kanal $channel tidak seimbang" }
            }
        }

        private fun scalar(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }

        const val DATABASE_NAME = "kron-v4.db"
    }
}
