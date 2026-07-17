package com.morneven.kron.report

import android.content.Context
import android.net.Uri
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AllocationBalanceRow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class CsvExporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    suspend fun export(uri: Uri, activities: List<ActivityRow>, allocations: List<AllocationBalanceRow>) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(uri, "w")?.bufferedWriter(Charsets.UTF_8)?.use { writer ->
            writer.write("\uFEFF")
            writer.appendLine("KRON ACTIVITY JOURNAL")
            writer.appendLine("tanggal,tipe,judul,sumber,dampak_akun,dampak_vault,dampak_budget,status")
            activities.forEach { event ->
                writer.appendLine(listOf(
                    LocalDate.ofEpochDay(event.effectiveEpochDay),
                    event.type,
                    event.title,
                    event.source,
                    event.cashImpact,
                    event.vaultImpact,
                    event.budgetImpact,
                    if (event.reversedByEventId == null) "NORMAL" else "REVERSED",
                ).joinToString(",") { csv(it.toString()) })
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
                ).joinToString(",") { csv(it.toString()) })
            }
        } ?: error("Tidak dapat membuka file CSV")
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
}
