package com.morneven.kron.backup

import android.database.sqlite.SQLiteDatabase
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.TeamRole
import java.io.File

internal data class TeamImportMetadata(
    val teamId: String,
    val folderId: String,
    val liveFileId: String? = null,
    val localRole: String,
    val headSnapshotId: String?,
    val generation: Long,
    val inviteIdHash: String?,
    val importedAt: Long,
    val activateImported: Boolean = true,
    val workspaceStatus: String = "SYNCED",
)

internal object TeamGraphImporter {
    fun merge(target: File, source: File, metadata: TeamImportMetadata): Long {
        require(target.isFile && source.isFile && target.canonicalFile != source.canonicalFile) {
            "Database import Team tidak valid"
        }
        require(metadata.teamId.isNotBlank() && metadata.folderId.isNotBlank()) {
            "Metadata import Team tidak valid"
        }
        require(metadata.localRole in setOf(TeamRole.OWNER, TeamRole.EDITOR, TeamRole.VIEWER)) {
            "Role import Team tidak valid"
        }
        require(metadata.generation >= 0 && metadata.importedAt > 0 &&
            (metadata.inviteIdHash == null || metadata.inviteIdHash.matches(Regex("[0-9a-f]{64}")))
        ) {
            "Metadata import Team tidak valid"
        }
        val sourceScope = TeamSnapshotPruner.validateImported(source, metadata.teamId)
        require(sourceScope.generation == metadata.generation) { "Generation import Team tidak cocok" }

        return SQLiteDatabase.openDatabase(target.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            require(scalar(db, "PRAGMA user_version") == KronDatabase.SCHEMA_VERSION.toLong()) {
                "Schema database target Team tidak sesuai"
            }
            db.execSQL("PRAGMA foreign_keys=ON")
            db.execSQL("ATTACH DATABASE ? AS team_source", arrayOf(source.absolutePath))
            try {
                db.beginTransaction()
                try {
                    validateCollisions(db, metadata)
                    val accountId = insertGraph(db, sourceScope.accountId, metadata)
                    require(!db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) {
                        "Relasi hasil import Team tidak valid"
                    }
                    validateFinancialInvariants(db)
                    db.setTransactionSuccessful()
                    accountId
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.execSQL("DETACH DATABASE team_source")
            }
        }
    }

    private fun validateCollisions(db: SQLiteDatabase, metadata: TeamImportMetadata) {
        requireZero(db, "SELECT COUNT(*) FROM accounts WHERE teamId=?", arrayOf(metadata.teamId), "Team sudah ada")
        requireZero(db, "SELECT COUNT(*) FROM team_workspaces WHERE folderId=?", arrayOf(metadata.folderId), "Workspace Team sudah ada")
        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.team_workspaces WHERE folderId<>?",
            arrayOf(metadata.folderId),
            "Folder snapshot Team tidak cocok",
        )
        listOf("categories", "portfolios", "budget_periods", "allocations", "portfolio_allocation_templates").forEach { table ->
            requireZero(
                db,
                "SELECT COUNT(*) FROM team_source.$table s WHERE s.syncId='' OR EXISTS(SELECT 1 FROM main.$table t WHERE t.syncId=s.syncId)",
                null,
                "Sync ID $table bertabrakan",
            )
        }
        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.recurring_rules s WHERE s.syncId='' OR EXISTS(SELECT 1 FROM recurring_rules t WHERE t.id=s.id OR t.syncId=s.syncId)",
            null,
            "Automation Team bertabrakan",
        )
        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.activity_events s JOIN activity_events t ON t.id=s.id",
            null,
            "UUID event Team bertabrakan",
        )
        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.receipts s JOIN receipts t ON t.storageId=s.storageId",
            null,
            "Storage ID bukti Team bertabrakan",
        )
        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.team_invitation_uses s JOIN team_invitation_uses t ON t.inviteIdHash=s.inviteIdHash",
            null,
            "Riwayat undangan Team bertabrakan",
        )
        metadata.inviteIdHash?.let { inviteIdHash ->
            requireZero(
                db,
                "SELECT COUNT(*) FROM team_invitation_uses WHERE inviteIdHash=?",
                arrayOf(inviteIdHash),
                "Kode akses Team sudah pernah digunakan",
            )
        }
        requireZero(
            db,
            "SELECT COUNT(*) FROM team_source.team_event_proofs s JOIN team_event_proofs t ON t.chainId=s.chainId AND t.sequence=s.sequence",
            null,
            "Rantai bukti Team bertabrakan",
        )
        requireZero(
            db,
            """SELECT COUNT(*) FROM team_source.evidence_keys s JOIN evidence_keys t ON t.id=s.id
               WHERE t.fingerprint<>s.fingerprint""",
            null,
            "ID kunci bukti Team bertabrakan",
        )
        requireZero(
            db,
            """SELECT COUNT(*) FROM team_source.evidence_keys s JOIN evidence_keys t ON t.fingerprint=s.fingerprint
               WHERE t.algorithm<>s.algorithm OR t.publicKeyBase64<>s.publicKeyBase64 OR t.certificateBase64<>s.certificateBase64""",
            null,
            "Fingerprint kunci bukti Team tidak cocok",
        )
    }

    private fun insertGraph(db: SQLiteDatabase, sourceAccountId: Long, metadata: TeamImportMetadata): Long {
        if (metadata.activateImported) db.execSQL("UPDATE accounts SET isActive=0")
        db.execSQL(
            """INSERT INTO accounts(name,isActive,isArchived,archivedAt,createdAt,sharingMode,teamId,revision,updatedAt,lastWriterId)
               SELECT name,?,0,NULL,createdAt,'TEAM',teamId,revision,updatedAt,lastWriterId
               FROM team_source.accounts WHERE id=?""",
            arrayOf(if (metadata.activateImported) 1 else 0, sourceAccountId),
        )
        val accountId = scalar(db, "SELECT last_insert_rowid()")
        require(accountId > 0) { "Akun hasil import Team tidak terbentuk" }

        createMappings(db)
        db.execSQL(
            """INSERT INTO categories(name,direction,color,icon,isArchived,accountId,syncId,revision,updatedAt,lastWriterId)
               SELECT name,direction,color,icon,isArchived,?,syncId,revision,updatedAt,lastWriterId FROM team_source.categories""",
            arrayOf(accountId),
        )
        fillMapping(db, "team_category_map", "categories")

        db.execSQL(
            """INSERT INTO portfolios(name,cadence,intervalCount,plannedIncome,rolloverEnabled,fundingPriority,startEpochDay,endMode,endValue,isPaused,isArchived,archivedAt,createdAt,accountId,syncId,revision,updatedAt,lastWriterId)
               SELECT name,cadence,intervalCount,plannedIncome,rolloverEnabled,fundingPriority,startEpochDay,endMode,endValue,isPaused,isArchived,archivedAt,createdAt,?,syncId,revision,updatedAt,lastWriterId
               FROM team_source.portfolios""",
            arrayOf(accountId),
        )
        fillMapping(db, "team_portfolio_map", "portfolios")

        db.execSQL(
            """INSERT INTO budget_periods(portfolioId,startEpochDay,endEpochDay,status,createdAt,syncId,revision,updatedAt,lastWriterId)
               SELECT pm.targetId,s.startEpochDay,s.endEpochDay,s.status,s.createdAt,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.budget_periods s JOIN team_portfolio_map pm ON pm.sourceId=s.portfolioId""",
        )
        fillMapping(db, "team_period_map", "budget_periods")

        db.execSQL(
            """INSERT INTO allocations(periodId,categoryId,fundingChannel,plannedAmount,syncId,revision,updatedAt,lastWriterId)
               SELECT pm.targetId,cm.targetId,s.fundingChannel,s.plannedAmount,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.allocations s
               JOIN team_period_map pm ON pm.sourceId=s.periodId
               JOIN team_category_map cm ON cm.sourceId=s.categoryId""",
        )
        fillMapping(db, "team_allocation_map", "allocations")

        db.execSQL(
            """INSERT INTO portfolio_allocation_templates(portfolioId,categoryId,plannedAmount,cashPercentage,syncId,revision,updatedAt,lastWriterId)
               SELECT pm.targetId,cm.targetId,s.plannedAmount,s.cashPercentage,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.portfolio_allocation_templates s
               JOIN team_portfolio_map pm ON pm.sourceId=s.portfolioId
               JOIN team_category_map cm ON cm.sourceId=s.categoryId""",
        )

        db.execSQL(
            """INSERT INTO recurring_rules(id,title,direction,amount,accountId,fundingChannel,categoryId,allocationId,cadence,intervalCount,anchorMonth,anchorDay,startEpochDay,nextEpochDay,endEpochDay,remainingOccurrences,isPaused,pausedByArchive,createdAt,syncId,revision,updatedAt,lastWriterId)
               SELECT s.id,s.title,s.direction,s.amount,?,s.fundingChannel,cm.targetId,am.targetId,s.cadence,s.intervalCount,s.anchorMonth,s.anchorDay,s.startEpochDay,s.nextEpochDay,s.endEpochDay,s.remainingOccurrences,s.isPaused,s.pausedByArchive,s.createdAt,s.syncId,s.revision,s.updatedAt,s.lastWriterId
               FROM team_source.recurring_rules s
               LEFT JOIN team_category_map cm ON cm.sourceId=s.categoryId
               LEFT JOIN team_allocation_map am ON am.sourceId=s.allocationId""",
            arrayOf(accountId),
        )

        db.execSQL(
            """INSERT INTO activity_events(id,type,title,note,source,effectiveEpochDay,createdAt,relatedEventId,reversedByEventId,targetAllocationId,accountId)
               SELECT s.id,s.type,s.title,s.note,s.source,s.effectiveEpochDay,s.createdAt,s.relatedEventId,s.reversedByEventId,am.targetId,?
               FROM team_source.activity_events s LEFT JOIN team_allocation_map am ON am.sourceId=s.targetAllocationId""",
            arrayOf(accountId),
        )
        db.execSQL(
            """INSERT INTO cash_journal_lines(eventId,accountId,fundingChannel,amount)
               SELECT eventId,?,fundingChannel,amount FROM team_source.cash_journal_lines""",
            arrayOf(accountId),
        )
        db.execSQL(
            """INSERT INTO budget_journal_lines(eventId,allocationId,bucket,fundingChannel,amount,accountId)
               SELECT s.eventId,am.targetId,s.bucket,s.fundingChannel,s.amount,?
               FROM team_source.budget_journal_lines s LEFT JOIN team_allocation_map am ON am.sourceId=s.allocationId""",
            arrayOf(accountId),
        )
        db.execSQL(
            """INSERT INTO transaction_splits(eventId,categoryId,allocationId,amount)
               SELECT s.eventId,cm.targetId,am.targetId,s.amount FROM team_source.transaction_splits s
               LEFT JOIN team_category_map cm ON cm.sourceId=s.categoryId
               LEFT JOIN team_allocation_map am ON am.sourceId=s.allocationId""",
        )
        db.execSQL(
            """INSERT INTO recurring_occurrences(ruleId,dueEpochDay,eventId,createdAt)
               SELECT ruleId,dueEpochDay,eventId,createdAt FROM team_source.recurring_occurrences""",
        )
        db.execSQL(
            """INSERT INTO audit_snapshots(eventId,reason,beforeJson,afterJson)
               SELECT eventId,reason,beforeJson,afterJson FROM team_source.audit_snapshots""",
        )
        db.execSQL(
            """INSERT INTO receipts(eventId,localPath,storageId,displayName,mimeType,byteSize,sha256,encryptionNonce,encryptionVersion,createdAt,capturedAt,latitude,longitude,origin,evidenceEventId)
               SELECT eventId,NULL,storageId,displayName,mimeType,byteSize,sha256,encryptionNonce,encryptionVersion,createdAt,capturedAt,latitude,longitude,origin,evidenceEventId
               FROM team_source.receipts""",
        )

        importEvidenceKeys(db)
        importLedger(db, sourceAccountId, accountId)
        db.execSQL(
            """INSERT INTO team_event_proofs(eventId,teamId,chainId,sequence,previousChainHash,payloadHash,chainHash,signatureBase64,recordedAtUtc,deviceId,actor,appVersion,keyId,canonicalVersion)
               SELECT s.eventId,s.teamId,s.chainId,s.sequence,s.previousChainHash,s.payloadHash,s.chainHash,s.signatureBase64,s.recordedAtUtc,s.deviceId,s.actor,s.appVersion,km.targetId,s.canonicalVersion
               FROM team_source.team_event_proofs s JOIN team_evidence_key_map km ON km.sourceId=s.keyId""",
        )
        db.execSQL(
            """INSERT INTO team_invitation_uses(inviteIdHash,teamId,usedAt)
               SELECT inviteIdHash,teamId,usedAt FROM team_source.team_invitation_uses""",
        )
        metadata.inviteIdHash?.let { inviteIdHash ->
            db.execSQL(
                "INSERT INTO team_invitation_uses(inviteIdHash,teamId,usedAt) VALUES(?,?,?)",
                arrayOf<Any?>(inviteIdHash, metadata.teamId, metadata.importedAt),
            )
        }
        db.execSQL(
            """INSERT INTO team_workspaces(accountId,teamId,folderId,localRole,ownerSubjectHash,liveFileId,headSnapshotId,generation,status,canRead,canWrite,canShare,capabilitiesVerifiedAt,archivedAt,updatedAt)
               SELECT ?,teamId,?, ?,ownerSubjectHash,?,?,?, ?,1,?,?,?,NULL,?
               FROM team_source.team_workspaces WHERE accountId=?""",
            arrayOf<Any?>(
                accountId,
                metadata.folderId,
                metadata.localRole,
                metadata.liveFileId,
                metadata.headSnapshotId,
                metadata.generation,
                metadata.workspaceStatus,
                if (metadata.localRole == TeamRole.VIEWER) 0 else 1,
                if (metadata.localRole == TeamRole.OWNER) 1 else 0,
                metadata.importedAt,
                metadata.importedAt,
                sourceAccountId,
            ),
        )
        require(scalar(db, "SELECT COUNT(*) FROM team_workspaces WHERE accountId=$accountId") == 1L) {
            "Workspace hasil import Team tidak terbentuk"
        }
        return accountId
    }

    private fun createMappings(db: SQLiteDatabase) {
        listOf("category", "portfolio", "period", "allocation").forEach { name ->
            db.execSQL("CREATE TEMP TABLE team_${name}_map(sourceId INTEGER PRIMARY KEY,targetId INTEGER NOT NULL UNIQUE)")
        }
        db.execSQL("CREATE TEMP TABLE team_evidence_key_map(sourceId TEXT PRIMARY KEY,targetId TEXT NOT NULL)")
        db.execSQL("CREATE TEMP TABLE team_ledger_account_map(sourceId TEXT PRIMARY KEY,targetId TEXT NOT NULL)")
    }

    private fun fillMapping(db: SQLiteDatabase, mapping: String, table: String) {
        db.execSQL(
            "INSERT INTO $mapping(sourceId,targetId) SELECT s.id,t.id FROM team_source.$table s JOIN main.$table t ON t.syncId=s.syncId",
        )
        require(scalar(db, "SELECT COUNT(*) FROM $mapping") == scalar(db, "SELECT COUNT(*) FROM team_source.$table")) {
            "Pemetaan $table hasil import Team tidak lengkap"
        }
    }

    private fun importEvidenceKeys(db: SQLiteDatabase) {
        db.execSQL(
            """INSERT INTO evidence_keys(id,alias,algorithm,publicKeyBase64,certificateBase64,fingerprint,securityLevel,createdAt,retiredAt)
               SELECT s.id,s.alias,s.algorithm,s.publicKeyBase64,s.certificateBase64,s.fingerprint,s.securityLevel,s.createdAt,s.retiredAt
               FROM team_source.evidence_keys s WHERE NOT EXISTS(SELECT 1 FROM evidence_keys t WHERE t.fingerprint=s.fingerprint)""",
        )
        db.execSQL(
            """INSERT INTO team_evidence_key_map(sourceId,targetId)
               SELECT s.id,t.id FROM team_source.evidence_keys s JOIN evidence_keys t ON t.fingerprint=s.fingerprint""",
        )
        require(
            scalar(db, "SELECT COUNT(*) FROM team_evidence_key_map") ==
                scalar(db, "SELECT COUNT(*) FROM team_source.evidence_keys"),
        ) { "Pemetaan kunci bukti Team tidak lengkap" }
    }

    private fun importLedger(db: SQLiteDatabase, sourceAccountId: Long, accountId: Long) {
        requireZero(
            db,
            """SELECT COUNT(*) FROM team_source.ledger_accounts
               WHERE (accountId IS NOT NULL AND accountId<>?)
                  OR (accountId IS NOT NULL AND categoryId IS NOT NULL)
                  OR (categoryId IS NOT NULL AND categoryId NOT IN (SELECT id FROM team_source.categories))
                  OR (categoryId IS NOT NULL AND kind NOT IN ('INCOME','EXPENSE'))
                  OR (accountId IS NOT NULL AND (kind<>'ASSET' OR fundingChannel NOT IN ('CASH','EBUDGET')))""",
            arrayOf(sourceAccountId.toString()),
            "Scope akun buku besar Team tidak valid",
        )
        requireZero(
            db,
            """SELECT COUNT(*) FROM team_source.ledger_accounts s JOIN ledger_accounts t ON t.id=s.id
               WHERE s.accountId IS NULL AND s.categoryId IS NULL
                 AND (t.code<>s.code OR t.kind<>s.kind OR t.accountId IS NOT NULL OR t.categoryId IS NOT NULL)""",
            null,
            "Akun buku besar umum bertabrakan",
        )
        db.execSQL(
            """INSERT INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt)
               SELECT s.id,s.code,s.name,s.kind,NULL,s.fundingChannel,NULL,s.createdAt
               FROM team_source.ledger_accounts s
               WHERE s.accountId IS NULL AND s.categoryId IS NULL
                 AND NOT EXISTS(SELECT 1 FROM ledger_accounts t WHERE t.id=s.id)""",
        )
        db.execSQL(
            """INSERT INTO team_ledger_account_map(sourceId,targetId)
               SELECT s.id,t.id FROM team_source.ledger_accounts s JOIN ledger_accounts t ON t.id=s.id
               WHERE s.accountId IS NULL AND s.categoryId IS NULL""",
        )
        db.execSQL(
            """INSERT INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt)
               SELECT 'asset:'||?||':'||s.fundingChannel,
                      '1'||printf('%06d',?)||CASE WHEN s.fundingChannel='CASH' THEN '01' ELSE '02' END,
                      s.name,s.kind,?,s.fundingChannel,NULL,s.createdAt
               FROM team_source.ledger_accounts s WHERE s.accountId=?""",
            arrayOf(accountId, accountId, accountId, sourceAccountId),
        )
        db.execSQL(
            """INSERT INTO team_ledger_account_map(sourceId,targetId)
               SELECT s.id,t.id FROM team_source.ledger_accounts s
               JOIN ledger_accounts t ON t.accountId=? AND t.fundingChannel=s.fundingChannel
               WHERE s.accountId=?""",
            arrayOf(accountId, sourceAccountId),
        )
        db.execSQL(
            """INSERT INTO ledger_accounts(id,code,name,kind,accountId,fundingChannel,categoryId,createdAt)
               SELECT lower(s.kind)||':category:'||cm.targetId,
                      CASE s.kind WHEN 'INCOME' THEN '4' ELSE '5' END||printf('%06d',cm.targetId),
                      s.name,s.kind,NULL,NULL,cm.targetId,s.createdAt
               FROM team_source.ledger_accounts s JOIN team_category_map cm ON cm.sourceId=s.categoryId
               WHERE s.accountId IS NULL AND s.categoryId IS NOT NULL""",
        )
        db.execSQL(
            """INSERT INTO team_ledger_account_map(sourceId,targetId)
               SELECT s.id,t.id FROM team_source.ledger_accounts s
               JOIN team_category_map cm ON cm.sourceId=s.categoryId
               JOIN ledger_accounts t ON t.categoryId=cm.targetId AND t.kind=s.kind
               WHERE s.accountId IS NULL AND s.categoryId IS NOT NULL""",
        )
        require(
            scalar(db, "SELECT COUNT(*) FROM team_ledger_account_map") ==
                scalar(db, "SELECT COUNT(*) FROM team_source.ledger_accounts"),
        ) { "Pemetaan buku besar Team tidak lengkap" }
        db.execSQL(
            """INSERT INTO ledger_lines(eventId,ledgerAccountId,side,amount,accountId,fundingChannel,categoryId,correlationId,legacyBackfill)
               SELECT s.eventId,lm.targetId,s.side,s.amount,
                      CASE WHEN s.accountId IS NULL THEN NULL ELSE ? END,
                      s.fundingChannel,cm.targetId,s.correlationId,s.legacyBackfill
               FROM team_source.ledger_lines s
               JOIN team_ledger_account_map lm ON lm.sourceId=s.ledgerAccountId
               LEFT JOIN team_category_map cm ON cm.sourceId=s.categoryId""",
            arrayOf(accountId),
        )
    }

    private fun requireZero(
        db: SQLiteDatabase,
        sql: String,
        args: Array<String>?,
        message: String,
    ) {
        require(scalar(db, sql, args) == 0L) { message }
    }

    private fun validateFinancialInvariants(db: SQLiteDatabase) {
        requireZero(
            db,
            """SELECT COUNT(*) FROM (
                   SELECT eventId,
                          SUM(CASE WHEN side='DEBIT' THEN amount ELSE 0 END) debit,
                          SUM(CASE WHEN side='CREDIT' THEN amount ELSE 0 END) credit
                   FROM ledger_lines GROUP BY eventId HAVING debit<>credit
               )""",
            null,
            "General ledger hasil import Team tidak seimbang",
        )
        requireZero(
            db,
            "SELECT COUNT(*) FROM ledger_lines WHERE amount<=0 OR side NOT IN ('DEBIT','CREDIT')",
            null,
            "Baris general ledger hasil import Team tidak valid",
        )
        requireZero(
            db,
            "SELECT COUNT(*) FROM (SELECT eventId FROM budget_journal_lines GROUP BY eventId HAVING SUM(amount)<>0)",
            null,
            "Subledger budget hasil import Team tidak seimbang",
        )
        requireZero(
            db,
            """SELECT COUNT(*) FROM (
                   SELECT eventId,accountId,fundingChannel FROM budget_journal_lines
                   GROUP BY eventId,accountId,fundingChannel HAVING SUM(amount)<>0
               )""",
            null,
            "Subledger budget per akun hasil import Team tidak seimbang",
        )
        val accountIds = db.rawQuery("SELECT id FROM accounts", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }
        accountIds.forEach { accountId ->
            listOf("CASH", "EBUDGET").forEach { channel ->
                val cash = scalar(
                    db,
                    "SELECT COALESCE(SUM(amount),0) FROM cash_journal_lines WHERE accountId=? AND fundingChannel=?",
                    arrayOf(accountId.toString(), channel),
                )
                val available = scalar(
                    db,
                    """SELECT COALESCE(SUM(amount),0) FROM budget_journal_lines
                       WHERE accountId=? AND fundingChannel=?
                         AND (bucket IN ('VAULT','UNALLOCATED','UNEXPECTED','ROLLOVER') OR allocationId IS NOT NULL)""",
                    arrayOf(accountId.toString(), channel),
                )
                require(cash == available) { "Invariant aset hasil import Team tidak seimbang" }
            }
        }
    }

    private fun scalar(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        db.rawQuery(sql, args).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
