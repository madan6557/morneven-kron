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
import androidx.compose.material3.FilterChip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.EmptyState
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.signedColor
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun ActivityScreen(state: KronUiState, onEvent: (String) -> Unit, modifier: Modifier = Modifier) {
    var filter by remember { mutableStateOf("Semua") }
    val filters = listOf("Semua", "Uang", "Budget", "Otomatis", "Resolusi", "Sistem")
    val shown = state.activities.filter { event ->
        when (filter) {
            "Uang" -> event.type in setOf("INCOME", "EXPENSE", "TRANSFER", "OPENING_BALANCE", "CHANNEL_TRANSFER")
            "Budget" -> event.type in setOf("PORTFOLIO_BOOKING", "REALLOCATION", "OVERBUDGET_COVERAGE", "RELEASE", "ROLLOVER")
            "Otomatis" -> event.type == "AUTOMATION"
            "Resolusi" -> event.type in setOf("REALLOCATION", "OVERBUDGET_COVERAGE")
            "Sistem" -> event.source == "SYSTEM" || event.type == "REVERSAL"
            else -> true
        }
    }
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("TRANSAKSI & AUDIT", style = MaterialTheme.typography.headlineMedium)
            Text("Semua kejadian tetap dapat ditelusuri", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(filters) { item -> FilterChip(selected = filter == item, onClick = { filter = item }, label = { Text(item) }) }
            }
        }
        if (shown.isEmpty()) item { EmptyState(Icons.AutoMirrored.Outlined.ReceiptLong, "Belum ada aktivitas", "Transaksi dan perubahan budget akan tampil di sini.") }
        items(shown, key = { it.id }) { event ->
            HudCard(modifier = Modifier.fillMaxWidth().clickable { onEvent(event.id) }) {
                Column(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                event.title,
                                style = MaterialTheme.typography.titleMedium.copy(textDecoration = if (event.reversedByEventId != null) TextDecoration.LineThrough else null),
                            )
                            Text(event.type.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        val impact = if (event.cashImpact != 0L) event.cashImpact else if (event.vaultImpact != 0L) event.vaultImpact else event.budgetImpact
                        Text(displayMoney(impact, state.valuesVisible), color = signedColor(impact), style = MaterialTheme.typography.labelLarge)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(LocalDate.ofEpochDay(event.effectiveEpochDay).format(DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("id-ID"))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.note.isNotBlank()) Text(event.note, style = MaterialTheme.typography.bodySmall)
                    Text(if (event.reversedByEventId == null) "Ketuk untuk audit dan revert" else "REVERSED", modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
