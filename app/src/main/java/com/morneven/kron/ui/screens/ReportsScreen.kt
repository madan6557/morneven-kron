package com.morneven.kron.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable
fun ReportsScreen(state: KronUiState, onExport: () -> Unit, modifier: Modifier = Modifier) {
    val visible = state.valuesVisible
    val spentByCategory = state.allocations.groupBy { it.categoryName }.mapValues { (_, rows) -> rows.sumOf { it.spentAmount } }.entries.sortedByDescending { it.value }
    val maxSpent = spentByCategory.maxOfOrNull { it.value }?.coerceAtLeast(1) ?: 1
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text("LAPORAN", style = MaterialTheme.typography.headlineMedium)
                    Text("Ringkasan yang direkonstruksi dari jurnal", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = onExport) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null)
                    Text("CSV")
                }
            }
        }
        item {
            HudCard {
                SectionHeader("Cash flow bulan ini")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Metric("Masuk", displayMoney(state.cashflow.income, visible), Modifier.weight(1f), KronGreen)
                    Metric("Keluar", displayMoney(state.cashflow.expense, visible), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(14.dp))
                val net = state.cashflow.income - state.cashflow.expense
                Metric("Net", displayMoney(net, visible), color = signedColor(net))
            }
        }
        item {
            HudCard {
                SectionHeader("Komposisi aset")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChannelBadge(FundingChannel.CASH)
                        Text(displayMoney(state.accountBalances.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.balance }, visible), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChannelBadge(FundingChannel.EBUDGET)
                        Text(displayMoney(state.accountBalances.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.balance }, visible), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (spentByCategory.isNotEmpty()) item { SectionHeader("Aktual per kategori") }
        items(spentByCategory, key = { it.key }) { entry ->
            HudCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(entry.key, style = MaterialTheme.typography.titleMedium)
                    Text(displayMoney(entry.value, visible), style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(progress = { entry.value.toFloat() / maxSpent.toFloat() }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.tertiary)
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
