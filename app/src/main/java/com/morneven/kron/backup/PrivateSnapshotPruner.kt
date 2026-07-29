package com.morneven.kron.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File

internal object PrivateSnapshotPruner {
    fun prune(file: File) {
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("PRAGMA foreign_keys=ON")
            db.beginTransaction()
            try {
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='trigger'", null).use { cursor ->
                    val triggers = buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                    triggers.forEach { db.execSQL("DROP TRIGGER IF EXISTS `" + it.replace("`", "``") + "`") }
                }
                val teamEvents = "SELECT id FROM activity_events WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM')"
                db.execSQL("DELETE FROM team_event_proofs WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM journal_seals WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM receipts WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM audit_snapshots WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM transaction_splits WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM budget_journal_lines WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM cash_journal_lines WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM ledger_lines WHERE eventId IN ($teamEvents)")
                db.execSQL("DELETE FROM recurring_occurrences WHERE ruleId IN (SELECT id FROM recurring_rules WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM'))")
                db.execSQL("DELETE FROM recurring_rules WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM')")
                db.execSQL("DELETE FROM portfolio_allocation_templates WHERE portfolioId IN (SELECT id FROM portfolios WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM'))")
                db.execSQL("DELETE FROM allocations WHERE periodId IN (SELECT id FROM budget_periods WHERE portfolioId IN (SELECT id FROM portfolios WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM')))")
                db.execSQL("DELETE FROM budget_periods WHERE portfolioId IN (SELECT id FROM portfolios WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM'))")
                db.execSQL("DELETE FROM portfolios WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM')")
                db.execSQL("DELETE FROM activity_events WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM')")
                db.execSQL("DELETE FROM categories WHERE accountId IN (SELECT id FROM accounts WHERE sharingMode='TEAM')")
                db.execSQL("DELETE FROM ledger_accounts WHERE id NOT IN (SELECT DISTINCT ledgerAccountId FROM ledger_lines)")
                db.execSQL("DELETE FROM evidence_keys WHERE id NOT IN (SELECT keyId FROM journal_seals UNION SELECT keyId FROM team_event_proofs)")
                db.execSQL("DELETE FROM team_members")
                db.execSQL("DELETE FROM team_invitation_uses")
                db.execSQL("DELETE FROM team_workspaces")
                db.execSQL("DELETE FROM accounts WHERE sharingMode='TEAM'")
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            require(db.rawQuery("PRAGMA integrity_check", null).use { it.moveToFirst() && it.getString(0) == "ok" }) {
                "Snapshot Private tidak valid"
            }
            require(!db.rawQuery("PRAGMA foreign_key_check", null).use { it.moveToFirst() }) {
                "Relasi snapshot Private tidak valid"
            }
        }
    }
}
