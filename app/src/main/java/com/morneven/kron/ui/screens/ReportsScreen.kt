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
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.Metric
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.compactIdr
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.signedColor
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ReportsScreen(state: KronUiState, onExport: () -> Unit, modifier: Modifier = Modifier) {
    val visible = state.valuesVisible
    var range by remember { mutableStateOf("30 hari") }
    var flowFilter by remember { mutableStateOf("Semua") }
    var selectedPoint by remember { mutableIntStateOf(-1) }
    var expandedPeriod by remember { mutableStateOf<Long?>(null) }
    var customStart by remember { mutableStateOf(LocalDate.now().minusDays(29)) }
    var customEnd by remember { mutableStateOf(LocalDate.now()) }
    var pickingStart by remember { mutableStateOf(true) }
    var showDatePicker by remember { mutableStateOf(false) }

    val end = if (range == "Custom") customEnd else LocalDate.now()
    val dayCount = when (range) {
        "7 hari" -> 7
        "90 hari" -> 90
        "1 tahun" -> 365
        "Custom" -> (customEnd.toEpochDay() - customStart.toEpochDay() + 1).toInt().coerceIn(1, 3_650)
        else -> 30
    }
    val start = if (range == "Custom") customStart else end.minusDays((dayCount - 1).toLong())
    val events = remember(state.activities, start, end, flowFilter) {
        state.activities.filter { event ->
            val date = LocalDate.ofEpochDay(event.effectiveEpochDay)
            val typeMatches = when (flowFilter) {
                "Masuk" -> event.type in setOf("INCOME", "OPENING_BALANCE")
                "Keluar" -> event.type in setOf("EXPENSE", "UNEXPECTED_EXPENSE")
                else -> event.type in setOf("INCOME", "OPENING_BALANCE", "EXPENSE", "UNEXPECTED_EXPENSE", "AUTOMATION")
            }
            !date.isBefore(start) && !date.isAfter(end) && typeMatches && event.reversedByEventId == null
        }
    }
    val points = remember(events, start, dayCount) {
        (0 until dayCount).map { offset ->
            val date = start.plusDays(offset.toLong())
            events.filter { LocalDate.ofEpochDay(it.effectiveEpochDay) == date }.sumOf { event ->
                if (event.cashImpact >= 0) event.cashImpact else event.cashImpact
            }
        }
    }
    val income = events.filter { it.cashImpact > 0 }.sumOf { it.cashImpact }
    val expense = -events.filter { it.cashImpact < 0 }.sumOf { it.cashImpact }
    val net = income - expense
    val periods = state.allocations.groupBy { it.periodId }.entries.sortedByDescending { it.value.firstOrNull()?.startEpochDay ?: 0L }
    val unexpectedTotal = events.filter { it.type == "UNEXPECTED_EXPENSE" }.sumOf { -it.cashImpact }
    val money: (Long) -> String = { value -> if (visible) compactIdr(value) else displayMoney(value, false) }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("LAPORAN", style = MaterialTheme.typography.headlineMedium)
                    Text("Ringkasan jurnal dan budget", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onExport) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text("CSV")
                }
            }
        }
        item {
            SectionHeader("Rentang analitik")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(listOf("7 hari", "30 hari", "90 hari", "1 tahun", "Custom")) { item ->
                    FilterChip(selected = range == item, onClick = { range = item; selectedPoint = -1 }, label = { Text(item) })
                }
            }
            if (range == "Custom") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { pickingStart = true; showDatePicker = true }, modifier = Modifier.weight(1f)) { Text("Mulai\n${shortDate(customStart)}") }
                    TextButton(onClick = { pickingStart = false; showDatePicker = true }, modifier = Modifier.weight(1f)) { Text("Sampai\n${shortDate(customEnd)}") }
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(listOf("Semua", "Masuk", "Keluar")) { item ->
                    FilterChip(selected = flowFilter == item, onClick = { flowFilter = item; selectedPoint = -1 }, label = { Text(item) })
                }
            }
        }
        item {
            HudCard {
                SectionHeader("Cash flow $range")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Metric("Masuk", money(income), Modifier.weight(1f), KronGreen)
                    Metric("Keluar", money(expense), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                    Metric("Net", money(net), Modifier.weight(1f), signedColor(net))
                }
                Spacer(Modifier.height(18.dp))
                FlowChart(points, selectedPoint, onSelect = { selectedPoint = it })
                Spacer(Modifier.height(8.dp))
                if (selectedPoint >= 0) {
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(shortDate(start.plusDays(selectedPoint.toLong())), style = MaterialTheme.typography.bodySmall)
                            Text(money(points[selectedPoint]), style = MaterialTheme.typography.labelLarge, color = signedColor(points[selectedPoint]))
                        }
                    }
                } else {
                    Text("Tekan titik grafik untuk melihat detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            HudCard {
                SectionHeader("Komposisi aset")
                AssetRow(FundingChannel.CASH, state.accountBalances.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.balance }, money)
                Spacer(Modifier.height(12.dp))
                AssetRow(FundingChannel.EBUDGET, state.accountBalances.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.balance }, money)
            }
        }
        if (periods.isNotEmpty()) item { SectionHeader("Aktual per budget") }
        items(periods, key = { it.key }) { (periodId, rows) ->
            val planned = rows.sumOf { it.plannedAmount }
            val booked = rows.sumOf { it.bookedAmount }
            val spent = rows.sumOf { it.spentAmount }
            val available = rows.sumOf { it.availableAmount }
            val expanded = expandedPeriod == periodId
            HudCard(modifier = Modifier.fillMaxWidth().clickable { expandedPeriod = if (expanded) null else periodId }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rows.first().portfolioName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${shortDate(LocalDate.ofEpochDay(rows.first().startEpochDay))} sampai ${shortDate(LocalDate.ofEpochDay(rows.first().endEpochDay))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(money(available), style = MaterialTheme.typography.labelLarge, color = signedColor(available))
                    Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = if (expanded) "Tutup rincian" else "Lihat rincian")
                }
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (booked <= 0) 0f else (spent.toFloat() / booked).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = if (available < 0) MaterialTheme.colorScheme.error else KronGold,
                )
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Rencana ${money(planned)}", style = MaterialTheme.typography.bodySmall)
                    Text("Terpakai ${money(spent)}", style = MaterialTheme.typography.bodySmall)
                }
                if (expanded) {
                    Spacer(Modifier.height(12.dp))
                    rows.forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(row.categoryName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ChannelBadge(row.fundingChannel)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(money(row.spentAmount), style = MaterialTheme.typography.labelLarge)
                                Text("dari ${money(row.plannedAmount)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
        if (unexpectedTotal > 0) item {
            HudCard(accent = MaterialTheme.colorScheme.error) {
                Text("Pengeluaran tak terduga", style = MaterialTheme.typography.titleMedium)
                Text(money(unexpectedTotal), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.error)
                Text("Tidak mengurangi alokasi budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }

    if (showDatePicker) {
        val selectedDate = if (pickingStart) customStart else customEnd
        val picker = rememberDatePickerState(initialSelectedDateMillis = selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (pickingStart) customStart = minOf(date, customEnd) else customEnd = maxOf(date, customStart)
                    }
                    showDatePicker = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Batal") } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun AssetRow(channel: String, value: Long, money: (Long) -> String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        ChannelBadge(channel)
        Text(money(value), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun FlowChart(points: List<Long>, selected: Int, onSelect: (Int) -> Unit) {
    val maxMagnitude = points.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1L) ?: 1L
    val gold = KronGold
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(164.dp)
            .pointerInput(points) {
                detectTapGestures { offset ->
                    if (points.isNotEmpty()) onSelect(((offset.x / size.width) * points.lastIndex).roundToInt().coerceIn(0, points.lastIndex))
                }
            },
    ) {
        repeat(4) { index ->
            val y = size.height * index / 3f
            drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
        }
        if (points.isEmpty()) return@Canvas
        val step = if (points.size == 1) size.width else size.width / points.lastIndex
        val coordinates = points.mapIndexed { index, value ->
            Offset(index * step, size.height / 2f - (value.toFloat() / maxMagnitude) * (size.height / 2f - 10f))
        }
        coordinates.zipWithNext().forEach { (start, end) -> drawLine(gold, start, end, 3.5f) }
        coordinates.forEachIndexed { index, point ->
            if (points.size <= 90 || index == selected) drawCircle(if (index == selected) Color.White else gold, if (index == selected) 7f else 3.5f, point)
        }
    }
}

private fun shortDate(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID")))
