package com.morneven.kron.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.Metric
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.signedColor
import com.morneven.kron.ui.theme.KronGreen
import java.time.LocalDate
import java.time.ZoneOffset
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
    val days = when (range) { "7 hari" -> 7; "90 hari" -> 90; "1 tahun" -> 365; "Custom" -> (customEnd.toEpochDay() - customStart.toEpochDay() + 1).toInt().coerceIn(1, 3650); else -> 30 }
    val start = if (range == "Custom") customStart else end.minusDays((days - 1).toLong())
    val events = state.activities.filter { event ->
        val inRange = LocalDate.ofEpochDay(event.effectiveEpochDay) in start..end
        val typeMatch = when (flowFilter) {
            "Masuk" -> event.type == "INCOME" || event.type == "OPENING_BALANCE"
            "Keluar" -> event.type == "EXPENSE" || event.type == "UNEXPECTED_EXPENSE"
            else -> true
        }
        inRange && typeMatch && event.reversedByEventId == null
    }
    val points = (0 until days).map { offset ->
        val date = start.plusDays(offset.toLong())
        events.filter { LocalDate.ofEpochDay(it.effectiveEpochDay) == date }.sumOf { event ->
            if (event.type == "INCOME" || event.type == "OPENING_BALANCE") event.cashImpact else -event.cashImpact
        }
    }
    val periods = state.allocations.groupBy { it.periodId }.entries.toList()
    val spentByCategory = state.allocations.groupBy { it.categoryName }.mapValues { (_, rows) -> rows.sumOf { it.spentAmount } }.entries.sortedByDescending { it.value }
    val unexpectedTotal = state.activities.filter { it.type == "UNEXPECTED_EXPENSE" && it.reversedByEventId == null }.sumOf { -it.cashImpact }
    LazyColumn(modifier = modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("LAPORAN", style = MaterialTheme.typography.headlineMedium)
                    Text("Analitik yang direkonstruksi dari jurnal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onExport) { Text("CSV") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                listOf("7 hari", "30 hari", "90 hari", "1 tahun", "Custom").forEach { item -> FilterChip(selected = range == item, onClick = { range = item; selectedPoint = -1 }, label = { Text(item) }) }
            }
            if (range == "Custom") Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                TextButton(onClick = { pickingStart = true; showDatePicker = true }) { Text("Mulai: $customStart") }
                TextButton(onClick = { pickingStart = false; showDatePicker = true }) { Text("Sampai: $customEnd") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                listOf("Semua", "Masuk", "Keluar").forEach { item -> FilterChip(selected = flowFilter == item, onClick = { flowFilter = item; selectedPoint = -1 }, label = { Text(item) }) }
            }
        }
        item {
            HudCard {
                SectionHeader("Cash flow $range")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val income = events.filter { it.type == "INCOME" || it.type == "OPENING_BALANCE" }.sumOf { it.cashImpact }
                    val expense = events.filter { it.type == "EXPENSE" || it.type == "UNEXPECTED_EXPENSE" }.sumOf { -it.cashImpact }
                    Metric("Masuk", displayMoney(income, visible), Modifier.weight(1f), KronGreen)
                    Metric("Keluar", displayMoney(expense, visible), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(12.dp))
                FlowChart(points, visible, selectedPoint, { selectedPoint = it })
                if (selectedPoint >= 0) Text("${start.plusDays(selectedPoint.toLong())}: ${displayMoney(points[selectedPoint], visible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        item {
            HudCard {
                SectionHeader("Komposisi aset")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) { ChannelBadge(FundingChannel.CASH); Text(displayMoney(state.accountBalances.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.balance }, visible)) }
                    Column(Modifier.weight(1f)) { ChannelBadge(FundingChannel.EBUDGET); Text(displayMoney(state.accountBalances.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.balance }, visible)) }
                }
            }
        }
        if (periods.isNotEmpty()) item { SectionHeader("Aktual per budget") }
        items(periods, key = { it.key }) { (periodId, rows) ->
            val spent = rows.sumOf { it.spentAmount }
            val planned = rows.sumOf { it.plannedAmount }
            val available = rows.sumOf { it.availableAmount }
            HudCard(modifier = Modifier.clickable { expandedPeriod = if (expandedPeriod == periodId) null else periodId }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(rows.first().portfolioName, style = MaterialTheme.typography.titleMedium); Text("Tekan untuk melihat kategori", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(displayMoney(spent, visible), color = signedColor(-spent))
                }
                Text("Rencana ${displayMoney(planned, visible)} · Sisa ${displayMoney(available, visible)}", style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(progress = { if (planned == 0L) 0f else (spent.toFloat() / planned).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.tertiary)
                if (expandedPeriod == periodId) rows.forEach { row -> Text("${row.categoryName} · ${displayMoney(row.spentAmount, visible)} / ${displayMoney(row.plannedAmount, visible)}", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
            }
        }
        if (unexpectedTotal > 0) item {
            HudCard { Text("Pengeluaran tak terduga", style = MaterialTheme.typography.titleMedium); Text(displayMoney(unexpectedTotal, visible), color = MaterialTheme.colorScheme.error) }
        }
        if (spentByCategory.isNotEmpty()) item { Text("Ringkasan kategori", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary) }
        item { Spacer(Modifier.height(80.dp)) }
    }
    if (range == "Custom" && showDatePicker) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = (if (pickingStart) customStart else customEnd).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(onDismissRequest = { showDatePicker = false }, confirmButton = { TextButton(onClick = { picker.selectedDateMillis?.let { val date = java.time.Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate(); if (pickingStart) customStart = date else customEnd = date }; showDatePicker = false }) { Text("Pilih") } }, dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Batal") } }) { DatePicker(picker) }
    }
}

@Composable
private fun FlowChart(points: List<Long>, visible: Boolean, selected: Int, onSelect: (Int) -> Unit) {
    val max = points.maxOfOrNull { kotlin.math.abs(it) }?.coerceAtLeast(1L) ?: 1L
    Canvas(Modifier.fillMaxWidth().height(150.dp).pointerInput(points) { detectTapGestures { offset -> onSelect(((offset.x / size.width) * points.size).roundToInt().coerceIn(0, points.lastIndex)) } }) {
        val step = if (points.size == 1) size.width else size.width / (points.size - 1)
        val path = points.mapIndexed { index, value -> Offset(index * step, size.height / 2f - (value.toFloat() / max) * (size.height / 2f - 8f)) }
        path.zipWithNext().forEach { (a, b) -> drawLine(Color(0xFFE0B84C), a, b, 4f) }
        path.forEachIndexed { index, point -> drawCircle(if (index == selected) Color.White else Color(0xFFE0B84C), if (index == selected) 7f else 4f, point) }
    }
}
