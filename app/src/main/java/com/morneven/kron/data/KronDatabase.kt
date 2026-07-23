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
        LedgerAccountEntity::class,
        LedgerLineEntity::class,
        JournalSealEntity::class,
        EvidenceKeyEntity::class,
        ActorProfileEntity::class,
    ],
    version = 13,
    exportSchema = true,
)
abstract class KronDatabase : RoomDatabase() {
    abstract fun kronDao(): KronDao

    companion object {
        @Volatile private var instance: KronDatabase? = null

        fun getInstance(context: Context): KronDatabase = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val keyManager = DatabaseKeyManager(appContext)
                val encryption = DatabaseEncryptionManager(appContext, keyManager)
                val databaseFile = appContext.getDatabasePath(DATABASE_NAME)
                val wasFreshInstall = !databaseFile.exists()
                val dbPath = databaseFile.absolutePath
                val preparation = encryption.preparePrimaryDatabase(databaseFile)
                try {
                    val opened = Room.databaseBuilder(appContext, KronDatabase::class.java, DATABASE_NAME)
                        .openHelperFactory(encryption.openHelperFactory(dbPath, preparation.keyMode))
                        .addMigrations(*ALL_MIGRATIONS)
                        .addCallback(DATABASE_TRIGGER_CALLBACK)
                        .build()
                    val writableDatabase = opened.openHelper.writableDatabase
                    validateOpenedDatabase(writableDatabase)
                    LegacyReceiptEncryption.migrate(
                        appContext,
                        writableDatabase,
                        EncryptedAttachmentStore(appContext, keyManager),
                    )
                    keyManager.confirmKeyProfile(preparation.keyMode)
                    preparation.guard?.commit()
                    if (wasFreshInstall) encryption.markFreshDatabaseValidated()
                    opened.also { instance = it }
                } catch (error: Exception) {
                    preparation.guard?.rollback()
                    throw error
                }
            }
        }

        internal fun openPlaintextValidationDatabase(context: Context, name: String): KronDatabase {
            SqlCipherLibrary.ensureLoaded()
            return Room.databaseBuilder(context.applicationContext, KronDatabase::class.java, name)
                .addMigrations(*ALL_MIGRATIONS)
                .addCallback(DATABASE_TRIGGER_CALLBACK)
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

        @Deprecated("Dead code — jangan gunakan. Pakai MIGRATION_3_4_RECOVERY. Dipertahankan hanya sebagai catatan historis.")
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

        /**
         * Forward-only replacement path for schema 3. The original 3 -> 4
         * migration is retained above as shipped history, but dropping parent
         * tables could leave SQLite's deferred foreign-key counter dirty even
         * after foreign_key_check returned clean. This path never leaves a
         * child row without its parent inside the migration transaction.
         */
        val MIGRATION_3_4_RECOVERY: Migration = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TEMP TABLE account_channels (id INTEGER PRIMARY KEY NOT NULL, fundingChannel TEXT NOT NULL)")
                db.execSQL("INSERT INTO account_channels(id, fundingChannel) SELECT id, CASE WHEN fundingChannel = 'EBUDGET' THEN 'EBUDGET' ELSE 'CASH' END FROM accounts")

                listOf(
                    "index_cash_journal_lines_eventId",
                    "index_cash_journal_lines_accountId",
                    "index_recurring_rules_accountId",
                    "index_recurring_rules_categoryId",
                    "index_recurring_rules_allocationId",
                    "index_recurring_occurrences_ruleId",
                    "index_recurring_occurrences_ruleId_dueEpochDay",
                ).forEach { db.execSQL("DROP INDEX IF EXISTS $it") }

                db.execSQL("ALTER TABLE recurring_occurrences RENAME TO recurring_occurrences_v3")
                db.execSQL("ALTER TABLE recurring_rules RENAME TO recurring_rules_v3")
                db.execSQL("ALTER TABLE cash_journal_lines RENAME TO cash_journal_lines_v3")
                db.execSQL("ALTER TABLE accounts RENAME TO accounts_v3")

                db.execSQL("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, isActive INTEGER NOT NULL, isArchived INTEGER NOT NULL, createdAt INTEGER NOT NULL)")
                db.execSQL(
                    """
                    INSERT INTO accounts(id, name, isActive, isArchived, createdAt)
                    SELECT id, name,
                           CASE WHEN isArchived = 0 AND id = (SELECT MIN(id) FROM accounts_v3 WHERE isArchived = 0) THEN 1 ELSE 0 END,
                           isArchived, createdAt
                    FROM accounts_v3
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE cash_journal_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        accountId INTEGER NOT NULL,
                        fundingChannel TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        FOREIGN KEY(eventId) REFERENCES activity_events(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(accountId) REFERENCES accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO cash_journal_lines(id, eventId, accountId, fundingChannel, amount)
                    SELECT line.id, line.eventId, line.accountId, channel.fundingChannel, line.amount
                    FROM cash_journal_lines_v3 line
                    JOIN account_channels channel ON channel.id = line.accountId
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE recurring_rules (
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
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO recurring_rules(
                        id, title, direction, amount, accountId, fundingChannel, categoryId, allocationId,
                        cadence, intervalCount, anchorMonth, anchorDay, startEpochDay, nextEpochDay,
                        endEpochDay, remainingOccurrences, isPaused, createdAt
                    )
                    SELECT rule.id, rule.title, rule.direction, rule.amount, rule.accountId,
                           channel.fundingChannel, rule.categoryId, rule.allocationId,
                           rule.cadence, rule.intervalCount, rule.anchorMonth, rule.anchorDay,
                           rule.startEpochDay, rule.nextEpochDay, rule.endEpochDay,
                           rule.remainingOccurrences, rule.isPaused, rule.createdAt
                    FROM recurring_rules_v3 rule
                    JOIN account_channels channel ON channel.id = rule.accountId
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    CREATE TABLE recurring_occurrences (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        ruleId TEXT NOT NULL,
                        dueEpochDay INTEGER NOT NULL,
                        eventId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(ruleId) REFERENCES recurring_rules(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL("INSERT INTO recurring_occurrences(id, ruleId, dueEpochDay, eventId, createdAt) SELECT id, ruleId, dueEpochDay, eventId, createdAt FROM recurring_occurrences_v3")

                db.execSQL("DROP TABLE recurring_occurrences_v3")
                db.execSQL("DROP TABLE recurring_rules_v3")
                db.execSQL("DROP TABLE cash_journal_lines_v3")
                db.execSQL("DROP TABLE accounts_v3")

                db.execSQL("CREATE INDEX index_cash_journal_lines_eventId ON cash_journal_lines(eventId)")
                db.execSQL("CREATE INDEX index_cash_journal_lines_accountId ON cash_journal_lines(accountId)")
                db.execSQL("CREATE INDEX index_recurring_rules_accountId ON recurring_rules(accountId)")
                db.execSQL("CREATE INDEX index_recurring_rules_categoryId ON recurring_rules(categoryId)")
                db.execSQL("CREATE INDEX index_recurring_rules_allocationId ON recurring_rules(allocationId)")
                db.execSQL("CREATE INDEX index_recurring_occurrences_ruleId ON recurring_occurrences(ruleId)")
                db.execSQL("CREATE UNIQUE INDEX index_recurring_occurrences_ruleId_dueEpochDay ON recurring_occurrences(ruleId, dueEpochDay)")
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
                val legacyReceiptCount = scalar(db, "SELECT COUNT(*) FROM receipts")
                if (legacyReceiptCount > 0) {
                    val validReceipts = scalar(db, "SELECT COUNT(*) FROM receipts WHERE eventId IS NOT NULL AND mimeType IS NOT NULL")
                    require(validReceipts == legacyReceiptCount) {
                        "Legacy receipts table memiliki data tidak valid sebelum migrasi"
                    }
                }
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

        /**
         * Reconstruct ownership erased by the shipped 10 -> 11 migration. Amounts,
         * journal IDs, and audit records stay untouched. A dedicated legacy account
         * holds only records for which no defensible owner can be inferred.
         */
        val MIGRATION_11_12: Migration = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE INDEX IF NOT EXISTS index_portfolios_accountId ON portfolios(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_accountId ON activity_events(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_budget_journal_lines_accountId_fundingChannel ON budget_journal_lines(accountId, fundingChannel)")

                // Cash-backed lines can be mapped precisely, including transfers
                // whose source and destination use different channels.
                db.execSQL(
                    """
                    UPDATE budget_journal_lines
                    SET accountId = (
                        SELECT c.accountId
                        FROM cash_journal_lines c
                        WHERE c.eventId = budget_journal_lines.eventId
                          AND c.fundingChannel = budget_journal_lines.fundingChannel
                          AND ((budget_journal_lines.amount < 0 AND c.amount < 0)
                            OR (budget_journal_lines.amount > 0 AND c.amount > 0))
                        ORDER BY c.id
                        LIMIT 1
                    )
                    WHERE accountId = 0
                      AND EXISTS(
                        SELECT 1
                        FROM cash_journal_lines c
                        WHERE c.eventId = budget_journal_lines.eventId
                          AND c.fundingChannel = budget_journal_lines.fundingChannel
                          AND ((budget_journal_lines.amount < 0 AND c.amount < 0)
                            OR (budget_journal_lines.amount > 0 AND c.amount > 0))
                      )
                    """.trimIndent(),
                )

                // A portfolio is safe to assign only when all attributed journal
                // lines agree on exactly one account.
                db.execSQL(
                    """
                    UPDATE portfolios
                    SET accountId = (
                        SELECT MIN(b.accountId)
                        FROM allocations al
                        JOIN budget_periods p ON p.id = al.periodId
                        JOIN budget_journal_lines b ON b.allocationId = al.id
                        WHERE p.portfolioId = portfolios.id AND b.accountId != 0
                    )
                    WHERE accountId = 0
                      AND 1 = (
                        SELECT COUNT(DISTINCT b.accountId)
                        FROM allocations al
                        JOIN budget_periods p ON p.id = al.periodId
                        JOIN budget_journal_lines b ON b.allocationId = al.id
                        WHERE p.portfolioId = portfolios.id AND b.accountId != 0
                      )
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    UPDATE budget_journal_lines
                    SET accountId = (
                        SELECT pf.accountId
                        FROM allocations al
                        JOIN budget_periods p ON p.id = al.periodId
                        JOIN portfolios pf ON pf.id = p.portfolioId
                        WHERE al.id = budget_journal_lines.allocationId
                    )
                    WHERE accountId = 0
                      AND allocationId IS NOT NULL
                      AND EXISTS(
                        SELECT 1
                        FROM allocations al
                        JOIN budget_periods p ON p.id = al.periodId
                        JOIN portfolios pf ON pf.id = p.portfolioId
                        WHERE al.id = budget_journal_lines.allocationId
                          AND pf.accountId != 0
                      )
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    UPDATE activity_events
                    SET accountId = COALESCE(
                        (
                            SELECT c.accountId
                            FROM cash_journal_lines c
                            WHERE c.eventId = activity_events.id AND c.amount < 0
                            ORDER BY c.id
                            LIMIT 1
                        ),
                        (
                            SELECT c.accountId
                            FROM cash_journal_lines c
                            WHERE c.eventId = activity_events.id
                            ORDER BY c.id
                            LIMIT 1
                        )
                    )
                    WHERE accountId = 0
                      AND EXISTS(SELECT 1 FROM cash_journal_lines c WHERE c.eventId = activity_events.id)
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    UPDATE activity_events
                    SET accountId = (
                        SELECT MIN(b.accountId)
                        FROM budget_journal_lines b
                        WHERE b.eventId = activity_events.id AND b.accountId != 0
                    )
                    WHERE accountId = 0
                      AND 1 = (
                        SELECT COUNT(DISTINCT b.accountId)
                        FROM budget_journal_lines b
                        WHERE b.eventId = activity_events.id AND b.accountId != 0
                      )
                    """.trimIndent(),
                )

                db.execSQL(
                    """
                    UPDATE budget_journal_lines
                    SET accountId = (
                        SELECT e.accountId
                        FROM activity_events e
                        WHERE e.id = budget_journal_lines.eventId
                    )
                    WHERE accountId = 0
                      AND EXISTS(
                        SELECT 1 FROM activity_events e
                        WHERE e.id = budget_journal_lines.eventId AND e.accountId != 0
                      )
                    """.trimIndent(),
                )

                val needsLegacyAccount = db.query(
                    """
                    SELECT EXISTS(
                        SELECT 1 FROM portfolios WHERE accountId = 0
                        UNION ALL SELECT 1 FROM activity_events WHERE accountId = 0
                        UNION ALL SELECT 1 FROM budget_journal_lines WHERE accountId = 0
                        UNION ALL SELECT 1 FROM recurring_rules WHERE accountId = 0
                    )
                    """.trimIndent(),
                ).use { cursor -> cursor.moveToFirst() && cursor.getInt(0) == 1 }
                if (needsLegacyAccount) {
                    db.execSQL(
                        """
                        INSERT INTO accounts(name, isActive, isArchived, archivedAt, createdAt)
                        SELECT 'Data KRON Lama', 0, 0, NULL, strftime('%s', 'now') * 1000
                        WHERE NOT EXISTS(SELECT 1 FROM accounts WHERE name = 'Data KRON Lama')
                        """.trimIndent(),
                    )
                    val legacyAccountId = db.query(
                        "SELECT id FROM accounts WHERE name = 'Data KRON Lama' ORDER BY id LIMIT 1",
                    ).use { cursor ->
                        require(cursor.moveToFirst()) { "Akun data lama tidak dapat dibuat" }
                        cursor.getLong(0)
                    }
                    db.execSQL("UPDATE portfolios SET accountId = ? WHERE accountId = 0", arrayOf(legacyAccountId))
                    db.execSQL("UPDATE activity_events SET accountId = ? WHERE accountId = 0", arrayOf(legacyAccountId))
                    db.execSQL("UPDATE budget_journal_lines SET accountId = ? WHERE accountId = 0", arrayOf(legacyAccountId))
                    db.execSQL("UPDATE recurring_rules SET accountId = ? WHERE accountId = 0", arrayOf(legacyAccountId))
                }
            }
        }

        val MIGRATION_12_13: Migration = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ledger_accounts (
                        id TEXT NOT NULL PRIMARY KEY,
                        code TEXT NOT NULL,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        accountId INTEGER,
                        fundingChannel TEXT,
                        categoryId INTEGER,
                        createdAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_accounts_code ON ledger_accounts(code)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_accounts_accountId ON ledger_accounts(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_accounts_categoryId ON ledger_accounts(categoryId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ledger_lines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        ledgerAccountId TEXT NOT NULL,
                        side TEXT NOT NULL,
                        amount INTEGER NOT NULL,
                        accountId INTEGER,
                        fundingChannel TEXT,
                        categoryId INTEGER,
                        correlationId TEXT,
                        legacyBackfill INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(eventId) REFERENCES activity_events(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(ledgerAccountId) REFERENCES ledger_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_lines_eventId ON ledger_lines(eventId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_lines_ledgerAccountId ON ledger_lines(ledgerAccountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_lines_accountId ON ledger_lines(accountId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_lines_correlationId ON ledger_lines(correlationId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS evidence_keys (
                        id TEXT NOT NULL PRIMARY KEY,
                        alias TEXT NOT NULL,
                        algorithm TEXT NOT NULL,
                        publicKeyBase64 TEXT NOT NULL,
                        certificateBase64 TEXT NOT NULL,
                        fingerprint TEXT NOT NULL,
                        securityLevel TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        retiredAt INTEGER
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_evidence_keys_fingerprint ON evidence_keys(fingerprint)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS journal_seals (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        eventId TEXT NOT NULL,
                        sequence INTEGER NOT NULL,
                        previousChainHash TEXT NOT NULL,
                        payloadHash TEXT NOT NULL,
                        chainHash TEXT NOT NULL,
                        signatureBase64 TEXT NOT NULL,
                        recordedAtUtc INTEGER NOT NULL,
                        timezoneId TEXT NOT NULL,
                        deviceId TEXT NOT NULL,
                        actor TEXT NOT NULL,
                        appVersion TEXT NOT NULL,
                        keyId TEXT NOT NULL,
                        legacyBackfill INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(eventId) REFERENCES activity_events(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
                        FOREIGN KEY(keyId) REFERENCES evidence_keys(id) ON UPDATE NO ACTION ON DELETE RESTRICT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_seals_eventId ON journal_seals(eventId)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_journal_seals_sequence ON journal_seals(sequence)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_journal_seals_keyId ON journal_seals(keyId)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS actor_profiles (
                        id INTEGER NOT NULL PRIMARY KEY,
                        displayName TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    "INSERT OR IGNORE INTO actor_profiles(id, displayName, updatedAt) VALUES(1, 'Pengguna lokal', strftime('%s','now') * 1000)",
                )
                db.execSQL("ALTER TABLE receipts ADD COLUMN origin TEXT NOT NULL DEFAULT 'LEGACY'")
                db.execSQL("ALTER TABLE receipts ADD COLUMN evidenceEventId TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_receipts_evidenceEventId ON receipts(evidenceEventId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_activity_events_accountId_effectiveEpochDay ON activity_events(accountId, effectiveEpochDay)")

                val nowExpression = "strftime('%s','now') * 1000"
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO ledger_accounts(id, code, name, kind, accountId, fundingChannel, categoryId, createdAt)
                    VALUES
                        ('income:general', '4000', 'Pemasukan', 'INCOME', NULL, NULL, NULL, $nowExpression),
                        ('expense:general', '5000', 'Pengeluaran', 'EXPENSE', NULL, NULL, NULL, $nowExpression),
                        ('equity:opening', '3000', 'Modal awal', 'EQUITY', NULL, NULL, NULL, $nowExpression),
                        ('clearing:legacy', '9999', 'Legacy clearing', 'CLEARING', NULL, NULL, NULL, $nowExpression)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO ledger_accounts(id, code, name, kind, accountId, fundingChannel, categoryId, createdAt)
                    SELECT 'asset:' || a.id || ':' || ch.channel,
                           '1' || printf('%06d', a.id) || CASE ch.channel WHEN 'CASH' THEN '01' ELSE '02' END,
                           a.name || ' ' || CASE ch.channel WHEN 'CASH' THEN 'Cash' ELSE 'eBudget' END,
                           'ASSET', a.id, ch.channel, NULL, $nowExpression
                    FROM accounts a
                    CROSS JOIN (SELECT 'CASH' AS channel UNION ALL SELECT 'EBUDGET') ch
                    """.trimIndent(),
                )

                // Ordinary cash movements are reconstructed without changing any
                // historical event or amount. Ambiguous legacy counterparts use a
                // dedicated clearing account and remain visibly marked as backfill.
                db.execSQL(
                    """
                    INSERT INTO ledger_lines(eventId, ledgerAccountId, side, amount, accountId, fundingChannel, categoryId, correlationId, legacyBackfill)
                    SELECT c.eventId,
                           'asset:' || c.accountId || ':' || c.fundingChannel,
                           CASE WHEN c.amount > 0 THEN 'DEBIT' ELSE 'CREDIT' END,
                           ABS(c.amount), c.accountId, c.fundingChannel, NULL, e.relatedEventId, 1
                    FROM cash_journal_lines c
                    JOIN activity_events e ON e.id = c.eventId
                    WHERE c.amount != 0 AND e.type NOT IN ('TRANSFER','CHANNEL_TRANSFER','REVERSAL')
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_lines(eventId, ledgerAccountId, side, amount, accountId, fundingChannel, categoryId, correlationId, legacyBackfill)
                    SELECT c.eventId,
                           CASE
                               WHEN e.type = 'OPENING_BALANCE' THEN 'equity:opening'
                               WHEN e.type IN ('INCOME','AUTOMATION') AND c.amount > 0 THEN 'income:general'
                               WHEN e.type IN ('EXPENSE','UNEXPECTED_EXPENSE','AUTOMATION') AND c.amount < 0 THEN 'expense:general'
                               ELSE 'clearing:legacy'
                           END,
                           CASE WHEN c.amount > 0 THEN 'CREDIT' ELSE 'DEBIT' END,
                           ABS(c.amount), c.accountId, c.fundingChannel,
                           (SELECT s.categoryId FROM transaction_splits s WHERE s.eventId = c.eventId ORDER BY s.id LIMIT 1),
                           e.relatedEventId, 1
                    FROM cash_journal_lines c
                    JOIN activity_events e ON e.id = c.eventId
                    WHERE c.amount != 0 AND e.type NOT IN ('TRANSFER','CHANNEL_TRANSFER','REVERSAL')
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_lines(eventId, ledgerAccountId, side, amount, accountId, fundingChannel, categoryId, correlationId, legacyBackfill)
                    SELECT c.eventId, 'asset:' || c.accountId || ':' || c.fundingChannel,
                           CASE WHEN c.amount > 0 THEN 'DEBIT' ELSE 'CREDIT' END,
                           ABS(c.amount), c.accountId, c.fundingChannel, NULL, e.relatedEventId, 1
                    FROM cash_journal_lines c
                    JOIN activity_events e ON e.id = c.eventId
                    WHERE c.amount != 0 AND e.type IN ('TRANSFER','CHANNEL_TRANSFER')
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_lines(eventId, ledgerAccountId, side, amount, accountId, fundingChannel, categoryId, correlationId, legacyBackfill)
                    SELECT r.id, l.ledgerAccountId,
                           CASE l.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END,
                           l.amount, l.accountId, l.fundingChannel, l.categoryId, r.relatedEventId, 1
                    FROM activity_events r
                    JOIN ledger_lines l ON l.eventId = r.relatedEventId
                    WHERE r.type = 'REVERSAL'
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_lines(eventId, ledgerAccountId, side, amount, accountId, fundingChannel, categoryId, correlationId, legacyBackfill)
                    SELECT c.eventId, 'asset:' || c.accountId || ':' || c.fundingChannel,
                           CASE WHEN c.amount > 0 THEN 'DEBIT' ELSE 'CREDIT' END,
                           ABS(c.amount), c.accountId, c.fundingChannel, NULL, e.relatedEventId, 1
                    FROM cash_journal_lines c
                    JOIN activity_events e ON e.id = c.eventId
                    WHERE c.amount != 0 AND e.type = 'REVERSAL'
                      AND NOT EXISTS(SELECT 1 FROM ledger_lines l WHERE l.eventId = e.relatedEventId)
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    INSERT INTO ledger_lines(eventId, ledgerAccountId, side, amount, accountId, fundingChannel, categoryId, correlationId, legacyBackfill)
                    SELECT c.eventId, 'clearing:legacy',
                           CASE WHEN c.amount > 0 THEN 'CREDIT' ELSE 'DEBIT' END,
                           ABS(c.amount), c.accountId, c.fundingChannel, NULL, e.relatedEventId, 1
                    FROM cash_journal_lines c
                    JOIN activity_events e ON e.id = c.eventId
                    WHERE c.amount != 0 AND e.type = 'REVERSAL'
                      AND NOT EXISTS(SELECT 1 FROM ledger_lines l WHERE l.eventId = e.relatedEventId)
                    """.trimIndent(),
                )
                // Historical cross-account and cross-channel movements were balanced
                // globally, but did not carry the per-account/channel bridge required
                // by the schema 13 budget subledger contract.
                db.execSQL(
                    """
                    INSERT INTO budget_journal_lines(eventId, allocationId, bucket, fundingChannel, amount, accountId)
                    SELECT eventId, NULL, 'EXTERNAL', fundingChannel, -SUM(amount), accountId
                    FROM budget_journal_lines
                    GROUP BY eventId, accountId, fundingChannel
                    HAVING SUM(amount) != 0
                    """.trimIndent(),
                )
                createAppendOnlyTriggers(db)
                createSyncGenerationTriggers(db)
            }
        }

        private val ALL_MIGRATIONS = arrayOf(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4_RECOVERY,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
            MIGRATION_10_11,
            MIGRATION_11_12,
            MIGRATION_12_13,
        )

        private val DATABASE_TRIGGER_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                createSyncGenerationTriggers(db)
                createAppendOnlyTriggers(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                createSyncGenerationTriggers(db)
                createAppendOnlyTriggers(db)
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
                "ledger_accounts",
                "ledger_lines",
                "journal_seals",
                "evidence_keys",
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

        private fun createAppendOnlyTriggers(db: SupportSQLiteDatabase) {
            val immutableTables = listOf(
                "activity_events",
                "cash_journal_lines",
                "budget_journal_lines",
                "transaction_splits",
                "audit_snapshots",
                "ledger_lines",
                "journal_seals",
                "evidence_keys",
            )
            immutableTables.forEach { table ->
                listOf("UPDATE", "DELETE").forEach { operation ->
                    db.execSQL(
                        """
                        CREATE TRIGGER IF NOT EXISTS append_only_${table}_${operation.lowercase()}
                        BEFORE $operation ON $table
                        BEGIN
                            SELECT RAISE(ABORT, 'Catatan audit KRON bersifat append-only');
                        END
                        """.trimIndent(),
                    )
                }
            }
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS append_only_receipts_update
                BEFORE UPDATE ON receipts
                WHEN NEW.eventId != OLD.eventId
                  OR NEW.storageId != OLD.storageId
                  OR NEW.displayName != OLD.displayName
                  OR NEW.mimeType != OLD.mimeType
                  OR NEW.byteSize != OLD.byteSize
                  OR NEW.sha256 != OLD.sha256
                  OR NEW.createdAt != OLD.createdAt
                  OR COALESCE(NEW.capturedAt, -1) != COALESCE(OLD.capturedAt, -1)
                  OR COALESCE(NEW.latitude, 999) != COALESCE(OLD.latitude, 999)
                  OR COALESCE(NEW.longitude, 999) != COALESCE(OLD.longitude, 999)
                  OR NEW.origin != OLD.origin
                  OR COALESCE(NEW.evidenceEventId, '') != COALESCE(OLD.evidenceEventId, '')
                BEGIN
                    SELECT RAISE(ABORT, 'Metadata bukti KRON bersifat append-only');
                END
                """.trimIndent(),
            )
            db.execSQL(
                """
                CREATE TRIGGER IF NOT EXISTS append_only_receipts_delete
                BEFORE DELETE ON receipts
                BEGIN
                    SELECT RAISE(ABORT, 'Bukti KRON bersifat append-only');
                END
                """.trimIndent(),
            )
        }

        private fun validateOpenedDatabase(db: SupportSQLiteDatabase) {
            val integrityOk = db.query("PRAGMA integrity_check").use { cursor ->
                cursor.moveToFirst() && cursor.getString(0).equals("ok", ignoreCase = true)
            }
            require(integrityOk) { "Integritas database KRON tidak valid" }
            val hasForeignKeyViolation = db.query("PRAGMA foreign_key_check").use { it.moveToFirst() }
            require(!hasForeignKeyViolation) { "Relasi database KRON tidak valid" }

            val unbalancedLedger = scalar(
                db,
                """
                SELECT COUNT(*) FROM (
                    SELECT eventId,
                           SUM(CASE WHEN side='DEBIT' THEN amount ELSE 0 END) AS debit,
                           SUM(CASE WHEN side='CREDIT' THEN amount ELSE 0 END) AS credit
                    FROM ledger_lines GROUP BY eventId HAVING debit != credit
                )
                """.trimIndent(),
            )
            require(unbalancedLedger == 0L) { "General ledger memiliki event tidak seimbang" }
            val invalidLedgerLine = scalar(
                db,
                "SELECT COUNT(*) FROM ledger_lines WHERE amount <= 0 OR side NOT IN ('DEBIT','CREDIT')",
            )
            require(invalidLedgerLine == 0L) { "General ledger memiliki baris tidak valid" }
            val unbalancedBudgetEvent = scalar(
                db,
                "SELECT COUNT(*) FROM (SELECT eventId FROM budget_journal_lines GROUP BY eventId HAVING SUM(amount) != 0)",
            )
            require(unbalancedBudgetEvent == 0L) { "Subledger budget memiliki event tidak seimbang" }
            val unbalancedBudgetScope = scalar(
                db,
                "SELECT COUNT(*) FROM (SELECT eventId, accountId, fundingChannel FROM budget_journal_lines GROUP BY eventId, accountId, fundingChannel HAVING SUM(amount) != 0)",
            )
            require(unbalancedBudgetScope == 0L) { "Subledger budget per akun dan kanal tidak seimbang" }

            val cashTotal = scalar(db, "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines")
            val budgetTotal = scalar(
                db,
                "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL",
            )
            require(cashTotal == budgetTotal) { "Invariant total aset database tidak seimbang" }
            val channelPairs = listOf(FundingChannel.CASH to "CASH", FundingChannel.EBUDGET to "EBUDGET")
            channelPairs.forEach { (_, channelName) ->
                val cash = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE fundingChannel=?",
                    arrayOf(channelName),
                )
                val available = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE fundingChannel=? AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                    arrayOf(channelName),
                )
                require(cash == available) { "Invariant kanal $channelName tidak seimbang" }
            }
            val accountIds = db.query("SELECT id FROM accounts").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getLong(0))
                }
            }
            accountIds.forEach { accountId ->
                val accountIdStr = accountId.toString()
                val cash = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE accountId=?",
                    arrayOf(accountIdStr),
                )
                val available = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines WHERE accountId=? " +
                        "AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                    arrayOf(accountIdStr),
                )
                require(cash == available) { "Invariant akun database tidak seimbang" }
                channelPairs.forEach { (_, channelName) ->
                    val channelCash = scalar(
                        db,
                        "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines " +
                            "WHERE accountId=? AND fundingChannel=?",
                        arrayOf(accountIdStr, channelName),
                    )
                    val channelAvailable = scalar(
                        db,
                        "SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines " +
                            "WHERE accountId=? AND fundingChannel=? " +
                            "AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)",
                        arrayOf(accountIdStr, channelName),
                    )
                    require(channelCash == channelAvailable) { "Invariant kanal akun database tidak seimbang" }
                }
            }
        }

        private fun scalar(db: SupportSQLiteDatabase, sql: String): Long = db.query(sql).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }

        private fun scalar(db: SupportSQLiteDatabase, sql: String, args: Array<String>): Long = db.query(sql, args).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }

        const val DATABASE_NAME = "kron-v4.db"

        val SCHEMA_VERSION: Int by lazy {
            KronDatabase::class.java.getAnnotation(Database::class.java)?.version
                ?: error("Database annotation missing version")
        }
    }
}
