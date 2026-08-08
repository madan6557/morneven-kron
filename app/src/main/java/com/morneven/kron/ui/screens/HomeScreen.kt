package com.morneven.kron.ui.screens

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCard
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.RemoveRedEye
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.DebtCalculator
import com.morneven.kron.data.DebtRole
import com.morneven.kron.data.DebtStatus
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.BudgetProgress
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.Metric
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.eventTypeLabel
import com.morneven.kron.ui.components.displayPrimaryHomeMoney
import com.morneven.kron.ui.components.displaySecondaryHomeMoney
import com.morneven.kron.ui.components.shouldCompactPrimaryHomeMoney
import com.morneven.kron.ui.components.shouldCompactSecondaryHomeMoney
import com.morneven.kron.ui.components.signedColor
import com.morneven.kron.ui.theme.KronBlue
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HomeScreen(
    state: KronUiState,
    onToggleValues: () -> Unit,
    onIncome: () -> Unit,
    onExpense: () -> Unit,
    onTransfer: () -> Unit,
    onResolve: () -> Unit,
    onDebt: () -> Unit = {},
    onAllActivities: () -> Unit,
    onPauseRule: (String) -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visible = state.valuesVisible
    var exactMoney by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val cashAssets = state.totalCashAssets
    val eBudgetAssets = state.totalEBudgetAssets
    val activePortfolioIds = state.portfolios.map { it.id }.toSet()
    val negative = state.allocations.filter { !it.portfolioArchived && it.availableAmount < 0 }
    val underfunded = state.periods.filter { it.portfolioId in activePortfolioIds && it.status == PeriodStatus.UNDERFUNDED }
    val activeRules = state.rules.filterNot { it.isPaused }
    val activeDebts = state.debts.filter { it.status == DebtStatus.OPEN }
    val today = LocalDate.now()
    val debtInterest = activeDebts.sumOf { DebtCalculator.currentInterest(it, today) }
    val debtPayable = activeDebts.filter { it.role == DebtRole.DEBTOR }
        .sumOf { it.principalOutstanding + DebtCalculator.currentInterest(it, today) }
    val debtReceivable = activeDebts.filter { it.role == DebtRole.CREDITOR }
        .sumOf { it.principalOutstanding + DebtCalculator.currentInterest(it, today) }
    val nearestDebt = activeDebts.filter { it.dueEpochDay != null }.minByOrNull { it.dueEpochDay!! }
    val overdueDebtCount = activeDebts.count { DebtCalculator.isOverdue(it, today) }

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text("KRON", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Black)
                    Text(state.activeAccount?.name ?: "Belum ada akun aktif", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    Text(
                        LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("id-ID"))).uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                FilledTonalIconButton(onClick = onToggleValues) {
                    Icon(
                        imageVector = if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.RemoveRedEye,
                        contentDescription = if (visible) "Sembunyikan nilai" else "Tampilkan nilai",
                    )
                }
            }
        }

        item {
            HudCard(accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)) {
                Text("SALDO AKUN AKTIF", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.height(8.dp))
                Text(
                    displayPrimaryHomeMoney(state.totalAssets, visible),
                    modifier = Modifier.clickable(enabled = visible && shouldCompactPrimaryHomeMoney(state.totalAssets)) { exactMoney = "Total aset nyata" to state.totalAssets },
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                )
                Text("Total Cash dan eBudget pada akun aktif", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(18.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Metric("Cash", displaySecondaryHomeMoney(cashAssets, visible), Modifier.weight(1f).clickable(enabled = visible && shouldCompactSecondaryHomeMoney(cashAssets)) { exactMoney = "Total Cash" to cashAssets }, KronGold)
                    Metric("eBudget", displaySecondaryHomeMoney(eBudgetAssets, visible), Modifier.weight(1f).clickable(enabled = visible && shouldCompactSecondaryHomeMoney(eBudgetAssets)) { exactMoney = "Total eBudget" to eBudgetAssets }, KronBlue)
                }
                Spacer(Modifier.height(16.dp))
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), shape = MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Main Vault", style = MaterialTheme.typography.labelLarge)
                            Text(displaySecondaryHomeMoney(state.totalVault, visible), modifier = Modifier.clickable(enabled = visible && shouldCompactSecondaryHomeMoney(state.totalVault)) { exactMoney = "Main Vault" to state.totalVault }, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChannelBadge(FundingChannel.CASH) }
                            Text(displaySecondaryHomeMoney(state.vaultCash, visible), modifier = Modifier.clickable(enabled = visible && shouldCompactSecondaryHomeMoney(state.vaultCash)) { exactMoney = "Main Vault Cash" to state.vaultCash }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChannelBadge(FundingChannel.EBUDGET) }
                            Text(displaySecondaryHomeMoney(state.vaultEBudget, visible), modifier = Modifier.clickable(enabled = visible && shouldCompactSecondaryHomeMoney(state.vaultEBudget)) { exactMoney = "Main Vault eBudget" to state.vaultEBudget }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Reserve rollover", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                            val rollover = state.rolloverCash + state.rolloverEBudget
                            Text(displaySecondaryHomeMoney(rollover, visible), modifier = Modifier.clickable(enabled = visible && shouldCompactSecondaryHomeMoney(rollover)) { exactMoney = "Reserve rollover" to rollover }, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                        }
                    }
                }
            }
        }

        if (readOnly) {
            item {
                HudCard {
                    Text("Akses hanya lihat", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Owner memberikan role Viewer. Transaksi, automation, restore, dan export dinonaktifkan.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader("Tindakan cepat")
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        QuickAction(Icons.Outlined.AddCard, "Pemasukan", KronGreen, onIncome, Modifier.weight(1f))
                        QuickAction(Icons.Outlined.Payments, "Pengeluaran", MaterialTheme.colorScheme.primary, onExpense, Modifier.weight(1f))
                        QuickAction(Icons.Outlined.SwapHoriz, "Transfer", KronBlue, onTransfer, Modifier.weight(1f))
                        QuickAction(Icons.Outlined.RequestQuote, "Hutang", MaterialTheme.colorScheme.tertiary, onDebt, Modifier.weight(1f))
                        QuickAction(Icons.Outlined.ArrowOutward, "Resolusi", KronGold, onResolve, Modifier.weight(1f))
                    }
                }
            }
        }

        if (activeDebts.isNotEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Hutang & Piutang", "Kelola", onDebt)
                    HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.65f)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Metric("Perlu dibayar", displayMoney(debtPayable, visible), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                            Metric("Akan diterima", displayMoney(debtReceivable, visible), Modifier.weight(1f), KronGreen)
                        }
                        if (debtInterest > 0L) Text("Bunga berjalan: ${displayMoney(debtInterest, visible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        nearestDebt?.dueEpochDay?.let { dueDay ->
                            val due = LocalDate.ofEpochDay(dueDay)
                            Text(
                                "Tenggat terdekat: ${nearestDebt.counterparty} · ${due.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID")))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (due.isBefore(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (overdueDebtCount > 0) Text("$overdueDebtCount hutang/piutang melewati tenggat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (negative.isNotEmpty() || underfunded.isNotEmpty() || state.unresolvedTotal < 0) {
            item {
                Column {
                    SectionHeader("Pusat perhatian")
                    HudCard(accent = MaterialTheme.colorScheme.error.copy(alpha = 0.65f)) {
                        if (negative.isNotEmpty()) AttentionRow(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "${negative.size} kategori budget minus",
                            value = displayMoney(negative.sumOf { it.availableAmount }, visible),
                            color = MaterialTheme.colorScheme.error,
                            onClick = onResolve,
                        )
                        if (state.unresolvedTotal < 0) AttentionRow(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "Pengeluaran belum teralokasi",
                            value = displayMoney(state.unresolvedTotal, visible),
                            color = MaterialTheme.colorScheme.error,
                            onClick = onResolve,
                        )
                        if (underfunded.isNotEmpty()) AttentionRow(
                            icon = Icons.Outlined.AccountBalanceWallet,
                            title = "${underfunded.size} portfolio belum terdanai",
                            value = "Perlu booking",
                            color = KronGold,
                            onClick = onResolve,
                        )
                    }
                }
            }
        }

        item {
            Column {
                SectionHeader("Cash flow bulan ini")
                HudCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Metric("Masuk", displayMoney(state.cashflow.income, visible), Modifier.fillMaxWidth(), KronGreen)
                        Metric("Keluar", displayMoney(state.cashflow.expense, visible), Modifier.fillMaxWidth(), MaterialTheme.colorScheme.error)
                        val net = state.cashflow.income - state.cashflow.expense
                        Metric("Net", displayMoney(net, visible), Modifier.fillMaxWidth(), signedColor(net))
                    }
                }
            }
        }

        val activeGroups = state.allocations.filter { !it.portfolioArchived && (it.periodStatus == PeriodStatus.ACTIVE || it.periodStatus == PeriodStatus.RESOLUTION_REQUIRED) }
            .groupBy { it.portfolioId }
            .entries.take(3)
        if (activeGroups.isNotEmpty()) {
            item { SectionHeader("Portfolio aktif") }
            items(activeGroups, key = { it.key }) { (_, allocations) ->
                val booked = allocations.sumOf { it.bookedAmount }
                val available = allocations.sumOf { it.availableAmount }
                HudCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(allocations.first().portfolioName, style = MaterialTheme.typography.titleMedium)
                            Text("${allocations.size} alokasi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(displayMoney(available, visible), color = signedColor(available), style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.height(12.dp))
                    BudgetProgress(booked, available)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (allocations.any { it.fundingChannel == FundingChannel.CASH }) ChannelBadge(FundingChannel.CASH)
                        if (allocations.any { it.fundingChannel == FundingChannel.EBUDGET }) ChannelBadge(FundingChannel.EBUDGET)
                    }
                }
            }
        }

        if (activeRules.isNotEmpty()) {
            item {
                SectionHeader("Transaksi otomatis berikutnya")
                Text(
                    "Pemasukan atau pengeluaran yang akan dicatat KRON otomatis. Revert transaksi tidak membatalkan jadwal ini.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(activeRules.take(3), key = { it.id }) { rule ->
                HudCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                            Column {
                                Text(rule.title, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (rule.direction == "INCOME") "Pemasukan otomatis" else "Pengeluaran otomatis",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (rule.direction == "INCOME") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                )
                                Text(LocalDate.ofEpochDay(rule.nextEpochDay).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Berulang setiap ${rule.intervalCount} ${if (rule.cadence == "YEARLY") "tahun" else "bulan"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                        }
                        Text(displayMoney(rule.amount, visible), style = MaterialTheme.typography.labelLarge)
                    }
                    if (!readOnly) androidx.compose.material3.TextButton(onClick = { onPauseRule(rule.id) }) { Text("Hentikan jadwal") }
                }
            }
        }

        val financialActivities = state.activities.filter {
            it.type in setOf("INCOME", "EXPENSE", "UNEXPECTED_EXPENSE", "TRANSFER", "OPENING_BALANCE", "CHANNEL_TRANSFER")
        }
        if (financialActivities.isNotEmpty()) {
            item { SectionHeader("Aktivitas terbaru", "Lihat semua", onAllActivities) }
            items(financialActivities.take(5), key = { it.id }) { activity ->
                val reversed = activity.reversedByEventId != null
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(activity.title, style = MaterialTheme.typography.bodyLarge.copy(textDecoration = if (reversed) TextDecoration.LineThrough else null), maxLines = 1)
                        Text(eventTypeLabel(activity.type), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!reversed) {
                        Text(displayMoney(activity.cashImpact.takeIf { it != 0L } ?: activity.budgetImpact, visible), color = signedColor(activity.cashImpact), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        item { Spacer(Modifier.height(72.dp)) }
    }

    exactMoney?.let { (label, value) ->
        AlertDialog(
            onDismissRequest = { exactMoney = null },
            title = { Text("Nominal lengkap") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(displayMoney(value, true), style = MaterialTheme.typography.titleLarge)
                }
            },
            confirmButton = { TextButton(onClick = { exactMoney = null }) { Text("Tutup") } },
        )
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(
            modifier = Modifier.size(52.dp).clickable(onClick = onClick),
            shape = MaterialTheme.shapes.medium,
            color = color.copy(alpha = 0.14f),
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.4f)),
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = label, tint = color) }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
    }
}

@Composable
private fun AttentionRow(icon: ImageVector, title: String, value: String, color: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
            Icon(icon, contentDescription = null, tint = color)
            Text(title, style = MaterialTheme.typography.bodyMedium)
        }
        Text(value, style = MaterialTheme.typography.labelLarge, color = color)
    }
}
