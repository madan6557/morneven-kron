package com.morneven.kron.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.DebtCalculator
import com.morneven.kron.data.DebtRole
import com.morneven.kron.data.DebtStatus
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.data.TransactionSplitEntity
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.compactIdr
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.formatIdr
import com.morneven.kron.ui.components.signedColor
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Optional per-event channel metadata supplied by a journal projection.
 * Empty metadata keeps cash flow honest and limits the channel filter to assets and budgets.
 */
typealias ReportEventChannels = Map<String, Set<String>>

private enum class ComparisonType { INCOME, EXPENSE, NET }

private fun compactChange(value: Long): String {
    return if (abs(value) >= 1_000_000) compactIdr(value) else formatIdr(value)
}

@Composable
private fun ChangeIndicator(
    current: Long,
    previous: Long?,
    type: ComparisonType,
    visible: Boolean,
    compactMoney: (Long) -> String,
) {
    if (previous == null || (previous == 0L && current == 0L) || (previous != 0L && current == previous)) return

    if (!visible) {
        Text("Rp ***", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }

    if (previous == 0L) {
        Text("Baru", style = MaterialTheme.typography.bodySmall, color = KronGreen)
        return
    }

    val delta = current - previous
    val percentage = (delta.toFloat() / abs(previous).toFloat()) * 100f
    val percentStr = String.format(Locale.ROOT, "%+.1f%%", percentage)
    val nominalStr = compactMoney(delta)

    val color = when (type) {
        ComparisonType.INCOME -> if (delta >= 0) KronGreen else MaterialTheme.colorScheme.error
        ComparisonType.EXPENSE -> if (delta >= 0) MaterialTheme.colorScheme.error else KronGreen
        ComparisonType.NET -> if (delta >= 0) KronGreen else MaterialTheme.colorScheme.error
    }

    Text("$nominalStr ($percentStr)", style = MaterialTheme.typography.bodySmall, color = color)
}

@Composable
fun ReportsScreen(
    state: KronUiState,
    onExport: () -> Unit,
    onManageDebts: () -> Unit = {},
    modifier: Modifier = Modifier,
    eventChannels: ReportEventChannels = emptyMap(),
    readOnly: Boolean = false,
) {
    val visible = state.valuesVisible
    var range by rememberSaveable { mutableStateOf(ReportRange.THIRTY_DAYS) }
    var mode by rememberSaveable { mutableStateOf(ReportMode.CUMULATIVE) }
    var series by rememberSaveable { mutableStateOf(ReportSeries.ALL) }
    var channel by rememberSaveable { mutableStateOf(ReportChannel.ALL) }
    var selectedPoint by rememberSaveable { mutableIntStateOf(-1) }
    var expandedPeriod by rememberSaveable { mutableStateOf<Long?>(null) }
    var customStartDay by rememberSaveable { mutableLongStateOf(LocalDate.now().minusDays(29).toEpochDay()) }
    var customEndDay by rememberSaveable { mutableLongStateOf(LocalDate.now().toEpochDay()) }
    var pickingStart by rememberSaveable { mutableStateOf(true) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var showTable by rememberSaveable { mutableStateOf(false) }

    val today = LocalDate.now()
    val reportDebts = state.debts
    val debtPayable = reportDebts.filter { it.status == DebtStatus.OPEN && it.role == DebtRole.DEBTOR }
        .sumOf { it.principalOutstanding + DebtCalculator.currentInterest(it, today) }
    val debtReceivable = reportDebts.filter { it.status == DebtStatus.OPEN && it.role == DebtRole.CREDITOR }
        .sumOf { it.principalOutstanding + DebtCalculator.currentInterest(it, today) }
    val debtInterest = reportDebts.filter { it.status == DebtStatus.OPEN }
        .sumOf { DebtCalculator.currentInterest(it, today) }
    val customStart = LocalDate.ofEpochDay(customStartDay)
    val customEnd = LocalDate.ofEpochDay(customEndDay)
    val end = if (range == ReportRange.CUSTOM) customEnd else today
    val start = if (range == ReportRange.CUSTOM) customStart else end.minusDays((range.days - 1).toLong())
    val channelAware = eventChannels.isNotEmpty()
    val cashFlowEvents = remember(state.activities, start, end, channel, eventChannels) {
        state.activities.filter { event ->
            val date = LocalDate.ofEpochDay(event.effectiveEpochDay)
            val channelMatches = channel == ReportChannel.ALL || !channelAware || eventChannels[event.id]?.contains(channel.value) == true
            !date.isBefore(start) &&
                !date.isAfter(end) &&
                event.reversedByEventId == null &&
                event.isCashFlowEvent() &&
                channelMatches
        }
    }
    val rawBuckets = remember(cashFlowEvents, start, end) { aggregateCashFlow(cashFlowEvents, start, end) }
    val buckets = remember(rawBuckets, mode) {
        if (mode == ReportMode.CUMULATIVE) rawBuckets.toCumulative() else rawBuckets
    }
    val effectiveSeries = when (mode) {
        ReportMode.CUMULATIVE -> ReportSeries.NET
        ReportMode.CASH_FLOW -> series
    }
    val income = cashFlowEvents.sumOf { event -> event.cashImpact.coerceAtLeast(0L) }
    val expense = cashFlowEvents.sumOf { event -> (-event.cashImpact).coerceAtLeast(0L) }
    val net = income - expense
    val prevDays = if (range == ReportRange.CUSTOM) {
        (end.toEpochDay() - start.toEpochDay() + 1L).toInt()
    } else {
        range.days
    }
    val prevStart = start.minusDays(prevDays.toLong())
    val prevEnd = start.minusDays(1)
    val prevCashFlowEvents = remember(state.activities, prevStart, prevEnd, channel, eventChannels) {
        state.activities.filter { event ->
            val date = LocalDate.ofEpochDay(event.effectiveEpochDay)
            val channelMatches = channel == ReportChannel.ALL || !channelAware || eventChannels[event.id]?.contains(channel.value) == true
            !date.isBefore(prevStart) && !date.isAfter(prevEnd) && event.reversedByEventId == null && event.isCashFlowEvent() && channelMatches
        }
    }
    val prevIncome = prevCashFlowEvents.sumOf { it.cashImpact.coerceAtLeast(0L) }
    val prevExpense = prevCashFlowEvents.sumOf { (-it.cashImpact).coerceAtLeast(0L) }
    val prevNet = prevIncome - prevExpense
    val selectedChannel = channel.value
    val filteredAllocations = remember(state.allocations, selectedChannel) {
        state.allocations.filter { it.periodStatus != PeriodStatus.CLOSED && (selectedChannel == null || it.fundingChannel == selectedChannel) }
    }
    val periods = remember(filteredAllocations) {
        filteredAllocations
            .groupBy { it.periodId }
            .entries
            .sortedByDescending { it.value.firstOrNull()?.startEpochDay ?: 0L }
    }
    val unexpectedTotal = cashFlowEvents.filter { it.type == "UNEXPECTED_EXPENSE" }.sumOf { (-it.cashImpact).coerceAtLeast(0L) }
    val unexpectedEvents = cashFlowEvents.filter { it.type == "UNEXPECTED_EXPENSE" }
    val unexpectedSplitsByCategory = remember(unexpectedEvents, state.splits, state.categories) {
        val splitMap = state.splits.filter { split -> unexpectedEvents.any { it.id == split.eventId } }
        val categoryMap = state.categories.associateBy { it.id }
        splitMap.groupBy { it.categoryId }.map { (catId, splits) ->
            val catName = catId?.let { categoryMap[it]?.name } ?: "Tanpa kategori"
            val cat = catId?.let { categoryMap[it] }
            Triple(catName, splits.sumOf { it.amount }, cat?.direction)
        }.sortedByDescending { it.second }
    }
    var unexpectedExpanded by rememberSaveable { mutableStateOf(false) }
    val money: (Long) -> String = { value -> displayMoney(value, visible) }
    val rangeLabel = if (range == ReportRange.CUSTOM) "${shortDate(start)} sampai ${shortDate(end)}" else range.label.lowercase()

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("LAPORAN", style = MaterialTheme.typography.headlineMedium)
                    Text("Ringkasan yang direkonstruksi dari jurnal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Akun aktif: ${state.activeAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Button(onClick = onExport, enabled = !readOnly) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text(if (readOnly) "Viewer" else "CSV")
                }
            }
        }

        item {
            SectionHeader("Rentang analitik")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(ReportRange.entries, key = { it.name }) { item ->
                    FilterChip(
                        selected = range == item,
                        onClick = {
                            range = item
                            selectedPoint = -1
                        },
                        label = { Text(item.label) },
                    )
                }
            }
            if (range == ReportRange.CUSTOM) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            pickingStart = true
                            showDatePicker = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Mulai\n${shortDate(customStart)}") }
                    TextButton(
                        onClick = {
                            pickingStart = false
                            showDatePicker = true
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("Sampai\n${shortDate(customEnd)}") }
                }
            }
            Text("Mode laporan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(ReportMode.entries, key = { it.name }) { item ->
                    FilterChip(
                        selected = mode == item,
                        onClick = { mode = item; selectedPoint = -1 },
                        label = { Text(item.label) },
                    )
                }
            }
            if (mode == ReportMode.CASH_FLOW) {
                Text("Seri", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                    items(ReportSeries.entries, key = { it.name }) { item ->
                        FilterChip(
                            selected = series == item,
                            onClick = {
                                series = item
                                selectedPoint = -1
                            },
                            label = { Text(item.label) },
                        )
                    }
                }
            }
            Text("Kanal aset dan budget", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(ReportChannel.entries, key = { it.name }) { item ->
                    FilterChip(
                        selected = channel == item,
                        onClick = {
                            channel = item
                            selectedPoint = -1
                        },
                        label = { Text(item.label) },
                    )
                }
            }
            if (!channelAware && channel != ReportChannel.ALL) {
                Text(
                    "Filter kanal diterapkan pada aset dan budget. Cash flow tetap mencakup semua kanal sampai rincian kanal tersedia pada jurnal.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            HudCard {
                SectionHeader(
                    if (mode == ReportMode.CUMULATIVE) "Perubahan saldo $rangeLabel" else "Cash flow $rangeLabel"
                )
                ReportMetric("Masuk", money(income), KronGreen) {
                    ChangeIndicator(income, prevIncome, ComparisonType.INCOME, visible, ::compactChange)
                }
                ReportMetric("Keluar", money(expense), MaterialTheme.colorScheme.error) {
                    ChangeIndicator(expense, prevExpense, ComparisonType.EXPENSE, visible, ::compactChange)
                }
                ReportMetric("Net", money(net), KronGold) {
                    ChangeIndicator(net, prevNet, ComparisonType.NET, visible, ::compactChange)
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !showTable, onClick = { showTable = false }, label = { Text("Grafik") })
                    FilterChip(selected = showTable, onClick = { showTable = true }, label = { Text("Tabel") })
                }
                if (!showTable) {
                    ChartLegend(effectiveSeries)
                    Spacer(Modifier.height(8.dp))
                    CashFlowChart(
                        buckets = buckets,
                        series = effectiveSeries,
                        selected = selectedPoint,
                        valuesVisible = visible,
                        onSelect = { selectedPoint = it },
                    )
                    Spacer(Modifier.height(8.dp))
                    if (selectedPoint in buckets.indices) {
                        BucketDetails(buckets[selectedPoint], money)
                    } else {
                        Text(
                            if (buckets.isEmpty()) "Belum ada cash flow pada rentang ini" else "Tekan grafik untuk melihat rincian periode",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (showTable) {
            val reversedBuckets = buckets.reversed()
            items(reversedBuckets, key = { it.key }) { bucket ->
                val originalIndex = buckets.indexOf(bucket)
                val prevBucket = if (originalIndex > 0) buckets[originalIndex - 1] else null
                HudCard {
                    Text(bucket.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    BucketDetails(bucket, money, prevBucket, visible)
                }
            }
        }

        item {
            HudCard {
                SectionHeader("Komposisi aset")
                if (channel in setOf(ReportChannel.ALL, ReportChannel.CASH)) {
                    AssetRow(FundingChannel.CASH, state.totalCashAssets, money)
                }
                if (channel == ReportChannel.ALL) Spacer(Modifier.height(12.dp))
                if (channel in setOf(ReportChannel.ALL, ReportChannel.EBUDGET)) {
                    AssetRow(FundingChannel.EBUDGET, state.totalEBudgetAssets, money)
                }
            }
        }

        if (reportDebts.isNotEmpty()) item {
            HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)) {
                SectionHeader("Laporan Hutang")
                Text("Tracker ini tidak menambah saldo aset. Hanya pembayaran yang masuk ke cash flow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                ReportMetric("Perlu dibayar", money(debtPayable), MaterialTheme.colorScheme.error)
                ReportMetric("Akan diterima", money(debtReceivable), KronGreen)
                ReportMetric("Bunga berjalan", money(debtInterest), KronGold)
                Text("Aktif ${reportDebts.count { it.status == DebtStatus.OPEN }} · Lunas ${reportDebts.count { it.status == DebtStatus.SETTLED }} · Arsip ${reportDebts.count { it.status == DebtStatus.ARCHIVED }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reportDebts.filter { it.status == DebtStatus.OPEN }.sortedBy { it.dueEpochDay ?: Long.MAX_VALUE }.take(3).forEach { debt ->
                    val interest = DebtCalculator.currentInterest(debt, today)
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                    Text("${if (debt.role == DebtRole.DEBTOR) "Hutang" else "Piutang"} · ${debt.counterparty}", style = MaterialTheme.typography.bodyMedium)
                    Text("${debt.title} · ${money(debt.principalOutstanding + interest)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    debt.dueEpochDay?.let { due -> Text("Tenggat ${shortDate(LocalDate.ofEpochDay(due))}", style = MaterialTheme.typography.labelSmall, color = if (LocalDate.ofEpochDay(due).isBefore(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary) }
                }
                TextButton(onClick = onManageDebts, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text("Kelola hutang & piutang")
                }
            }
        }

        if (periods.isNotEmpty()) item { SectionHeader("Aktual per budget") }
        items(periods, key = { it.key }) { (periodId, rows) ->
            val planned = rows.sumOf { it.plannedAmount }
            val booked = rows.sumOf { it.bookedAmount }
            val spent = rows.sumOf { it.spentAmount }
            val available = rows.sumOf { it.availableAmount }
            val expanded = expandedPeriod == periodId
            HudCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expandedPeriod = if (expanded) null else periodId }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${rows.first().portfolioName}, tersedia ${money(available)}, ${if (expanded) "rincian terbuka" else "ketuk untuk rincian"}"
                    },
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(rows.first().portfolioName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${shortDate(LocalDate.ofEpochDay(rows.first().startEpochDay))} sampai ${shortDate(LocalDate.ofEpochDay(rows.first().endEpochDay))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Tutup rincian" else "Lihat rincian",
                    )
                }
                Text(money(available), style = MaterialTheme.typography.titleLarge, color = signedColor(available))
                Text("Tersedia", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (booked <= 0) 0f else (spent.toFloat() / booked).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (available < 0) MaterialTheme.colorScheme.error else KronGold,
                )
                Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Rencana ${money(planned)}", style = MaterialTheme.typography.bodySmall)
                    Text("Booking ${money(booked)}", style = MaterialTheme.typography.bodySmall)
                    Text("Terpakai ${money(spent)}", style = MaterialTheme.typography.bodySmall)
                }
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    rows.forEach { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(row.categoryName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                ChannelBadge(row.fundingChannel)
                            }
                            Text(
                                "Terpakai ${money(row.spentAmount)} dari rencana ${money(row.plannedAmount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (unexpectedTotal > 0) item {
            HudCard(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { unexpectedExpanded = !unexpectedExpanded }
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Pengeluaran tak terduga ${money(unexpectedTotal)}, ${if (unexpectedExpanded) "rincian terbuka" else "ketuk untuk rincian"}"
                    },
                accent = MaterialTheme.colorScheme.error,
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pengeluaran tak terduga", style = MaterialTheme.typography.titleMedium)
                        Text("Tidak mengurangi alokasi budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(
                        if (unexpectedExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (unexpectedExpanded) "Tutup rincian" else "Lihat rincian",
                    )
                }
                Text(money(unexpectedTotal), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                if (unexpectedExpanded && unexpectedSplitsByCategory.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(8.dp))
                    unexpectedSplitsByCategory.forEach { (catName, amount, _) ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(catName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                "Total ${money(amount)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else if (unexpectedExpanded) {
                    Spacer(Modifier.height(8.dp))
                    Text("Tidak ada rincian per kategori", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showDatePicker) {
        val selectedDate = if (pickingStart) customStart else customEnd
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (pickingStart) {
                            customStartDay = minOf(date, customEnd).toEpochDay()
                        } else {
                            customEndDay = maxOf(date, customStart).toEpochDay()
                        }
                    }
                    selectedPoint = -1
                    showDatePicker = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Batal") } },
        ) { DatePicker(picker) }
    }
}

private enum class ReportMode(val label: String) {
    CUMULATIVE("Akumulasi net"),
    CASH_FLOW("Arus kas"),
}

private enum class ReportRange(val label: String, val days: Int) {
    SEVEN_DAYS("7 hari", 7),
    THIRTY_DAYS("30 hari", 30),
    NINETY_DAYS("90 hari", 90),
    ONE_YEAR("1 tahun", 365),
    CUSTOM("Custom", 30),
}

private enum class ReportSeries(val label: String) {
    ALL("Semua"),
    INCOME("Masuk"),
    EXPENSE("Keluar"),
    NET("Net"),
}

private enum class ReportChannel(val label: String, val value: String?) {
    ALL("Semua kanal", null),
    CASH("Cash", FundingChannel.CASH),
    EBUDGET("eBudget", FundingChannel.EBUDGET),
}

private data class CashFlowBucket(
    val key: String,
    val label: String,
    val income: Long,
    val expense: Long,
) {
    val net: Long get() = income - expense
}

private fun ActivityRow.isCashFlowEvent(): Boolean = when (type) {
    "INCOME", "OPENING_BALANCE", "EXPENSE", "UNEXPECTED_EXPENSE", "AUTOMATION" -> cashImpact != 0L
    else -> false
}

private fun aggregateCashFlow(events: List<ActivityRow>, start: LocalDate, end: LocalDate): List<CashFlowBucket> {
    val dayCount = end.toEpochDay() - start.toEpochDay() + 1L
    return if (dayCount <= 90L) {
        val byDay = events.groupBy { it.effectiveEpochDay }
        generateSequence(start) { current -> current.plusDays(1).takeUnless { it.isAfter(end) } }
            .map { date ->
                val rows = byDay[date.toEpochDay()].orEmpty()
                CashFlowBucket(
                    key = "D:${date.toEpochDay()}",
                    label = shortDate(date),
                    income = rows.sumOf { it.cashImpact.coerceAtLeast(0L) },
                    expense = rows.sumOf { (-it.cashImpact).coerceAtLeast(0L) },
                )
            }
            .toList()
    } else {
        val firstMonth = YearMonth.from(start)
        val lastMonth = YearMonth.from(end)
        val byMonth = events.groupBy { YearMonth.from(LocalDate.ofEpochDay(it.effectiveEpochDay)) }
        generateSequence(firstMonth) { current -> current.plusMonths(1).takeUnless { it.isAfter(lastMonth) } }
            .map { month ->
                val rows = byMonth[month].orEmpty()
                CashFlowBucket(
                    key = "M:$month",
                    label = month.format(monthFormat),
                    income = rows.sumOf { it.cashImpact.coerceAtLeast(0L) },
                    expense = rows.sumOf { (-it.cashImpact).coerceAtLeast(0L) },
                )
            }
            .toList()
    }
}

private fun List<CashFlowBucket>.toCumulative(): List<CashFlowBucket> {
    var runningIncome = 0L
    var runningExpense = 0L
    return map { bucket ->
        runningIncome += bucket.income
        runningExpense += bucket.expense
        CashFlowBucket(
            key = bucket.key,
            label = bucket.label,
            income = runningIncome,
            expense = runningExpense,
        )
    }
}

@Composable
private fun ReportMetric(label: String, value: String, color: Color, indicator: (@Composable () -> Unit)? = null) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (indicator != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
                )
                indicator()
            }
        }
    }
}

@Composable
private fun BucketDetails(bucket: CashFlowBucket, money: (Long) -> String, previousBucket: CashFlowBucket? = null, visible: Boolean = true) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ReportValueRow("Masuk", money(bucket.income), KronGreen)
        if (previousBucket != null) {
            ChangeIndicator(bucket.income, previousBucket.income, ComparisonType.INCOME, visible, ::compactChange)
        }
        ReportValueRow("Keluar", money(bucket.expense), MaterialTheme.colorScheme.error)
        if (previousBucket != null) {
            ChangeIndicator(bucket.expense, previousBucket.expense, ComparisonType.EXPENSE, visible, ::compactChange)
        }
        ReportValueRow("Net", money(bucket.net), KronGold)
        if (previousBucket != null) {
            ChangeIndicator(bucket.net, previousBucket.net, ComparisonType.NET, visible, ::compactChange)
        }
    }
}

