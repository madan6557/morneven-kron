package com.morneven.kron.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.BudgetProgress
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.EmptyState
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.signedColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun BudgetScreen(
    state: KronUiState,
    onCreate: () -> Unit,
    onResolve: () -> Unit,
    onFund: (Long) -> Unit,
    onChannelTransfer: () -> Unit,
    onReleaseRollover: (String) -> Unit,
    onDetail: (Long, Boolean) -> Unit,
    onHistory: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onArchive: (Long) -> Unit,
    onRestore: (Long, Boolean) -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showArchive by remember { mutableStateOf(false) }
    val latestActivePeriods = state.portfolios.mapNotNull { portfolio ->
        state.allocations
            .filter { it.portfolioId == portfolio.id && !it.portfolioArchived }
            .groupBy { it.periodId }
            .maxByOrNull { (_, rows) -> rows.maxOf { it.startEpochDay } }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("BUDGET", style = MaterialTheme.typography.headlineMedium)
                    Text("Portfolio bulanan dan tahunan", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Akun aktif: ${state.activeAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (!showArchive && !readOnly) {
                    Button(onClick = onCreate) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text("Buat")
                    }
                }
            }
        }
        item {
            if (readOnly) {
                HudCard(accent = MaterialTheme.colorScheme.tertiary) {
                    Text("Akses hanya lihat", style = MaterialTheme.typography.titleMedium)
                    Text("Anda dapat membaca budget Team, tetapi perubahan hanya tersedia untuk Owner dan Editor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !showArchive, onClick = { showArchive = false }, label = { Text("Aktif") })
                FilterChip(selected = showArchive, onClick = { showArchive = true }, label = { Text("Arsip (${state.archivedPortfolios.size})") })
            }
        }

        if (!showArchive) {
            item {
                HudCard {
                    SectionHeader("Dana terbooking")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChannelAmount(FundingChannel.CASH, state.bookedCash, state.valuesVisible, Modifier.weight(1f))
                        ChannelAmount(FundingChannel.EBUDGET, state.bookedEBudget, state.valuesVisible, Modifier.weight(1f))
                    }
                    if (!readOnly && (state.rolloverCash > 0 || state.rolloverEBudget > 0)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (state.rolloverCash > 0) Button(onClick = { onReleaseRollover(FundingChannel.CASH) }) { Text("Cash ke Vault") }
                            if (state.rolloverEBudget > 0) Button(onClick = { onReleaseRollover(FundingChannel.EBUDGET) }) { Text("eBudget ke Vault") }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Reserve rollover", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ChannelAmount(FundingChannel.CASH, state.rolloverCash, state.valuesVisible, Modifier.weight(1f))
                        ChannelAmount(FundingChannel.EBUDGET, state.rolloverEBudget, state.valuesVisible, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(12.dp))
                    if (!readOnly) {
                        Row(
                            Modifier.fillMaxWidth().clickable(onClick = onChannelTransfer).padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Column {
                                Text("Pindahkan Cash dan eBudget", style = MaterialTheme.typography.labelLarge)
                                Text("Konversi dana kategori beserta akun nyata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            if (latestActivePeriods.isEmpty()) {
                item { EmptyState(Icons.Outlined.AccountBalanceWallet, "Belum ada portfolio aktif", "Buat RAB baru atau pulihkan portfolio dari tab Arsip.") }
            }
            items(latestActivePeriods, key = { it.key }) { (_, rows) ->
                val first = rows.first()
                val portfolio = state.portfolios.first { it.id == first.portfolioId }
                ActiveBudgetCard(
                    rows = rows,
                    paused = portfolio.isPaused,
                    hasPortfolioDeficit = state.allocations.any { it.portfolioId == first.portfolioId && it.availableAmount < 0 },
                    visible = state.valuesVisible,
                    onDetail = { onDetail(first.periodId, readOnly) },
                    onHistory = { onHistory(first.portfolioId) },
                    onPause = { onPause(first.portfolioId) },
                    onResume = { onResume(first.portfolioId) },
                    onArchive = { onArchive(first.portfolioId) },
                    onResolve = onResolve,
                    onFund = { onFund(first.periodId) },
                    readOnly = readOnly,
                )
            }
        } else {
            if (state.archivedPortfolios.isEmpty()) {
                item { EmptyState(Icons.Outlined.Archive, "Arsip masih kosong", "Portfolio yang diarsipkan tetap dapat dipulihkan dari sini.") }
            }
            items(state.archivedPortfolios, key = { it.id }) { portfolio ->
                val historicalRows = state.allocations.filter { it.portfolioId == portfolio.id }
                val latestRows = historicalRows.groupBy { it.periodId }.maxByOrNull { (_, rows) -> rows.maxOf { it.startEpochDay } }?.value.orEmpty()
                val archivedDate = portfolio.archivedAt?.let { millis ->
                    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().format(indonesianDate)
                } ?: "Tidak diketahui"
                HudCard(accent = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(portfolio.name, style = MaterialTheme.typography.titleLarge)
                            Text("DIARSIPKAN $archivedDate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text("Setiap ${portfolio.intervalCount} ${if (portfolio.cadence == "YEARLY") "tahun" else "bulan"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    if (latestRows.isNotEmpty()) {
                        Text("Periode terakhir ${LocalDate.ofEpochDay(latestRows.first().startEpochDay).format(indonesianDate)} sampai ${LocalDate.ofEpochDay(latestRows.first().endEpochDay).format(indonesianDate)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Historis terpakai ${displayMoney(historicalRows.sumOf { it.spentAmount }, state.valuesVisible)}", style = MaterialTheme.typography.bodyMedium)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { latestRows.firstOrNull()?.let { onDetail(it.periodId, true) } }, enabled = latestRows.isNotEmpty()) { Text("Lihat detail read-only") }
                        TextButton(onClick = { onHistory(portfolio.id) }) { Text("Riwayat") }
                    }
                    if (!readOnly) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { onRestore(portfolio.id, false) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.Restore, contentDescription = null)
                                Text("Pulihkan")
                            }
                            Button(onClick = { onRestore(portfolio.id, true) }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Outlined.PlayCircle, contentDescription = null)
                                Text("Pulihkan & Aktifkan")
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun ActiveBudgetCard(
    rows: List<AllocationBalanceRow>,
    paused: Boolean,
    hasPortfolioDeficit: Boolean,
    visible: Boolean,
    onDetail: () -> Unit,
    onHistory: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onArchive: () -> Unit,
    onResolve: () -> Unit,
    onFund: () -> Unit,
    readOnly: Boolean,
) {
    val first = rows.first()
    val available = rows.sumOf { it.availableAmount }
    val booked = rows.sumOf { it.bookedAmount }
    val hasDeficit = rows.any { it.availableAmount < 0 }
    HudCard(accent = if (hasDeficit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(first.portfolioName, style = MaterialTheme.typography.titleLarge)
                Text("${if (paused) "JEDA" else first.periodStatus.replace('_', ' ')} · ${LocalDate.ofEpochDay(first.startEpochDay).format(indonesianDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(displayMoney(available, visible), color = signedColor(available), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(12.dp))
        BudgetProgress(booked, available)
        Spacer(Modifier.height(14.dp))
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    ChannelBadge(row.fundingChannel)
                    Text(row.categoryName, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(displayMoney(row.availableAmount, visible), color = signedColor(row.availableAmount), style = MaterialTheme.typography.labelLarge)
                    Text("booking ${displayMoney(row.bookedAmount, visible)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDetail, modifier = Modifier.weight(1f)) { Text("Detail budget") }
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f)) { Text("Riwayat") }
        }
        if (!readOnly) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = if (paused) onResume else onPause, modifier = Modifier.weight(1f)) {
                    Icon(if (paused) Icons.Outlined.PlayCircle else Icons.Outlined.PauseCircle, contentDescription = null)
                    Text(if (paused) "Lanjutkan" else "Jeda")
                }
                OutlinedButton(onClick = onArchive, enabled = !hasPortfolioDeficit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Archive, contentDescription = null)
                    Text("Arsipkan")
                }
            }
            if (hasPortfolioDeficit) {
                Button(onClick = onResolve, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text("Selesaikan budget minus")
                }
            } else if (first.periodStatus == PeriodStatus.UNDERFUNDED || first.periodStatus == PeriodStatus.DRAFT) {
                Button(onClick = onFund, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text("Booking dari Main Vault") }
            }
        }
    }
}

@Composable
private fun ChannelAmount(channel: String, amount: Long, visible: Boolean, modifier: Modifier) {
    Column(modifier) {
        ChannelBadge(channel)
        Text(displayMoney(amount, visible), style = MaterialTheme.typography.titleMedium)
    }
}

private val indonesianDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
