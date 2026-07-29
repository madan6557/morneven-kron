package com.morneven.kron.backup

import android.database.sqlite.SQLiteDatabase
import com.morneven.kron.data.KronDatabase
import java.io.File

/** Applies a verified descendant snapshot to one existing Team account in a staging database. */
internal object TeamGraphRefresher {
    fun refresh(
        target: File,
        source: File,
        teamId: String,
        folderId: String,
        liveFileId: String?,
        role: String,
        headSnapshotId: String,
        generation: Long,
    ) {
        val sourceScope = TeamSnapshotPruner.validateImported(source, teamId)
        require(sourceScope.generation == generation) { "Generation snapshot Team tidak cocok" }
        SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            require(scalar(db, "PRAGMA user_version") == KronDatabase.SCHEMA_VERSION.toLong()) {
                "Schema database target Team tidak sesuai"
            }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("ATTACH DATABASE ? AS team_source", arrayOf(source.absolutePath))
            try {
                db.beginTransaction()
                try {
                    val accountId = scalar(db, "SELECT id FROM accounts WHERE teamId=?", arrayOf(teamId))
                    require(accountId > 0) { "Akun Team lokal tidak ditemukan" }
                    validateAppendOnlyHistory(db, accountId)
                    createMappings(db)
                    upsertMutableGraph(db, sourceScope.accountId, accountId)
                    appendEvents(db, sourceScope.accountId, accountId)
                    db.execSQL(
                        """UPDATE team_workspaces SET folderId=?,localRole=?,liveFileId=?,headSnapshotId=?,generation=?,status='SYNCED',
                           canRead=1,canWrite=?,canShare=0,capabilitiesVerifiedAt=?,updatedAt=?
                           WHERE accountId=? AND teamId=?""",
                        arrayOf<Any?>(folderId, role, liveFileId, headSnapshotId, generation, if (role == "VIEWER") 0 else 1,
                            System.currentTimeMillis(), System.currentTimeMillis(), accountId, teamId),
                    )
                    require(!db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) {
                        "Relasi hasil sync Team tidak valid"
                    }
                    validateFinancialInvariants(db)
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE team_source")
            }
        }
    }

    private fun validateAppendOnlyHistory(db: SQLiteDatabase, accountId: Long) {
        requireZero(
            db,
            "SELECT COUNT(*) FROM activity_events l WHERE l.accountId=? AND NOT EXISTS(SELECT 1 FROM team_source.activity_events r WHERE r.id=l.id)",
            arrayOf(accountId.toString()),
            "Snapshot Team tidak memuat seluruh riwayat lokal",
        )
        requireZero(
            db,
            """SELECT COUNT(*) FROM team_event_proofs l JOIN team_source.team_event_proofs r ON r.eventId=l.eventId
               WHERE l.teamId=(SELECT teamId FROM accounts WHERE id=?)
                 AND (l.payloadHash<>r.payloadHash OR l.chainHash<>r.chainHash OR l.signatureBase64<>r.signatureBase64)""",
            arrayOf(accountId.toString()),
            "Bukti event Team berbeda dari snapshot lokal",
        )
    }

    private fun createMappings(db: SQLiteDatabase) {
        listOf("category", "portfolio", "period", "allocation").forEach { name ->
            db.execSQL("CREATE TEMP TABLE refresh_${name}_map(sourceId INTEGER PRIMARY KEY,targetId INTEGER NOT NULL UNIQUE)")
        }
        db.execSQL("CREATE TEMP TABLE refresh_key_map(sourceId TEXT PRIMARY KEY,targetId TEXT NOT NULL)")
        db.execSQL("CREATE TEMP TABLE refresh_ledger_map(sourceId TEXT PRIMARY KEY,targetId TEXT NOT NULL)")
    }

