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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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

@Composable
fun BudgetScreen(
    state: KronUiState,
    onCreate: () -> Unit,
    onResolve: () -> Unit,
    onFund: (Long) -> Unit,
    onChannelTransfer: () -> Unit,
    onReleaseRollover: (String) -> Unit,
    onDetail: (Long) -> Unit,
    onPause: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val grouped = state.allocations.groupBy { it.periodId }
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
                }
                Button(onClick = onCreate) {
                    Icon(Icons.Outlined.Add, contentDescription = null)
                    Text("Buat")
                }
            }
        }
        item {
            HudCard {
                SectionHeader("Dana terbooking")
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        ChannelBadge(FundingChannel.CASH)
                        Text(displayMoney(state.bookedCash, state.valuesVisible), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        ChannelBadge(FundingChannel.EBUDGET)
                        Text(displayMoney(state.bookedEBudget, state.valuesVisible), style = MaterialTheme.typography.titleMedium)
                    }
                }
                if (state.rolloverCash > 0 || state.rolloverEBudget > 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (state.rolloverCash > 0) Button(onClick = { onReleaseRollover(FundingChannel.CASH) }) { Text("Cash ke Vault") }
                        if (state.rolloverEBudget > 0) Button(onClick = { onReleaseRollover(FundingChannel.EBUDGET) }) { Text("eBudget ke Vault") }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("Reserve rollover", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        ChannelBadge(FundingChannel.CASH)
                        Text(displayMoney(state.rolloverCash, state.valuesVisible), style = MaterialTheme.typography.titleMedium)
                    }
                    Column(Modifier.weight(1f)) {
                        ChannelBadge(FundingChannel.EBUDGET)
                        Text(displayMoney(state.rolloverEBudget, state.valuesVisible), style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(12.dp))
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
        if (grouped.isEmpty()) {
            item { EmptyState(Icons.Outlined.AccountBalanceWallet, "Belum ada portfolio", "Buat RAB pertama dan tentukan komposisi Cash serta eBudget tiap kategori.") }
        }
        items(grouped.entries.toList(), key = { it.key }) { (_, rows) ->
            val first = rows.first()
            val available = rows.sumOf { it.availableAmount }
            val booked = rows.sumOf { it.bookedAmount }
            HudCard(accent = if (rows.any { it.availableAmount < 0 }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(first.portfolioName, style = MaterialTheme.typography.titleLarge)
                        val portfolio = state.portfolios.firstOrNull { it.id == first.portfolioId }
                        val cadence = portfolio?.let { "Setiap ${it.intervalCount} ${if (it.cadence == "YEARLY") "tahun" else "bulan"}" }
                        Text(listOfNotNull(first.periodStatus.replace('_', ' '), cadence).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(displayMoney(available, state.valuesVisible), color = signedColor(available), style = MaterialTheme.typography.titleMedium)
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
                            Text(displayMoney(row.availableAmount, state.valuesVisible), color = signedColor(row.availableAmount), style = MaterialTheme.typography.labelLarge)
                            Text("booking ${displayMoney(row.bookedAmount, state.valuesVisible)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Button(onClick = { onDetail(first.periodId) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Text("Lihat detail budget")
                }
                val portfolio = state.portfolios.firstOrNull { it.id == first.portfolioId }
                if (portfolio?.isPaused == false) {
                    Button(onClick = { onPause(first.portfolioId) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Text("Hentikan portfolio")
                    }
                }
                if (rows.any { it.availableAmount < 0 }) {
                    Button(onClick = onResolve, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Icon(Icons.Outlined.ErrorOutline, contentDescription = null)
                        Text("Selesaikan budget minus")
                    }
                } else if (first.periodStatus == PeriodStatus.UNDERFUNDED || first.periodStatus == PeriodStatus.DRAFT) {
                    Button(onClick = { onFund(first.periodId) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Text("Booking dari Main Vault")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}