@Composable
private fun ReportValueRow(label: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChartLegend(series: ReportSeries) {
    val entries = when (series) {
        ReportSeries.ALL -> listOf("Masuk" to KronGreen, "Keluar" to MaterialTheme.colorScheme.error, "Net" to KronGold)
        ReportSeries.INCOME -> listOf("Masuk" to KronGreen)
        ReportSeries.EXPENSE -> listOf("Keluar" to MaterialTheme.colorScheme.error)
        ReportSeries.NET -> listOf("Net" to KronGold)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        entries.forEach { (label, color) ->
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(color = color, shape = MaterialTheme.shapes.extraSmall, modifier = Modifier.height(6.dp).fillMaxWidth(0.035f)) {}
                Text(label, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun AssetRow(channel: String, value: Long, money: (Long) -> String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        ChannelBadge(channel)
        Text(money(value), style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CashFlowChart(
    buckets: List<CashFlowBucket>,
    series: ReportSeries,
    selected: Int,
    valuesVisible: Boolean,
    onSelect: (Int) -> Unit,
) {
    val visibleSeries = when (series) {
        ReportSeries.ALL -> listOf(
            buckets.map { it.income } to KronGreen,
            buckets.map { it.expense } to MaterialTheme.colorScheme.error,
            buckets.map { it.net } to KronGold,
        )
        ReportSeries.INCOME -> listOf(buckets.map { it.income } to KronGreen)
        ReportSeries.EXPENSE -> listOf(buckets.map { it.expense } to MaterialTheme.colorScheme.error)
        ReportSeries.NET -> listOf(buckets.map { it.net } to KronGold)
    }
    val values = visibleSeries.flatMap { it.first }
    val minimum = minOf(0L, values.minOrNull() ?: 0L)
    val maximum = maxOf(0L, values.maxOrNull() ?: 0L)
    val span = (maximum - minimum).coerceAtLeast(1L)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val accessibilityLabel = if (valuesVisible) {
        "Grafik cash flow dengan ${buckets.size} periode. Gunakan tampilan tabel untuk membaca seluruh nilai."
    } else {
        "Grafik cash flow dengan nilai disembunyikan."
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(180.dp)
            .semantics { contentDescription = accessibilityLabel }
            .pointerInput(buckets) {
                detectTapGestures { offset ->
                    if (buckets.isNotEmpty()) {
                        val denominator = size.width.coerceAtLeast(1)
                        onSelect(((offset.x / denominator) * buckets.lastIndex).roundToInt().coerceIn(0, buckets.lastIndex))
                    }
                }
            },
    ) {
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        val zeroY = size.height - ((0L - minimum).toFloat() / span.toFloat()) * size.height
        drawLine(grid.copy(alpha = 0.45f), Offset(0f, zeroY), Offset(size.width, zeroY), 2f)
        if (buckets.isEmpty()) return@Canvas
        val step = if (buckets.size == 1) size.width else size.width / buckets.lastIndex
        visibleSeries.forEach { (seriesValues, color) ->
            val coordinates = seriesValues.mapIndexed { index, value ->
                Offset(
                    x = index * step,
                    y = size.height - ((value - minimum).toFloat() / span.toFloat()) * size.height,
                )
            }
            coordinates.zipWithNext().forEach { (lineStart, lineEnd) -> drawLine(color, lineStart, lineEnd, 3.2f) }
            coordinates.forEachIndexed { index, point ->
                if (buckets.size <= 31 || index == selected) {
                    drawCircle(if (index == selected) selectedColor else color, if (index == selected) 6.5f else 3f, point)
                }
            }
        }
    }
}

private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("id-ID"))
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
private fun shortDate(date: LocalDate): String = date.format(dateFormat)