    private fun upsertMutableGraph(db: SQLiteDatabase, sourceAccountId: Long, accountId: Long) {
        db.execSQL(
            """INSERT INTO categories(name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt,lastWriterId)
               SELECT s.name,s.direction,s.color,s.icon,s.isArchived,?,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.categories s WHERE NOT EXISTS(SELECT 1 FROM categories t WHERE t.syncId=s.syncId)""",
            arrayOf(accountId),
        )
        map(db, "refresh_category_map", "categories")
        db.execSQL(
            """UPDATE categories SET
               name=(SELECT s.name FROM team_source.categories s WHERE s.syncId=categories.syncId),
               direction=(SELECT s.direction FROM team_source.categories s WHERE s.syncId=categories.syncId),
               color=(SELECT s.color FROM team_source.categories s WHERE s.syncId=categories.syncId),
               icon=(SELECT s.icon FROM team_source.categories s WHERE s.syncId=categories.syncId),
               isArchived=(SELECT s.isArchived FROM team_source.categories s WHERE s.syncId=categories.syncId),
               revision=(SELECT s.revision FROM team_source.categories s WHERE s.syncId=categories.syncId),
               updatedAt=(SELECT s.updatedAt FROM team_source.categories s WHERE s.syncId=categories.syncId),
               lastWriterId=(SELECT s.lastWriterId FROM team_source.categories s WHERE s.syncId=categories.syncId)
               WHERE accountId=? AND syncId IN (SELECT syncId FROM team_source.categories)""",
            arrayOf(accountId),
        )

        db.execSQL(
            """INSERT INTO portfolios(name,cadence,intervalCount,plannedIncome,rolloverEnabled,fundingPriority,startEpochDay,endMode,endValue,isPaused,isArchived,archivedAt,createdAt,accountId,syncId,revision,updatedAt,lastWriterId)
               SELECT s.name,s.cadence,s.intervalCount,s.plannedIncome,s.rolloverEnabled,s.fundingPriority,s.startEpochDay,s.endMode,s.endValue,s.isPaused,s.isArchived,s.archivedAt,s.createdAt,?,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.portfolios s WHERE NOT EXISTS(SELECT 1 FROM portfolios t WHERE t.syncId=s.syncId)""",
            arrayOf(accountId),
        )
        map(db, "refresh_portfolio_map", "portfolios")
        updateColumns(db, "portfolios", listOf("name", "cadence", "intervalCount", "plannedIncome", "rolloverEnabled", "fundingPriority", "startEpochDay", "endMode", "endValue", "isPaused", "isArchived", "archivedAt", "revision", "updatedAt", "lastWriterId"), accountId)

        db.execSQL(
            """INSERT INTO budget_periods(portfolioId,startEpochDay,endEpochDay,status,createdAt,syncId,revision,updatedAt,lastWriterId)
               SELECT pm.targetId,s.startEpochDay,s.endEpochDay,s.status,s.createdAt,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.budget_periods s JOIN refresh_portfolio_map pm ON pm.sourceId=s.portfolioId
               WHERE NOT EXISTS(SELECT 1 FROM budget_periods t WHERE t.syncId=s.syncId)""",
        )
        map(db, "refresh_period_map", "budget_periods")
        updateColumns(db, "budget_periods", listOf("startEpochDay", "endEpochDay", "status", "revision", "updatedAt", "lastWriterId"), null)

        db.execSQL(
            """INSERT INTO allocations(periodId,categoryId,fundingChannel,plannedAmount,syncId,revision,updatedAt,lastWriterId)
               SELECT pm.targetId,cm.targetId,s.fundingChannel,s.plannedAmount,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.allocations s JOIN refresh_period_map pm ON pm.sourceId=s.periodId
               JOIN refresh_category_map cm ON cm.sourceId=s.categoryId
               WHERE NOT EXISTS(SELECT 1 FROM allocations t WHERE t.syncId=s.syncId)""",
        )
        map(db, "refresh_allocation_map", "allocations")
        updateColumns(db, "allocations", listOf("fundingChannel", "plannedAmount", "revision", "updatedAt", "lastWriterId"), null)

        db.execSQL(
            """INSERT INTO portfolio_allocation_templates(portfolioId,categoryId,plannedAmount,cashPercentage,syncId,revision,updatedAt,lastWriterId)
               SELECT pm.targetId,cm.targetId,s.plannedAmount,s.cashPercentage,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.portfolio_allocation_templates s JOIN refresh_portfolio_map pm ON pm.sourceId=s.portfolioId
               JOIN refresh_category_map cm ON cm.sourceId=s.categoryId
               WHERE NOT EXISTS(SELECT 1 FROM portfolio_allocation_templates t WHERE t.syncId=s.syncId)""",
        )
        updateColumns(db, "portfolio_allocation_templates", listOf("plannedAmount", "cashPercentage", "revision", "updatedAt", "lastWriterId"), null)

        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.recurring_rules s JOIN recurring_rules t ON t.id=s.id WHERE t.syncId<>s.syncId",
            null,
            "ID automation Team bertabrakan",
        )
        db.execSQL(
            """INSERT INTO recurring_rules(id,title,direction,amount,accountId,fundingChannel,categoryId,allocationId,cadence,intervalCount,anchorMonth,anchorDay,startEpochDay,nextEpochDay,endEpochDay,remainingOccurrences,isPaused,pausedByArchive,createdAt,syncId,revision,updatedAt,lastWriterId)
               SELECT s.id,s.title,s.direction,s.amount,?,s.fundingChannel,cm.targetId,am.targetId,s.cadence,s.intervalCount,s.anchorMonth,s.anchorDay,s.startEpochDay,s.nextEpochDay,s.endEpochDay,s.remainingOccurrences,s.isPaused,s.pausedByArchive,s.createdAt,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.recurring_rules s LEFT JOIN refresh_category_map cm ON cm.sourceId=s.categoryId
               LEFT JOIN refresh_allocation_map am ON am.sourceId=s.allocationId
               WHERE NOT EXISTS(SELECT 1 FROM recurring_rules t WHERE t.syncId=s.syncId)""",
            arrayOf(accountId),
        )
        updateColumns(db, "recurring_rules", listOf("title", "direction", "amount", "fundingChannel", "cadence", "intervalCount", "anchorMonth", "anchorDay", "startEpochDay", "nextEpochDay", "endEpochDay", "remainingOccurrences", "isPaused", "pausedByArchive", "revision", "updatedAt", "lastWriterId"), accountId)
        db.execSQL(
            """UPDATE recurring_rules SET
               categoryId=(SELECT cm.targetId FROM team_source.recurring_rules s LEFT JOIN refresh_category_map cm ON cm.sourceId=s.categoryId WHERE s.syncId=recurring_rules.syncId),
               allocationId=(SELECT am.targetId FROM team_source.recurring_rules s LEFT JOIN refresh_allocation_map am ON am.sourceId=s.allocationId WHERE s.syncId=recurring_rules.syncId)
               WHERE accountId=? AND syncId IN (SELECT syncId FROM team_source.recurring_rules)""",
            arrayOf(accountId),
        )

