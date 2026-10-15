package com.morneven.kron.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.IconButton
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Payments
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.DebtCalculator
import com.morneven.kron.data.DebtRole
import com.morneven.kron.data.DebtStatus
import com.morneven.kron.data.DebtSummary
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
import com.morneven.kron.ui.theme.KronSubCardShape
import com.morneven.kron.ui.theme.LocalKronDarkTheme
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
        Text("Rp ***", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
        return
    }

    if (previous == 0L) {
        Text("Baru", style = MaterialTheme.typography.bodySmall, color = KronGreen, maxLines = 1, softWrap = false)
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

    Text(
        "$nominalStr ($percentStr)",
        style = MaterialTheme.typography.bodySmall,
        color = color,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis,
    )
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
    var range by rememberSaveable { mutableStateOf(ReportRange.THIS_MONTH) }
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
    val debtSummary = remember(state.debts, today.toEpochDay()) { DebtSummary.of(state.debts, today) }
    val debtPayable = debtSummary.payable
    val debtReceivable = debtSummary.receivable
    val debtInterest = debtSummary.interest
    val customStart = LocalDate.ofEpochDay(customStartDay)
    val customEnd = LocalDate.ofEpochDay(customEndDay)
    val dateComparison = remember(range, today, customStartDay, customEndDay) {
        when (range) {
            ReportRange.THIS_MONTH -> {
                if (today.dayOfMonth <= 7) {
                    val prevMonth = YearMonth.from(today).minusMonths(1)
                    val s = prevMonth.atDay(1)
                    val e = prevMonth.atEndOfMonth()
                    val pMonth = prevMonth.minusMonths(1)
                    DateRangeComparison(s, e, pMonth.atDay(1), pMonth.atEndOfMonth())
                } else {
                    val curMonth = YearMonth.from(today)
                    val s = curMonth.atDay(1)
                    val e = today
                    val prevMonth = curMonth.minusMonths(1)
                    DateRangeComparison(s, e, prevMonth.atDay(1), prevMonth.atDay(minOf(today.dayOfMonth, prevMonth.lengthOfMonth())))
                }
            }
            ReportRange.CUSTOM -> {
                val s = customStart
                val e = customEnd
                val days = (e.toEpochDay() - s.toEpochDay() + 1L).coerceAtLeast(1L)
                DateRangeComparison(s, e, s.minusDays(days), s.minusDays(1))
            }
            else -> {
                val e = today
                val s = e.minusDays((range.days - 1).toLong())
                DateRangeComparison(s, e, s.minusDays(range.days.toLong()), s.minusDays(1))
            }
        }
    }
    val start = dateComparison.start
    val end = dateComparison.end
    val prevStart = dateComparison.prevStart
    val prevEnd = dateComparison.prevEnd
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
        // Matching every split against every event is quadratic and this list grows with history.
        val unexpectedIds = unexpectedEvents.mapTo(HashSet(unexpectedEvents.size)) { it.id }
        val splitMap = state.splits.filter { it.eventId in unexpectedIds }
        val categoryMap = state.categories.associateBy { it.id }
        splitMap.groupBy { it.categoryId }.map { (catId, splits) ->
            val catName = catId?.let { categoryMap[it]?.name } ?: "Tanpa kategori"
            val cat = catId?.let { categoryMap[it] }
            Triple(catName, splits.sumOf { it.amount }, cat?.direction)
        }.sortedByDescending { it.second }
    }
    var unexpectedExpanded by rememberSaveable { mutableStateOf(false) }
    val money: (Long) -> String = { value -> displayMoney(value, visible) }
    val rangeLabel = when (range) {
        ReportRange.CUSTOM -> "${shortDate(start)} sampai ${shortDate(end)}"
        ReportRange.THIS_MONTH -> if (today.dayOfMonth <= 7) "bulan lalu (${shortDate(start)} - ${shortDate(end)})" else "bulan ini (${shortDate(start)} - ${shortDate(end)})"
        else -> range.label.lowercase()
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("LAPORAN", style = MaterialTheme.typography.headlineMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Text("Ringkasan yang direkonstruksi dari jurnal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Akun aktif: ${state.activeAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                }
                Button(onClick = onExport, enabled = !readOnly, shape = com.morneven.kron.ui.theme.KronButtonShape) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text(if (readOnly) "Viewer" else "CSV")
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
    }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader(
                    if (mode == ReportMode.CUMULATIVE) "Perubahan saldo $rangeLabel" else "Cash flow $rangeLabel"
                )
                HudCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ReportKpiCard(
                            label = "Masuk",
                            value = money(income),
                            icon = Icons.Outlined.ArrowDownward,
                            color = KronGreen,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            indicator = {
                                ChangeIndicator(income, prevIncome, ComparisonType.INCOME, visible, ::compactChange)
                            },
                        )
                        ReportKpiCard(
                            label = "Keluar",
                            value = money(expense),
                            icon = Icons.Outlined.ArrowUpward,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            indicator = {
                                ChangeIndicator(expense, prevExpense, ComparisonType.EXPENSE, visible, ::compactChange)
                            },
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    NetCashFlowCard(
                        label = if (mode == ReportMode.CUMULATIVE) "Net Perubahan" else "Net Cash Flow",
                        value = money(net),
                        net = net,
                        indicator = {
                            ChangeIndicator(net, prevNet, ComparisonType.NET, visible, ::compactChange)
                        },
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !showTable, onClick = { showTable = false }, label = { Text("Grafik") })
                        FilterChip(selected = showTable, onClick = { showTable = true }, label = { Text("Tabel") })
                    }
                    if (!showTable) {
                        ChartLegend(effectiveSeries, mode)
                        Spacer(Modifier.height(8.dp))
                        CashFlowChart(
                            buckets = buckets,
                            series = effectiveSeries,
                            mode = mode,
                            selected = selectedPoint,
                            valuesVisible = visible,
                            onSelect = { selectedPoint = it },
                        )
                        Spacer(Modifier.height(8.dp))
                        if (selectedPoint in buckets.indices) {
                            val bucket = buckets[selectedPoint]
                            val rawBucket = if (mode == ReportMode.CUMULATIVE && selectedPoint in rawBuckets.indices) rawBuckets[selectedPoint] else null
                            ChartPointAuditDetails(
                                bucket = bucket,
                                rawBucket = rawBucket,
                                mode = mode,
                                selectedIndex = selectedPoint,
                                totalPoints = buckets.size,
                                money = money,
                                cashFlowEvents = cashFlowEvents,
                                onClose = { selectedPoint = -1 },
                            )
                        } else {
                            Surface(
                                shape = KronSubCardShape,
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.Outlined.Info,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        if (buckets.isEmpty()) "Belum ada cash flow pada rentang ini" else "Ketuk titik pada grafik untuk mengaudit tanggal & mutasi transaksi",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showTable) {
            val displayBuckets = if (mode == ReportMode.CUMULATIVE) {
                buckets.mapIndexed { index, cumulativeBucket ->
                    CashFlowBucket(
                        key = cumulativeBucket.key,
                        label = cumulativeBucket.label,
                        income = rawBuckets[index].income,
                        expense = rawBuckets[index].expense,
                        explicitNet = cumulativeBucket.net,
                    )
                }
            } else {
                buckets
            }
            val reversedBuckets = displayBuckets.reversed()
            items(reversedBuckets, key = { it.key }) { bucket ->
                val originalIndex = displayBuckets.indexOf(bucket)
                val prevBucket = if (originalIndex > 0) displayBuckets[originalIndex - 1] else null
                HudCard {
                    Text(bucket.label, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    BucketDetails(bucket, money, prevBucket, visible)
                }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Komposisi aset")
                HudCard {
                    if (channel in setOf(ReportChannel.ALL, ReportChannel.CASH)) {
                        AssetRow(FundingChannel.CASH, state.totalCashAssets, money)
                    }
                    if (channel == ReportChannel.ALL) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 10.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                        )
                    }
                    if (channel in setOf(ReportChannel.ALL, ReportChannel.EBUDGET)) {
                        AssetRow(FundingChannel.EBUDGET, state.totalEBudgetAssets, money)
                    }
                }
            }
        }

        if (reportDebts.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionHeader("Laporan Hutang")
                HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)) {
                    Text("Tracker ini tidak menambah saldo aset. Hanya pembayaran yang masuk ke cash flow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReportMetricCompact("Perlu dibayar", money(debtPayable), MaterialTheme.colorScheme.error, Modifier.weight(1f))
                        ReportMetricCompact("Akan diterima", money(debtReceivable), KronGreen, Modifier.weight(1f))
                    }
                    if (debtInterest > 0L) {
                        Spacer(Modifier.height(8.dp))
                        ReportMetricCompact("Bunga berjalan", money(debtInterest), KronGold, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
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

private data class DateRangeComparison(
    val start: LocalDate,
    val end: LocalDate,
    val prevStart: LocalDate,
    val prevEnd: LocalDate,
)

private enum class ReportRange(val label: String, val days: Int) {
    THIS_MONTH("Bulan ini", 0),
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
    val explicitNet: Long? = null,
) {
    val net: Long get() = explicitNet ?: (income - expense)
}

/**
 * Whether the event moved money in or out of the account.
 *
 * `DEBT_OPEN` belongs here: borrowing puts cash into a channel and lending takes it out. Repayments
 * are already counted, because they are posted as ordinary income and expense events, so leaving
 * the opening movement out made every debt look like a one-sided loss or gain.
 */
private fun ActivityRow.isCashFlowEvent(): Boolean = when (type) {
    "INCOME", "OPENING_BALANCE", "EXPENSE", "UNEXPECTED_EXPENSE", "AUTOMATION", "TRANSFER", "DEBT_OPEN" -> cashImpact != 0L
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
private fun ReportKpiCard(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    modifier: Modifier = Modifier,
    indicator: (@Composable () -> Unit)? = null,
) {
    val isDark = LocalKronDarkTheme.current
    Surface(
        modifier = modifier,
        shape = KronSubCardShape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, color.copy(alpha = if (isDark) 0.25f else 0.40f)),
        shadowElevation = if (isDark) 0.dp else 1.5.dp,
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
                Box(
                    modifier = Modifier
                        .size(26.dp)
                        .background(color.copy(alpha = 0.12f), RoundedCornerShape(7.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = color,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 18.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (indicator != null) {
                    indicator()
                }
            }
        }
    }
}

@Composable
private fun NetCashFlowCard(
    label: String,
    value: String,
    net: Long,
    indicator: (@Composable () -> Unit)? = null,
) {
    val netColor = when {
        net > 0 -> KronGreen
        net < 0 -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.AccountBalanceWallet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(
                        label.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        softWrap = false,
                    )
                    Box(
                        modifier = Modifier.heightIn(min = 16.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (indicator != null) {
                            indicator()
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = netColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReportMetricCompact(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = KronSubCardShape,
        border = BorderStroke(1.dp, color.copy(alpha = 0.20f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
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
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, softWrap = false)
        Text(value, style = MaterialTheme.typography.labelLarge, color = color, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChartLegend(series: ReportSeries, mode: ReportMode) {
    val entries = when {
        mode == ReportMode.CUMULATIVE -> listOf("Akumulasi Net" to KronGold)
        series == ReportSeries.ALL -> listOf("Masuk" to KronGreen, "Keluar" to MaterialTheme.colorScheme.error)
        series == ReportSeries.INCOME -> listOf("Masuk" to KronGreen)
        series == ReportSeries.EXPENSE -> listOf("Keluar" to MaterialTheme.colorScheme.error)
        series == ReportSeries.NET -> listOf("Net" to KronGold)
        else -> listOf("Net" to KronGold)
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
    val isCash = channel == FundingChannel.CASH
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        if (isCash) KronGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        RoundedCornerShape(10.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (isCash) Icons.Outlined.Payments else Icons.Outlined.AccountBalance,
                    contentDescription = null,
                    tint = if (isCash) KronGreen else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                ChannelBadge(channel)
                Text(
                    if (isCash) "Kas tunai & fisik" else "Akun bank & digital",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            money(value),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CashFlowChart(
    buckets: List<CashFlowBucket>,
    series: ReportSeries,
    mode: ReportMode,
    selected: Int,
    valuesVisible: Boolean,
    onSelect: (Int) -> Unit,
) {
    val isBarChart = mode == ReportMode.CASH_FLOW

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

    val minimum = if (isBarChart) {
        if (series == ReportSeries.NET) {
            minOf(0L, buckets.minOfOrNull { it.net } ?: 0L)
        } else {
            0L
        }
    } else {
        val values = visibleSeries.flatMap { it.first }
        minOf(0L, values.minOrNull() ?: 0L)
    }

    val maximum = if (isBarChart) {
        when (series) {
            ReportSeries.ALL -> maxOf(0L, buckets.maxOfOrNull { maxOf(it.income, it.expense) } ?: 0L)
            ReportSeries.INCOME -> maxOf(0L, buckets.maxOfOrNull { it.income } ?: 0L)
            ReportSeries.EXPENSE -> maxOf(0L, buckets.maxOfOrNull { it.expense } ?: 0L)
            ReportSeries.NET -> maxOf(0L, buckets.maxOfOrNull { it.net } ?: 0L)
        }
    } else {
        val values = visibleSeries.flatMap { it.first }
        maxOf(0L, values.maxOrNull() ?: 0L)
    }

    val span = (maximum - minimum).coerceAtLeast(1L)
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)
    val selectedColor = MaterialTheme.colorScheme.onSurface
    val primaryColor = MaterialTheme.colorScheme.primary
    val errorColor = MaterialTheme.colorScheme.error
    val greenColor = KronGreen
    val goldColor = KronGold
    val accessibilityLabel = if (valuesVisible) {
        if (isBarChart) {
            "Grafik batang cash flow dengan ${buckets.size} periode."
        } else {
            "Grafik garis cash flow dengan ${buckets.size} periode. Gunakan tampilan tabel untuk membaca seluruh nilai."
        }
    } else {
        "Grafik cash flow dengan nilai disembunyikan."
    }

    Column(Modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = accessibilityLabel }
                .pointerInput(buckets, isBarChart) {
                    detectTapGestures { offset ->
                        if (buckets.isNotEmpty()) {
                            val denominator = size.width.coerceAtLeast(1)
                            val index = if (isBarChart) {
                                ((offset.x / denominator) * buckets.size).toInt().coerceIn(0, buckets.lastIndex)
                            } else {
                                ((offset.x / denominator) * buckets.lastIndex).roundToInt().coerceIn(0, buckets.lastIndex)
                            }
                            onSelect(index)
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

            if (isBarChart) {
                val slotW = size.width / buckets.size.toFloat()

                if (selected in buckets.indices) {
                    val selX = selected * slotW + slotW / 2f
                    drawLine(
                        color = primaryColor.copy(alpha = 0.55f),
                        start = Offset(selX, 0f),
                        end = Offset(selX, size.height),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                    )
                }

                buckets.forEachIndexed { index, bucket ->
                    val slotCenter = index * slotW + slotW / 2f
                    val isSelected = index == selected
                    val alpha = if (selected == -1 || isSelected) 0.95f else 0.45f

                    when (series) {
                        ReportSeries.ALL -> {
                            val totalBarW = (slotW * 0.75f).coerceIn(4f, 26f)
                            val singleBarW = (totalBarW / 2f).coerceAtLeast(2f)
                            val gap = 1f

                            val incY = zeroY - (bucket.income.toFloat() / span.toFloat()) * size.height
                            val incH = (zeroY - incY).coerceAtLeast(if (bucket.income > 0) 2f else 0f)
                            if (incH > 0f) {
                                drawRoundRect(
                                    color = greenColor.copy(alpha = alpha),
                                    topLeft = Offset(slotCenter - totalBarW / 2f, incY),
                                    size = Size(singleBarW - gap, incH),
                                    cornerRadius = CornerRadius(3f, 3f),
                                )
                            }

                            val expY = zeroY - (bucket.expense.toFloat() / span.toFloat()) * size.height
                            val expH = (zeroY - expY).coerceAtLeast(if (bucket.expense > 0) 2f else 0f)
                            if (expH > 0f) {
                                drawRoundRect(
                                    color = errorColor.copy(alpha = alpha),
                                    topLeft = Offset(slotCenter - totalBarW / 2f + singleBarW, expY),
                                    size = Size(singleBarW - gap, expH),
                                    cornerRadius = CornerRadius(3f, 3f),
                                )
                            }
                        }
                        ReportSeries.INCOME -> {
                            val barW = (slotW * 0.65f).coerceIn(3f, 22f)
                            val incY = zeroY - (bucket.income.toFloat() / span.toFloat()) * size.height
                            val incH = (zeroY - incY).coerceAtLeast(if (bucket.income > 0) 2f else 0f)
                            if (incH > 0f) {
                                drawRoundRect(
                                    color = greenColor.copy(alpha = alpha),
                                    topLeft = Offset(slotCenter - barW / 2f, incY),
                                    size = Size(barW, incH),
                                    cornerRadius = CornerRadius(3f, 3f),
                                )
                            }
                        }
                        ReportSeries.EXPENSE -> {
                            val barW = (slotW * 0.65f).coerceIn(3f, 22f)
                            val expY = zeroY - (bucket.expense.toFloat() / span.toFloat()) * size.height
                            val expH = (zeroY - expY).coerceAtLeast(if (bucket.expense > 0) 2f else 0f)
                            if (expH > 0f) {
                                drawRoundRect(
                                    color = errorColor.copy(alpha = alpha),
                                    topLeft = Offset(slotCenter - barW / 2f, expY),
                                    size = Size(barW, expH),
                                    cornerRadius = CornerRadius(3f, 3f),
                                )
                            }
                        }
                        ReportSeries.NET -> {
                            val barW = (slotW * 0.65f).coerceIn(3f, 22f)
                            val netY = size.height - ((bucket.net - minimum).toFloat() / span.toFloat()) * size.height
                            val topY = minOf(zeroY, netY)
                            val bottomY = maxOf(zeroY, netY)
                            val barH = (bottomY - topY).coerceAtLeast(if (bucket.net != 0L) 2f else 0f)
                            val color = if (bucket.net >= 0) greenColor else errorColor
                            if (barH > 0f) {
                                drawRoundRect(
                                    color = color.copy(alpha = alpha),
                                    topLeft = Offset(slotCenter - barW / 2f, topY),
                                    size = Size(barW, barH),
                                    cornerRadius = CornerRadius(3f, 3f),
                                )
                            }
                        }
                    }
                }
            } else {
                val step = if (buckets.size == 1) size.width else size.width / buckets.lastIndex

                if (selected in buckets.indices && buckets.isNotEmpty()) {
                    val selX = if (buckets.size == 1) size.width * 0.5f else selected * step
                    drawLine(
                        color = primaryColor.copy(alpha = 0.55f),
                        start = Offset(selX, 0f),
                        end = Offset(selX, size.height),
                        strokeWidth = 1.5f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f),
                    )
                }

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

        if (buckets.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            ChartTimeAxis(buckets = buckets, selected = selected)
        }
    }
}

@Composable
private fun ChartTimeAxis(
    buckets: List<CashFlowBucket>,
    selected: Int,
) {
    if (buckets.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val indices = when {
            buckets.size <= 2 -> buckets.indices.toList()
            buckets.size in 3..6 -> listOf(0, buckets.size / 2, buckets.lastIndex)
            else -> listOf(
                0,
                (buckets.size - 1) / 3,
                ((buckets.size - 1) * 2) / 3,
                buckets.lastIndex,
            )
        }
        indices.forEach { index ->
            val bucket = buckets[index]
            val isPointSelected = index == selected
            Text(
                text = compactAxisLabel(bucket),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (isPointSelected) FontWeight.Bold else FontWeight.Normal,
                color = if (isPointSelected) KronGold else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

@Composable
private fun ChartPointAuditDetails(
    bucket: CashFlowBucket,
    rawBucket: CashFlowBucket?,
    mode: ReportMode,
    selectedIndex: Int,
    totalPoints: Int,
    money: (Long) -> String,
    cashFlowEvents: List<ActivityRow>,
    onClose: () -> Unit,
) {
    val isDaily = bucket.key.startsWith("D:")
    val epochDay = if (isDaily) bucket.key.removePrefix("D:").toLongOrNull() else null
    val isMonthly = bucket.key.startsWith("M:")
    val monthStr = if (isMonthly) bucket.key.removePrefix("M:") else null

    val dayEvents = remember(cashFlowEvents, epochDay, monthStr) {
        when {
            epochDay != null -> cashFlowEvents.filter { it.effectiveEpochDay == epochDay }
            monthStr != null -> cashFlowEvents.filter {
                YearMonth.from(LocalDate.ofEpochDay(it.effectiveEpochDay)).toString() == monthStr
            }
            else -> emptyList()
        }
    }

    val isDark = LocalKronDarkTheme.current
    Surface(
        shape = KronSubCardShape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = if (isDark) 0.35f else 0.70f)),
        shadowElevation = if (isDark) 0.dp else 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .background(KronGold.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Outlined.CalendarMonth,
                            contentDescription = null,
                            tint = KronGold,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            bucket.label,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "Titik ${selectedIndex + 1} dari $totalPoints periode",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Tutup audit tanggal",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            val displayIncome = if (mode == ReportMode.CUMULATIVE && rawBucket != null) rawBucket.income else bucket.income
            val displayExpense = if (mode == ReportMode.CUMULATIVE && rawBucket != null) rawBucket.expense else bucket.expense
            val netOfPeriod = displayIncome - displayExpense

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ReportValueRow("Masuk pada tanggal ini", money(displayIncome), KronGreen)
                ReportValueRow("Keluar pada tanggal ini", money(displayExpense), MaterialTheme.colorScheme.error)
                ReportValueRow("Net mutasi", money(netOfPeriod), if (netOfPeriod >= 0) KronGreen else MaterialTheme.colorScheme.error)
                if (mode == ReportMode.CUMULATIVE) {
                    ReportValueRow("Akumulasi net s.d. tanggal ini", money(bucket.net), KronGold)
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            if (dayEvents.isNotEmpty()) {
                Text(
                    "Transaksi tercatat (${dayEvents.size}):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    dayEvents.forEach { event ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val title = event.note.takeIf { it.isNotBlank() } ?: when (event.type) {
                                "INCOME" -> "Pemasukan"
                                "EXPENSE" -> "Pengeluaran"
                                "TRANSFER" -> "Transfer"
                                "UNEXPECTED_EXPENSE" -> "Pengeluaran Tak Terduga"
                                "OPENING_BALANCE" -> "Saldo Awal"
                                "DEBT_OPEN" -> "Pencatatan Hutang"
                                else -> event.type
                            }
                            Text(
                                title,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(8.dp))
                            val isPositive = event.cashImpact > 0
                            val sign = if (isPositive) "+" else if (event.cashImpact < 0) "-" else ""
                            Text(
                                "$sign${money(abs(event.cashImpact))}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (isPositive) KronGreen else if (event.cashImpact < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                softWrap = false,
                            )
                        }
                    }
                }
            } else {
                Text(
                    if (isDaily) "Tidak ada mutasi finansial pada tanggal ini." else "Tidak ada transaksi tercatat pada bulan ini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val monthFormat = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("id-ID"))
private val dateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
private val axisDateFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("id-ID"))
private fun shortDate(date: LocalDate): String = date.format(dateFormat)

private fun compactAxisLabel(bucket: CashFlowBucket): String {
    if (bucket.key.startsWith("D:")) {
        val epochDay = bucket.key.removePrefix("D:").toLongOrNull()
        if (epochDay != null) {
            return LocalDate.ofEpochDay(epochDay).format(axisDateFormatter)
        }
    }
    return bucket.label
}
