package com.morneven.kron.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.BudgetProgress
import com.morneven.kron.ui.components.CategoryAvatar
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.EmptyState
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.signedColor
import com.morneven.kron.ui.theme.KronBlue
import com.morneven.kron.ui.theme.KronButtonShape
import com.morneven.kron.ui.theme.KronChipShape
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronSubCardShape
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
    onDetail: (Long, Boolean) -> Unit,
    onHistory: (Long) -> Unit,
    onPause: (Long) -> Unit,
    onResume: (Long) -> Unit,
    onArchive: (Long) -> Unit,
    onToggleRollover: (Long, Boolean) -> Unit,
    onRestore: (Long, Boolean) -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showArchive by remember { mutableStateOf(false) }
    val categoryMap = remember(state.categories) { state.categories.associateBy { it.id } }
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
                    Text("BUDGET", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Portfolio bulanan dan tahunan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                FilterChip(selected = !showArchive, onClick = { showArchive = false }, label = { Text("Aktif") }, shape = KronChipShape)
                FilterChip(selected = showArchive, onClick = { showArchive = true }, label = { Text("Arsip (${state.archivedPortfolios.size})") }, shape = KronChipShape)
            }
        }

        if (!showArchive) {
            item {
                HudCard(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                    SectionHeader("Dana terbooking")
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ChannelAmount(FundingChannel.CASH, state.bookedCash, state.valuesVisible, Modifier.weight(1f))
                        ChannelAmount(FundingChannel.EBUDGET, state.bookedEBudget, state.valuesVisible, Modifier.weight(1f))
                    }
                    val rollover = state.rolloverCash + state.rolloverEBudget
                    if (rollover > 0 || !state.valuesVisible) {
                        Spacer(Modifier.height(12.dp))
                        Text("Reserve rollover", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChannelAmount(FundingChannel.CASH, state.rolloverCash, state.valuesVisible, Modifier.weight(1f))
                            ChannelAmount(FundingChannel.EBUDGET, state.rolloverEBudget, state.valuesVisible, Modifier.weight(1f))
                        }
                    }
                    if (!readOnly) {
                        Spacer(Modifier.height(12.dp))
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onChannelTransfer),
                            shape = KronSubCardShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = KronBlue.copy(alpha = 0.15f),
                                    modifier = Modifier.size(32.dp),
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = KronBlue, modifier = Modifier.size(18.dp))
                                    }
                                }
                                Column(Modifier.weight(1f)) {
                                    Text("Pindahkan Cash dan eBudget", style = MaterialTheme.typography.titleSmall)
                                    Text("Konversi dana kategori beserta akun nyata", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(
                                    Icons.AutoMirrored.Outlined.ArrowForward,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(16.dp),
                                )
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
                val period = state.periods.firstOrNull { it.id == first.periodId }
                val periodTotal = period?.plannedTotal ?: rows.sumOf { it.plannedAmount }
                ActiveBudgetCard(
                    rows = rows,
                    categoryMap = categoryMap,
                    periodTotal = periodTotal,
                    paused = portfolio.isPaused,
                    rolloverEnabled = portfolio.rolloverEnabled,
                    hasPortfolioDeficit = state.allocations.any { it.portfolioId == first.portfolioId && it.availableAmount < 0 },
                    visible = state.valuesVisible,
                    onDetail = { onDetail(first.periodId, readOnly) },
                    onHistory = { onHistory(first.portfolioId) },
                    onPause = { onPause(first.portfolioId) },
                    onResume = { onResume(first.portfolioId) },
                    onArchive = { onArchive(first.portfolioId) },
                    onToggleRollover = { enabled -> onToggleRollover(first.portfolioId, enabled) },
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
                            OutlinedButton(onClick = { onRestore(portfolio.id, false) }, modifier = Modifier.fillMaxWidth(), shape = KronButtonShape) {
                                Icon(Icons.Outlined.Restore, contentDescription = null)
                                Text("Pulihkan")
                            }
                            Button(onClick = { onRestore(portfolio.id, true) }, modifier = Modifier.fillMaxWidth(), shape = KronButtonShape) {
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
    categoryMap: Map<Long, CategoryEntity>,
    periodTotal: Long,
    paused: Boolean,
    rolloverEnabled: Boolean,
    hasPortfolioDeficit: Boolean,
    visible: Boolean,
    onDetail: () -> Unit,
    onHistory: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onArchive: () -> Unit,
    onToggleRollover: (Boolean) -> Unit,
    onResolve: () -> Unit,
    onFund: () -> Unit,
    readOnly: Boolean,
) {
    val first = rows.first()
    val available = rows.sumOf { it.availableAmount }
    val booked = rows.sumOf { it.bookedAmount }
    val hasDeficit = rows.any { it.availableAmount < 0 }
    HudCard(
        accent = if (hasDeficit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(first.portfolioName, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("${if (paused) "JEDA" else first.periodStatus.replace('_', ' ')} · ${LocalDate.ofEpochDay(first.startEpochDay).format(indonesianDate)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(displayMoney(available, visible), color = signedColor(available), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.height(10.dp))
        BudgetProgress(booked, available)
        val plannedCategories = rows.sumOf { it.plannedAmount }
        val unallocated = (periodTotal - plannedCategories).coerceAtLeast(0L)
        if (unallocated > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Sisa belum dibagi",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    displayMoney(unallocated, visible),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        val visibleRows = rows.filter { it.isActive }
        visibleRows.forEach { row ->
            val cat = categoryMap[row.categoryId]
            val isUnfunded = row.periodStatus != PeriodStatus.DRAFT && row.bookedAmount == 0L && row.availableAmount == 0L && row.spentAmount == 0L && row.plannedAmount > 0L
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp),
                shape = KronSubCardShape,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    ) {
                        CategoryAvatar(
                            categoryName = row.categoryName,
                            iconKey = cat?.icon,
                            colorLong = cat?.color,
                            size = 32.dp,
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                row.categoryName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            ChannelBadge(row.fundingChannel)
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        if (isUnfunded) {
                            Surface(
                                color = KronGold.copy(alpha = 0.14f),
                                shape = KronChipShape,
                                border = BorderStroke(0.6.dp, KronGold.copy(alpha = 0.4f)),
                            ) {
                                Text(
                                    text = "Perlu booking",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = KronGold,
                                )
                            }
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "rencana ${displayMoney(row.plannedAmount, visible)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        } else {
                            Text(
                                displayMoney(row.availableAmount, visible),
                                color = signedColor(row.availableAmount),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                softWrap = false,
                            )
                            Text(
                                "booking ${displayMoney(row.bookedAmount, visible)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onDetail, modifier = Modifier.weight(1f), shape = KronButtonShape) { Text("Detail budget") }
            OutlinedButton(onClick = onHistory, modifier = Modifier.weight(1f), shape = KronButtonShape) { Text("Riwayat") }
        }
        if (!readOnly) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = if (paused) onResume else onPause, modifier = Modifier.weight(1f), shape = KronButtonShape) {
                    Icon(if (paused) Icons.Outlined.PlayCircle else Icons.Outlined.PauseCircle, contentDescription = null)
                    Text(if (paused) "Lanjutkan" else "Jeda")
                }
                OutlinedButton(onClick = onArchive, enabled = !hasPortfolioDeficit, modifier = Modifier.weight(1f), shape = KronButtonShape) {
                    Icon(Icons.Outlined.Archive, contentDescription = null)
                    Text("Arsipkan")
                }
            }
            if (hasPortfolioDeficit) {
                Button(onClick = onResolve, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), shape = KronButtonShape) {
                    Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                    Text("Selesaikan budget minus")
                }
            } else if (first.periodStatus == PeriodStatus.UNDERFUNDED || first.periodStatus == PeriodStatus.DRAFT) {
                Button(onClick = onFund, modifier = Modifier.fillMaxWidth().padding(top = 10.dp), shape = KronButtonShape) { Text("Booking dari Main Vault") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Rollover sisa ke periode berikutnya", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = rolloverEnabled, onCheckedChange = onToggleRollover)
            }
        }
    }
}

@Composable
private fun ChannelAmount(channel: String, amount: Long, visible: Boolean, modifier: Modifier = Modifier) {
    val isCash = channel == FundingChannel.CASH
    val color = if (isCash) KronGold else KronBlue
    Surface(
        modifier = modifier,
        shape = KronSubCardShape,
        color = color.copy(alpha = 0.08f),
        border = BorderStroke(0.8.dp, color.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ChannelBadge(channel)
            Text(
                text = displayMoney(amount, visible),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

private val indonesianDate = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