        db.execSQL(
            """UPDATE accounts SET name=(SELECT name FROM team_source.accounts WHERE id=?),
               revision=(SELECT revision FROM team_source.accounts WHERE id=?),
               updatedAt=(SELECT updatedAt FROM team_source.accounts WHERE id=?),
               lastWriterId=(SELECT lastWriterId FROM team_source.accounts WHERE id=?) WHERE id=?""",
            arrayOf(sourceAccountId, sourceAccountId, sourceAccountId, sourceAccountId, accountId),
        )
    }

    private fun appendEvents(db: SQLiteDatabase, sourceAccountId: Long, accountId: Long) {
        db.execSQL(
            """INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId,accountId)
               SELECT s.id,s.type,s.title,s.note,s.source,s.effectiveEpochDay,s.createdAt,s.relatedEventId,s.reversedByEventId,am.targetId,?
               FROM team_source.activity_events s LEFT JOIN refresh_allocation_map am ON am.sourceId=s.targetAllocationId
               WHERE NOT EXISTS(SELECT 1 FROM activity_events t WHERE t.id=s.id)""",
            arrayOf(accountId),
        )
        db.execSQL("INSERT INTO cash_journal_lines(eventId,accountId,fundingChannel,amount) SELECT s.eventId,?,s.fundingChannel,s.amount FROM team_source.cash_journal_lines s WHERE NOT EXISTS(SELECT 1 FROM cash_journal_lines t WHERE t.eventId=s.eventId)", arrayOf(accountId))
        db.execSQL("""INSERT INTO budget_journal_lines(eventId,allocationId,bucket,fundingChannel,amount,accountId)
            SELECT s.eventId,am.targetId,s.bucket,s.fundingChannel,s.amount,? FROM team_source.budget_journal_lines s
            LEFT JOIN refresh_allocation_map am ON am.sourceId=s.allocationId
            WHERE NOT EXISTS(SELECT 1 FROM budget_journal_lines t WHERE t.eventId=s.eventId)""", arrayOf(accountId))
        db.execSQL("""INSERT INTO transaction_splits(eventId,categoryId,allocationId,amount)
            SELECT s.eventId,cm.targetId,am.targetId,s.amount FROM team_source.transaction_splits s
            LEFT JOIN refresh_category_map cm ON cm.sourceId=s.categoryId LEFT JOIN refresh_allocation_map am ON am.sourceId=s.allocationId
            WHERE NOT EXISTS(SELECT 1 FROM transaction_splits t WHERE t.eventId=s.eventId)""")
        db.execSQL("INSERT INTO recurring_occurrences(ruleId,dueEpochDay,eventId,createdAt) SELECT s.ruleId,s.dueEpochDay,s.eventId,s.createdAt FROM team_source.recurring_occurrences s WHERE NOT EXISTS(SELECT 1 FROM recurring_occurrences t WHERE t.ruleId=s.ruleId AND t.dueEpochDay=s.dueEpochDay)")
        db.execSQL("INSERT INTO audit_snapshots(eventId,reason,beforeJson,afterJson) SELECT s.eventId,s.reason,s.beforeJson,s.afterJson FROM team_source.audit_snapshots s WHERE NOT EXISTS(SELECT 1 FROM audit_snapshots t WHERE t.eventId=s.eventId)")
        db.execSQL("""INSERT INTO receipts(eventId,localPath,storageId,displayName,mimeType,byteSize,sha256,encryptionNonce,encryptionVersion,createdAt,capturedAt,latitude,longitude,origin,evidenceEventId)
            SELECT s.eventId,NULL,s.storageId,s.displayName,s.mimeType,s.byteSize,s.sha256,s.encryptionNonce,s.encryptionVersion,s.createdAt,s.capturedAt,s.latitude,s.longitude,s.origin,s.evidenceEventId
            FROM team_source.receipts s WHERE NOT EXISTS(SELECT 1 FROM receipts t WHERE t.storageId=s.storageId)""")
        importEvidenceAndLedger(db, sourceAccountId, accountId)
        db.execSQL("""INSERT INTO team_event_proofs(eventId,teamId,chainId,sequence,previousChainHash,payloadHash,chainHash,signatureBase64,recordedAtUtc,deviceId,actor,appVersion,keyId,canonicalVersion)
            SELECT s.eventId,s.teamId,s.chainId,s.sequence,s.previousChainHash,s.payloadHash,s.chainHash,s.signatureBase64,s.recordedAtUtc,s.deviceId,s.actor,s.appVersion,km.targetId,s.canonicalVersion
            FROM team_source.team_event_proofs s JOIN refresh_key_map km ON km.sourceId=s.keyId
            WHERE NOT EXISTS(SELECT 1 FROM team_event_proofs t WHERE t.eventId=s.eventId)""")
        db.execSQL("INSERT OR IGNORE INTO team_invitation_uses(inviteIdHash,teamId,usedAt) SELECT inviteIdHash,teamId,usedAt FROM team_source.team_invitation_uses")
    }

    private fun importEvidenceAndLedger(db: SQLiteDatabase, sourceAccountId: Long, accountId: Long) {
        db.execSQL("""INSERT INTO evidence_keys(id,alias,algorithm,publicKeyBase64,certificateBase64,fingerprint,securityLevel,createdAt,retiredAt)
            SELECT s.id,s.alias,s.algorithm,s.publicKeyBase64,s.certificateBase64,s.fingerprint,s.securityLevel,s.createdAt,s.retiredAt
            FROM team_source.evidence_keys s WHERE NOT EXISTS(SELECT 1 FROM evidence_keys t WHERE t.fingerprint=s.fingerprint)""")
        db.execSQL("INSERT INTO refresh_key_map SELECT s.id,t.id FROM team_source.evidence_keys s JOIN evidence_keys t ON t.fingerprint=s.fingerprint")

        db.execSQL("INSERT INTO refresh_ledger_map SELECT s.id,t.id FROM team_source.ledger_accounts s JOIN ledger_accounts t ON s.accountId IS NULL AND s.categoryId IS NULL AND t.id=s.id")
        db.execSQL("INSERT OR IGNORE INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt) SELECT 'asset:'||?||':'||s.fundingChannel,'1'||printf('%06d',?)||CASE WHEN s.fundingChannel='CASH' THEN '01' ELSE '02' END,s.name,s.kind,?,s.fundingChannel,NULL,s.createdAt FROM team_source.ledger_accounts s WHERE s.accountId=?", arrayOf(accountId, accountId, accountId, sourceAccountId))
        db.execSQL("INSERT INTO refresh_ledger_map SELECT s.id,t.id FROM team_source.ledger_accounts s JOIN ledger_accounts t ON t.accountId=? AND t.fundingChannel=s.fundingChannel WHERE s.accountId=?", arrayOf(accountId, sourceAccountId))
        db.execSQL("INSERT OR IGNORE INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt) SELECT lower(s.kind)||':category:'||cm.targetId,CASE s.kind WHEN 'INCOME' THEN '4' ELSE '5' END||printf('%06d',cm.targetId),s.name,s.kind,NULL,NULL,cm.targetId,s.createdAt FROM team_source.ledger_accounts s JOIN refresh_category_map cm ON cm.sourceId=s.categoryId WHERE s.categoryId IS NOT NULL")
        db.execSQL("INSERT INTO refresh_ledger_map SELECT s.id,t.id FROM team_source.ledger_accounts s JOIN refresh_category_map cm ON cm.sourceId=s.categoryId JOIN ledger_accounts t ON t.categoryId=cm.targetId AND t.kind=s.kind WHERE s.categoryId IS NOT NULL")
        db.execSQL("""INSERT INTO ledger_lines(eventId,ledgerAccountId,side,amount,accountId,fundingChannel,categoryId,correlationId,legacyBackfill)
            SELECT s.eventId,lm.targetId,s.side,s.amount,CASE WHEN s.accountId IS NULL THEN NULL ELSE ? END,s.fundingChannel,cm.targetId,s.correlationId,s.legacyBackfill
            FROM team_source.ledger_lines s JOIN refresh_ledger_map lm ON lm.sourceId=s.ledgerAccountId
            LEFT JOIN refresh_category_map cm ON cm.sourceId=s.categoryId
            WHERE NOT EXISTS(SELECT 1 FROM ledger_lines t WHERE t.eventId=s.eventId)""", arrayOf(accountId))
    }

    private fun updateColumns(db: SQLiteDatabase, table: String, columns: List<String>, accountId: Long?) {
        val assignments = columns.joinToString(",") { column -> "$column=(SELECT s.$column FROM team_source.$table s WHERE s.syncId=$table.syncId)" }
        val accountPredicate = if (accountId == null) "" else " AND accountId=$accountId"
        db.execSQL("UPDATE $table SET $assignments WHERE syncId IN (SELECT syncId FROM team_source.$table)$accountPredicate")
    }

    private fun map(db: SQLiteDatabase, mapping: String, table: String) {
        db.execSQL("INSERT INTO $mapping SELECT s.id,t.id FROM team_source.$table s JOIN $table t ON t.syncId=s.syncId")
        require(scalar(db, "SELECT COUNT(*) FROM $mapping") == scalar(db, "SELECT COUNT(*) FROM team_source.$table")) {
            "Pemetaan $table hasil sync Team tidak lengkap"
        }
    }

    private fun validateFinancialInvariants(db: SQLiteDatabase) {
        requireZero(db, "SELECT COUNT(*) FROM (SELECT eventId FROM ledger_lines GROUP BY eventId HAVING SUM(CASE WHEN side='DEBIT' THEN amount ELSE -amount END)<>0)", null, "General ledger hasil sync Team tidak seimbang")
        requireZero(db, "SELECT COUNT(*) FROM (SELECT eventId FROM budget_journal_lines GROUP BY eventId HAVING SUM(amount)<>0)", null, "Subledger budget hasil sync Team tidak seimbang")
    }

    private fun requireZero(db: SQLiteDatabase, sql: String, args: Array<String>?, message: String) {
        require(scalar(db, sql, args) == 0L) { message }
    }

    private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        db.rawQuery(sql, args).use { cursor -> require(cursor.moveToFirst()); cursor.getLong(0) }
}
