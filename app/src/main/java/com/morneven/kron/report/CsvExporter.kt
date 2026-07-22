package com.morneven.kron.report

import android.content.Context
import android.net.Uri
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.LedgerSide
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class CsvExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
) {
    suspend fun export(uri: Uri, activities: List<ActivityRow>, allocations: List<AllocationBalanceRow>) = withContext(Dispatchers.IO) {
        val dao = database.kronDao()
        val ledgerByEvent = dao.allLedgerLines().groupBy { it.eventId }
        val receiptsByEvent = dao.allReceipts().groupBy { it.eventId }
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write("\uFEFF")
            writer.appendLine("KRON ACTIVITY JOURNAL")
            writer.appendLine("event_id,tanggal_efektif,waktu_dicatat,tipe,judul,sumber,debit,kredit,kanal,dampak_akun,dampak_vault,dampak_budget,reversal,attachment_hash,status_audit")
            activities.forEach { event ->
                val ledger = ledgerByEvent[event.id].orEmpty()
                writer.appendLine(listOf(
                    event.id,
                    LocalDate.ofEpochDay(event.effectiveEpochDay),
                    java.time.Instant.ofEpochMilli(event.createdAt),
                    event.type,
                    event.title,
                    event.source,
                    ledger.filter { it.side == LedgerSide.DEBIT }.sumOf { it.amount },
                    ledger.filter { it.side == LedgerSide.CREDIT }.sumOf { it.amount },
                    ledger.mapNotNull { it.fundingChannel }.distinct().joinToString("+"),
                    event.cashImpact,
                    event.vaultImpact,
                    event.budgetImpact,
                    event.reversedByEventId.orEmpty(),
                    receiptsByEvent[event.id].orEmpty().joinToString("|") { it.sha256 },
                    event.auditStatus,
                ).joinToString(",") { csv(it) })
            }
            writer.appendLine()
            writer.appendLine("KRON BUDGET VS ACTUAL")
            writer.appendLine("portfolio,kategori,kanal,rencana,booking,pengeluaran,sisa,status")
            allocations.forEach { row ->
                writer.appendLine(listOf(
                    row.portfolioName,
                    row.categoryName,
                    row.fundingChannel,
                    row.plannedAmount,
                    row.bookedAmount,
                    row.spentAmount,
                    row.availableAmount,
                    row.periodStatus,
                ).joinToString(",") { csv(it) })
            }
        } ?: error("Tidak dapat membuka file CSV")
    }

    private fun csv(value: Any): String {
        if (value is Number) return value.toString()
        val raw = value.toString()
        val protected = if (raw.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$raw" else raw
        return "\"${protected.replace("\"", "\"\"")}\""
    }
}
