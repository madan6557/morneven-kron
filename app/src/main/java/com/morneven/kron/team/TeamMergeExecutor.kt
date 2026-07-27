package com.morneven.kron.team

import androidx.sqlite.db.SupportSQLiteDatabase
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.sync.ConflictChoice
import com.morneven.kron.sync.MergePlan
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MergeResult(
    val appliedChoices: Int,
    val eventsMerged: Int,
    val entitiesMerged: Int,
)

@Singleton
class TeamMergeExecutor @Inject constructor(
    private val database: KronDatabase,
) {
    suspend fun execute(
        plan: MergePlan,
        remoteDbFile: File,
        datasetId: String,
        generation: Long,
    ): MergeResult = withContext(Dispatchers.IO) {
        require(remoteDbFile.isFile) { "Database snapshot remote tidak ditemukan" }
        applyPlan(plan, remoteDbFile, datasetId, generation)
    }

    private fun applyPlan(
        plan: MergePlan,
        remoteDb: File,
        datasetId: String,
        generation: Long,
    ): MergeResult {
        val liveWritable = database.openHelper.writableDatabase
        liveWritable.execSQL("ATTACH DATABASE ? AS merge_db", arrayOf(remoteDb.absolutePath))
        try {
            liveWritable.beginTransaction()
            try {
                var appliedChoices = 0
                var eventsMerged = 0
                var entitiesMerged = 0
                for ((key, choice) in plan.choices) {
                    if (choice != ConflictChoice.DRIVE) {
                        appliedChoices++
                        continue
                    }
                    val parts = key.split(":", limit = 2)
                    if (parts.size < 2) continue
                    when (parts[0]) {
                        "event" -> {
                            copyEvent(liveWritable, parts[1])
                            eventsMerged++
                        }
                        else -> {
                            val table = entityTable(parts[0]) ?: continue
                            copySyncEntity(liveWritable, table, parts[1])
                            entitiesMerged++
                        }
                    }
                    appliedChoices++
                }
                updateSyncState(liveWritable, plan, datasetId, generation)
                database.invalidationTracker.refreshAsync()
                liveWritable.setTransactionSuccessful()
                return MergeResult(appliedChoices, eventsMerged, entitiesMerged)
            } finally {
                liveWritable.endTransaction()
            }
        } finally {
            liveWritable.execSQL("DETACH DATABASE merge_db")
        }
    }

    private fun copyEvent(live: SupportSQLiteDatabase, eventId: String) {
        for (table in listOf("cash_journal_lines", "budget_journal_lines",
                "transaction_splits", "audit_snapshots")) {
            live.execSQL("DELETE FROM $table WHERE eventId=?", arrayOf<Any?>(eventId))
        }
        live.execSQL("DELETE FROM activity_events WHERE id=?", arrayOf<Any?>(eventId))
        for (table in listOf("activity_events", "cash_journal_lines", "budget_journal_lines",
                "transaction_splits", "audit_snapshots")) {
            live.execSQL("INSERT OR IGNORE INTO $table SELECT * FROM merge_db.$table WHERE id=? OR eventId=?",
                arrayOf<Any?>(eventId, eventId))
        }
    }

    private fun copySyncEntity(live: SupportSQLiteDatabase, table: String, syncId: String) {
        live.execSQL("DELETE FROM $table WHERE syncId=?", arrayOf<Any?>(syncId))
        live.execSQL("INSERT OR IGNORE INTO $table SELECT * FROM merge_db.$table WHERE syncId=?",
            arrayOf<Any?>(syncId))
    }

    private fun updateSyncState(live: SupportSQLiteDatabase, plan: MergePlan, datasetId: String, generation: Long) {
        val now = System.currentTimeMillis()
        for (parent in plan.parentSnapshotIds) {
            live.execSQL("UPDATE sync_state SET status='SUPERSEDED', updatedAt=? WHERE snapshotId=?",
                arrayOf<Any?>(now, parent))
        }
        val evCount = live.query("SELECT COUNT(*) FROM merge_db.activity_events").use {
            (if (it.moveToFirst()) it.getLong(0) else 0L).toString()
        }
        live.execSQL(
            "INSERT OR REPLACE INTO sync_state(datasetId,snapshotId,parentSnapshotIds,generation,status,totalEvents,updatedAt) VALUES(?,?,?,?,?,?,?)",
            arrayOf<Any?>(datasetId, plan.expectedRemoteSnapshotId, plan.parentSnapshotIds.joinToString(","),
                generation + 1, "SYNCED", evCount, now),
        )
    }

    private fun entityTable(type: String): String? = when (type) {
        "CATEGORY" -> "categories"
        "PORTFOLIO" -> "portfolios"
        "BUDGET_PERIOD" -> "budget_periods"
        "ALLOCATION" -> "allocations"
        "RECURRING_RULE" -> "recurring_rules"
        else -> null
    }
}
