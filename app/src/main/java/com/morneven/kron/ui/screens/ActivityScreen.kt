package com.morneven.kron.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.EmptyState
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.signedColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun ActivityScreen(state: KronUiState, onEvent: (String) -> Unit, modifier: Modifier = Modifier) {
    var typeFilter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var dateFilter by rememberSaveable { mutableStateOf(ActivityDateRange.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var visibleEventCount by rememberSaveable { mutableIntStateOf(PAGE_SIZE) }
    val listState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val today = LocalDate.now()
    val shown = remember(state.activities, typeFilter, dateFilter, query, today) {
        val minimumDay = dateFilter.days?.let { today.minusDays((it - 1).toLong()).toEpochDay() }
        state.activities.filter { event ->
            event.matches(typeFilter) &&
                (minimumDay == null || event.effectiveEpochDay >= minimumDay) &&
                event.matches(query)
        }
    }
    val visibleEvents = remember(shown, visibleEventCount) { shown.take(visibleEventCount) }
    val timeline = remember(visibleEvents, today) { visibleEvents.toTimeline(today) }

    LaunchedEffect(typeFilter, dateFilter, query, state.activeAccount?.id) {
        visibleEventCount = PAGE_SIZE
    }
    LaunchedEffect(listState, timeline.size, shown.size) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .collect { lastVisibleIndex ->
                if (lastVisibleIndex >= timeline.size - LOAD_MORE_THRESHOLD && visibleEventCount < shown.size) {
                    visibleEventCount = min(visibleEventCount + PAGE_SIZE, shown.size)
                }
            }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("TRANSAKSI & AUDIT", style = MaterialTheme.typography.headlineMedium)
            Text("Semua kejadian tetap tersimpan dan dapat ditelusuri", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Akun aktif: ${state.activeAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }
        item {
            ActivitySearchField(query = query, onQuery = { query = it }, focusManager = focusManager)
        }
        item {
            Text("Jenis aktivitas", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(ActivityFilter.entries, key = { it.name }) { item ->
                    FilterChip(selected = typeFilter == item, onClick = { typeFilter = item }, label = { Text(item.label) })
                }
            }
            Text("Rentang tanggal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                items(ActivityDateRange.entries, key = { it.name }) { item ->
                    FilterChip(selected = dateFilter == item, onClick = { dateFilter = item }, label = { Text(item.label) })
                }
            }
            Text(
                if (shown.size == state.activities.size) "${shown.size} aktivitas" else "${shown.size} dari ${state.activities.size} aktivitas",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        if (shown.isEmpty()) {
            item {
                EmptyState(
                    Icons.AutoMirrored.Outlined.ReceiptLong,
                    if (state.activities.isEmpty()) "Belum ada aktivitas" else "Aktivitas tidak ditemukan",
                    if (state.activities.isEmpty()) {
                        "Transaksi dan perubahan budget akan tampil di sini."
                    } else {
                        "Coba ubah kata pencarian, jenis, atau rentang tanggal."
                    },
                )
            }
        }

        items(
            items = timeline,
            key = { it.key },
            contentType = { if (it is ActivityTimelineItem.Header) "header" else "event" },
        ) { item ->
            when (item) {
                is ActivityTimelineItem.Header -> Text(
                    item.label.uppercase(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                is ActivityTimelineItem.Event -> ActivityCard(item.value, state.valuesVisible, onEvent)
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

@Composable
private fun ActivitySearchField(query: String, onQuery: (String) -> Unit, focusManager: FocusManager) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Cari aktivitas") },
        placeholder = { Text("Judul, catatan, tipe, atau sumber") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (query.isNotEmpty()) {
            {
                IconButton(onClick = { onQuery("") }) {
                    Icon(Icons.Outlined.Close, contentDescription = "Hapus pencarian")
                }
            }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
    )
}

@Composable
private fun ActivityCard(event: ActivityRow, valuesVisible: Boolean, onEvent: (String) -> Unit) {
    val impact = event.primaryImpact()
    val money = displayMoney(impact, valuesVisible)
    val reversed = event.reversedByEventId != null
    val auditOnly = reversed || event.type in setOf("ARCHIVE", "RESTORE", "REVERSAL")
    HudCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEvent(event.id) }
            .semantics(mergeDescendants = true) { role = Role.Button },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
                Text(
                    event.title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium.copy(textDecoration = if (reversed) TextDecoration.LineThrough else null),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                ActivityStatus(event)
            }
            Text(
                money,
                color = signedColor(impact),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                eventTypeLabel(event.type),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Text(
                LocalDate.ofEpochDay(event.effectiveEpochDay).format(fullDateFormat),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (event.note.isNotBlank()) {
                Text(event.note, style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text(
                if (auditOnly) "Ketuk untuk melihat audit" else "Ketuk untuk melihat audit atau membuat koreksi",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ActivityStatus(event: ActivityRow) {
    val label = when {
        event.reversedByEventId != null -> "DIBATALKAN"
        event.type == "ARCHIVE" -> "ARSIP"
        event.type == "RESTORE" -> "DIPULIHKAN"
        event.source == "SYSTEM" -> "SISTEM"
        event.source == "AUTOMATION" || event.type == "AUTOMATION" -> "OTOMATIS"
        else -> "TERCATAT"
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.extraSmall,
    ) {
        Text(label, modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

private enum class ActivityFilter(val label: String) {
    ALL("Semua"),
    MONEY("Uang"),
    BUDGET("Budget"),
    AUTOMATIC("Otomatis"),
    RESOLUTION("Resolusi"),
    SYSTEM("Sistem"),
}

private enum class ActivityDateRange(val label: String, val days: Int?) {
    ALL("Semua waktu", null),
    SEVEN_DAYS("7 hari", 7),
    THIRTY_DAYS("30 hari", 30),
    NINETY_DAYS("90 hari", 90),
    ONE_YEAR("1 tahun", 365),
}

private sealed interface ActivityTimelineItem {
    val key: String

    data class Header(val date: LocalDate, val label: String) : ActivityTimelineItem {
        override val key: String = "header:${date.toEpochDay()}"
    }

    data class Event(val value: ActivityRow) : ActivityTimelineItem {
        override val key: String = "event:${value.id}"
    }
}

private fun List<ActivityRow>.toTimeline(today: LocalDate): List<ActivityTimelineItem> = buildList {
    var previousDay: Long? = null
    this@toTimeline.forEach { event ->
        if (event.effectiveEpochDay != previousDay) {
            val date = LocalDate.ofEpochDay(event.effectiveEpochDay)
            val label = when (date) {
                today -> "Hari ini"
                today.minusDays(1) -> "Kemarin"
                else -> date.format(groupDateFormat)
            }
            add(ActivityTimelineItem.Header(date, label))
            previousDay = event.effectiveEpochDay
        }
        add(ActivityTimelineItem.Event(event))
    }
}

private fun ActivityRow.matches(filter: ActivityFilter): Boolean = when (filter) {
    ActivityFilter.MONEY -> type in setOf("INCOME", "EXPENSE", "UNEXPECTED_EXPENSE", "TRANSFER", "OPENING_BALANCE", "CHANNEL_TRANSFER")
    ActivityFilter.BUDGET -> type in setOf("PORTFOLIO_BOOKING", "REALLOCATION", "OVERBUDGET_COVERAGE", "RELEASE", "ROLLOVER", "CORRECTION")
    ActivityFilter.AUTOMATIC -> type == "AUTOMATION" || source == "AUTOMATION"
    ActivityFilter.RESOLUTION -> type in setOf("REALLOCATION", "OVERBUDGET_COVERAGE")
    ActivityFilter.SYSTEM -> source == "SYSTEM" || type in setOf("REVERSAL", "ARCHIVE", "RESTORE")
    ActivityFilter.ALL -> true
}

private fun ActivityRow.matches(query: String): Boolean {
    val search = query.trim()
    if (search.isEmpty()) return true
    return title.contains(search, ignoreCase = true) ||
        note.contains(search, ignoreCase = true) ||
        type.contains(search, ignoreCase = true) ||
        source.contains(search, ignoreCase = true) ||
        eventTypeLabel(type).contains(search, ignoreCase = true)
}

private fun ActivityRow.primaryImpact(): Long = when {
    cashImpact != 0L -> cashImpact
    vaultImpact != 0L -> vaultImpact
    else -> budgetImpact
}

private fun eventTypeLabel(type: String): String = when (type) {
    "INCOME" -> "Pemasukan"
    "EXPENSE" -> "Pengeluaran"
    "UNEXPECTED_EXPENSE" -> "Pengeluaran tak terduga"
    "TRANSFER" -> "Transfer antar akun"
    "CHANNEL_TRANSFER" -> "Transfer antar kanal"
    "OPENING_BALANCE" -> "Saldo awal"
    "PORTFOLIO_BOOKING" -> "Booking budget"
    "REALLOCATION" -> "Realokasi budget"
    "OVERBUDGET_COVERAGE" -> "Penutupan overbudget"
    "RELEASE" -> "Pelepasan budget"
    "ROLLOVER" -> "Rollover"
    "AUTOMATION" -> "Transaksi otomatis"
    "REVERSAL" -> "Reversal"
    "ARCHIVE" -> "Arsip"
    "RESTORE" -> "Pemulihan"
    "CORRECTION" -> "Koreksi jurnal"
    else -> type.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
}

private val fullDateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.forLanguageTag("id-ID"))
private val groupDateFormat = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("id-ID"))
private const val PAGE_SIZE = 50
private const val LOAD_MORE_THRESHOLD = 8
