package com.morneven.kron.report

import android.content.Context
import android.net.Uri
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.data.LedgerSide
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class CsvExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val databaseRuntime: DatabaseRuntime,
) {
    private val database get() = databaseRuntime.current()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.forLanguageTag("id-ID"))

    suspend fun export(uri: Uri, activities: List<ActivityRow>, allocations: List<AllocationBalanceRow>) = withContext(Dispatchers.IO) {
        val dao = database.kronDao()
        val ledgerByEvent = dao.allLedgerLines().groupBy { it.eventId }
        val receiptsByEvent = dao.allReceipts().groupBy { it.eventId }
        val splitsByEvent = dao.allSplits().groupBy { it.eventId }
        val categories = dao.allCategories().associateBy { it.id }
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write("\uFEFF")

            writer.appendLine("KRON ACTIVITY JOURNAL")
            writer.appendLine("event_id,tanggal_efektif,waktu_dicatat,tipe,judul,catatan,sumber,debit,kredit,kanal,dampak_akun,dampak_vault,dampak_budget,dampak_rollover,dampak_belum_dialokasikan,reversal,jumlah_split,attachment_hash,status_audit")
            activities.forEach { event ->
                val ledger = ledgerByEvent[event.id].orEmpty()
                val splits = splitsByEvent[event.id].orEmpty()
                val createdAtZoned = Instant.ofEpochMilli(event.createdAt).atZone(ZoneId.systemDefault())
                writer.appendLine(listOf(
                    event.id,
                    LocalDate.ofEpochDay(event.effectiveEpochDay),
                    createdAtZoned.format(timeFmt),
                    event.type,
                    event.title,
                    event.note,
                    event.source,
                    ledger.filter { it.side == LedgerSide.DEBIT }.sumOf { it.amount },
                    ledger.filter { it.side == LedgerSide.CREDIT }.sumOf { it.amount },
                    ledger.mapNotNull { it.fundingChannel }.distinct().joinToString("+"),
                    event.cashImpact,
                    event.vaultImpact,
                    event.budgetImpact,
                    event.rolloverImpact,
                    event.unallocatedImpact,
                    event.reversedByEventId.orEmpty(),
                    splits.size,
                    receiptsByEvent[event.id].orEmpty().joinToString("|") { it.sha256 },
                    event.auditStatus,
                ).joinToString(",") { csv(it) })
            }

            writer.appendLine()
            writer.appendLine("KRON TRANSACTION SPLITS")
            writer.appendLine("event_id,tanggal_efektif,tipe,judul,kategori_id,nama_kategori,dampak_nominal")
            activities.forEach { event ->
                val splits = splitsByEvent[event.id].orEmpty()
                splits.forEach { split ->
                    val catName = split.categoryId?.let { categories[it]?.name } ?: ""
                    writer.appendLine(listOf(
                        event.id,
                        LocalDate.ofEpochDay(event.effectiveEpochDay),
                        event.type,
                        event.title,
                        split.categoryId ?: "",
                        catName,
                        split.amount,
                    ).joinToString(",") { csv(it) })
                }
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

            writer.appendLine()
            writer.appendLine("KRON SUMMARY")
            val income = activities.filter { it.type in setOf("INCOME", "OPENING_BALANCE") && it.reversedByEventId == null }.sumOf { it.cashImpact.coerceAtLeast(0L) }
            val expense = activities.filter { it.type in setOf("EXPENSE", "AUTOMATION") && it.reversedByEventId == null }.sumOf { (-it.cashImpact).coerceAtLeast(0L) }
            val unexpected = activities.filter { it.type == "UNEXPECTED_EXPENSE" && it.reversedByEventId == null }.sumOf { (-it.cashImpact).coerceAtLeast(0L) }
            val reversalCount = activities.count { it.reversedByEventId != null }
            val totalEvents = activities.size
            val totalReceipts = receiptsByEvent.size
            writer.appendLine("metrik,nilai")
            writer.appendLine("total_event,$totalEvents")
            writer.appendLine("total_pemasukan,$income")
            writer.appendLine("total_pengeluaran,$expense")
            writer.appendLine("total_pengeluaran_tak_terduga,$unexpected")
            writer.appendLine("total_reversal,$reversalCount")
            writer.appendLine("total_lampiran,$totalReceipts")
        } ?: error("Tidak dapat membuka file CSV")
    }

    private fun csv(value: Any): String {
        if (value is Number) return value.toString()
        val raw = value.toString()
        val protected = if (raw.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$raw" else raw
        return "\"${protected.replace("\"", "\"\"")}\""
    }
}
