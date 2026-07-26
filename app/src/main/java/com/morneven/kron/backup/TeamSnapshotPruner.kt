package com.morneven.kron.backup

import android.database.sqlite.SQLiteDatabase
import com.morneven.kron.data.KronDatabase
import java.io.File

internal data class TeamSnapshotScope(
    val accountId: Long,
    val teamId: String,
    val generation: Long,
)

internal object TeamSnapshotPruner {
    fun prune(file: File, scope: TeamSnapshotScope) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            validateGraph(db, scope)
            require(db.rawQuery("PRAGMA journal_mode=DELETE", null).use { it.moveToFirst() && it.getString(0).equals("delete", true) }) {
                "Journal staging snapshot Team tidak aman"
            }
            require(db.rawQuery("PRAGMA secure_delete=ON", null).use { it.moveToFirst() && it.getInt(0) != 0 }) {
                "Secure delete snapshot Team tidak tersedia"
            }
            db.beginTransaction()
            try {
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger'", null).use { cursor ->
                    val triggers = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    triggers.forEach { db.execSQL("DROP TRIGGER IF EXISTS `" + it.replace("`", "``") + "`") }
                }
                val accountId = scope.accountId
                db.execSQL("DELETE FROM team_event_proofs WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM journal_seals")
                db.execSQL("DELETE FROM receipts WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("UPDATE receipts SET localPath=NULL")
                db.execSQL("DELETE FROM audit_snapshots WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM transaction_splits WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM budget_journal_lines WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM cash_journal_lines WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM ledger_lines WHERE eventId NOT IN (SELECT id FROM activity_events WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM recurring_occurrences WHERE ruleId NOT IN (SELECT id FROM recurring_rules WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM recurring_rules WHERE accountId<>?", arrayOf(accountId))
                db.execSQL("DELETE FROM portfolio_allocation_templates WHERE portfolioId NOT IN (SELECT id FROM portfolios WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM allocations WHERE periodId NOT IN (SELECT p.id FROM budget_periods p JOIN portfolios f ON f.id=p.portfolioId WHERE f.accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM budget_periods WHERE portfolioId NOT IN (SELECT id FROM portfolios WHERE accountId=?)", arrayOf(accountId))
                db.execSQL("DELETE FROM portfolios WHERE accountId<>?", arrayOf(accountId))
                db.execSQL("DELETE FROM activity_events WHERE accountId<>?", arrayOf(accountId))
                db.execSQL("DELETE FROM ledger_accounts WHERE id NOT IN (SELECT DISTINCT ledgerAccountId FROM ledger_lines)")
                db.execSQL("DELETE FROM evidence_keys WHERE id NOT IN (SELECT DISTINCT keyId FROM team_event_proofs)")
                db.execSQL("DELETE FROM team_members")
                db.execSQL("DELETE FROM team_invitation_uses WHERE teamId<>?", arrayOf(scope.teamId))
                db.execSQL("DELETE FROM team_workspaces WHERE accountId<>?", arrayOf(accountId))
                db.execSQL("DELETE FROM categories WHERE accountId IS NULL OR accountId<>?", arrayOf(accountId))
                db.execSQL("DELETE FROM accounts WHERE id<>?", arrayOf(accountId))
                db.execSQL("UPDATE accounts SET isActive=1")
                db.execSQL("DELETE FROM actor_profiles")
                db.execSQL("DELETE FROM sync_state")
                db.execSQL(
                    "INSERT INTO sync_state(id,datasetId,deviceId,localGeneration,lastSyncedGeneration,status,disabledDueToBilling,updatedAt) " +
                        "VALUES(1,?,?,?,0,'DISCONNECTED',0,?)",
                    arrayOf<Any?>(scope.teamId, "team-snapshot", scope.generation, System.currentTimeMillis()),
                )
                db.execSQL("DELETE FROM sqlite_sequence")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            db.execSQL("VACUUM")
            validateSnapshot(db, scope)
        }
    }

    private fun validateGraph(db: SQLiteDatabase, scope: TeamSnapshotScope) {
        val accountId = scope.accountId.toString()
        val eventScope = "SELECT id FROM activity_events WHERE accountId=$accountId"
        fun empty(sql: String, message: String) = require(scalar(db, sql) == 0L) { message }
        empty("SELECT COUNT(*) FROM cash_journal_lines WHERE eventId IN ($eventScope) AND accountId<>$accountId", "Cash event Team menyentuh akun lain")
        empty("SELECT COUNT(*) FROM budget_journal_lines WHERE eventId IN ($eventScope) AND accountId<>$accountId", "Budget event Team menyentuh akun lain")
        empty("SELECT COUNT(*) FROM ledger_lines WHERE eventId IN ($eventScope) AND accountId IS NOT NULL AND accountId<>$accountId", "Ledger event Team menyentuh akun lain")
        empty("SELECT COUNT(*) FROM activity_events WHERE accountId=$accountId AND relatedEventId IS NOT NULL AND relatedEventId NOT IN ($eventScope)", "Relasi event Team keluar dari akun")
        empty("SELECT COUNT(*) FROM activity_events WHERE accountId=$accountId AND reversedByEventId IS NOT NULL AND reversedByEventId NOT IN ($eventScope)", "Reversal event Team keluar dari akun")
        empty("SELECT COUNT(*) FROM categories WHERE id IN (SELECT categoryId FROM transaction_splits WHERE eventId IN ($eventScope) AND categoryId IS NOT NULL) AND (accountId IS NULL OR accountId<>$accountId)", "Kategori event Team tidak scoped")
        empty("SELECT COUNT(*) FROM allocations a JOIN budget_periods p ON p.id=a.periodId JOIN portfolios f ON f.id=p.portfolioId JOIN categories c ON c.id=a.categoryId WHERE f.accountId=$accountId AND (c.accountId IS NULL OR c.accountId<>$accountId)", "Alokasi Team memakai kategori akun lain")
        empty("SELECT COUNT(*) FROM portfolio_allocation_templates t JOIN portfolios f ON f.id=t.portfolioId JOIN categories c ON c.id=t.categoryId WHERE f.accountId=$accountId AND (c.accountId IS NULL OR c.accountId<>$accountId)", "Template Team memakai kategori akun lain")
        empty("SELECT COUNT(*) FROM recurring_rules WHERE accountId=$accountId AND categoryId IS NOT NULL AND categoryId NOT IN (SELECT id FROM categories WHERE accountId=$accountId)", "Kategori automation Team tidak scoped")
        empty("SELECT COUNT(*) FROM recurring_rules WHERE accountId=$accountId AND allocationId IS NOT NULL AND allocationId NOT IN (SELECT a.id FROM allocations a JOIN budget_periods p ON p.id=a.periodId JOIN portfolios f ON f.id=p.portfolioId WHERE f.accountId=$accountId)", "Automation Team memakai alokasi akun lain")
        empty("SELECT COUNT(*) FROM activity_events WHERE accountId=$accountId AND targetAllocationId IS NOT NULL AND targetAllocationId NOT IN (SELECT a.id FROM allocations a JOIN budget_periods p ON p.id=a.periodId JOIN portfolios f ON f.id=p.portfolioId WHERE f.accountId=$accountId)", "Event Team memakai alokasi akun lain")
        empty("SELECT COUNT(*) FROM transaction_splits WHERE eventId IN ($eventScope) AND allocationId IS NOT NULL AND allocationId NOT IN (SELECT a.id FROM allocations a JOIN budget_periods p ON p.id=a.periodId JOIN portfolios f ON f.id=p.portfolioId WHERE f.accountId=$accountId)", "Split Team memakai alokasi akun lain")
        empty("SELECT COUNT(*) FROM budget_journal_lines WHERE eventId IN ($eventScope) AND allocationId IS NOT NULL AND allocationId NOT IN (SELECT a.id FROM allocations a JOIN budget_periods p ON p.id=a.periodId JOIN portfolios f ON f.id=p.portfolioId WHERE f.accountId=$accountId)", "Budget Team memakai alokasi akun lain")
        empty("SELECT COUNT(*) FROM ledger_lines WHERE eventId IN ($eventScope) AND categoryId IS NOT NULL AND categoryId NOT IN (SELECT id FROM categories WHERE accountId=$accountId)", "Ledger Team memakai kategori akun lain")
        empty("SELECT COUNT(*) FROM recurring_occurrences WHERE ruleId IN (SELECT id FROM recurring_rules WHERE accountId=$accountId) AND eventId NOT IN ($eventScope)", "Occurrence Team merujuk event akun lain")
        empty("SELECT COUNT(*) FROM receipts WHERE eventId IN ($eventScope) AND evidenceEventId IS NOT NULL AND evidenceEventId NOT IN ($eventScope)", "Bukti Team merujuk akun lain")
        require(
            scalar(
                db,
                "SELECT COUNT(*) FROM activity_events e WHERE e.accountId=$accountId AND NOT EXISTS(SELECT 1 FROM team_event_proofs p WHERE p.eventId=e.id AND p.teamId=?)",
                arrayOf(scope.teamId),
            ) == 0L,
        ) { "Event Team belum memiliki bukti portable" }
    }

    private fun validateSnapshot(db: SQLiteDatabase, scope: TeamSnapshotScope) {
        require(scalar(db, "PRAGMA user_version") == KronDatabase.SCHEMA_VERSION.toLong()) { "Versi snapshot Team tidak sesuai" }
        require(db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0).equals("ok", true) }) {
            "Integritas snapshot Team tidak valid"
        }
        require(!db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) { "Relasi snapshot Team tidak valid" }
        require(scalar(db, "SELECT COUNT(*) FROM accounts") == 1L) { "Snapshot Team memuat akun lain" }
        require(scalar(db, "SELECT COUNT(*) FROM accounts WHERE id=${scope.accountId} AND teamId=? AND sharingMode='TEAM'", arrayOf(scope.teamId)) == 1L) {
            "Identitas snapshot Team tidak cocok"
        }
        require(scalar(db, "SELECT COUNT(*) FROM journal_seals") == 0L) { "Seal lokal tidak boleh masuk snapshot Team" }
        require(scalar(db, "SELECT COUNT(*) FROM categories WHERE accountId IS NULL OR accountId<>${scope.accountId}") == 0L) {
            "Snapshot Team memuat kategori akun lain"
        }
        require(scalar(db, "SELECT COUNT(*) FROM receipts WHERE localPath IS NOT NULL") == 0L) { "Snapshot Team memuat path lokal" }
        require(scalar(db, "SELECT COUNT(*) FROM team_members") == 0L) { "Snapshot Team memuat cache collaborator" }
        require(scalar(db, "SELECT COUNT(*) FROM sync_state WHERE accountSubject IS NOT NULL OR accountEmail IS NOT NULL") == 0L) {
            "Snapshot Team memuat identitas sync privat"
        }
        validateGraph(db, scope)
    }

    private fun scalar(database: SQLiteDatabase, sql: String, args: Array<String>? = null): Long =
        database.rawQuery(sql, args).use { cursor ->
            require(cursor.moveToFirst())
            cursor.getLong(0)
        }
}
