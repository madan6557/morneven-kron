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
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCard
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.ArrowUpward
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
import com.morneven.kron.data.DebtSummary
import com.morneven.kron.data.DebtRole
import com.morneven.kron.data.DebtStatus
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.BudgetProgress
import com.morneven.kron.ui.components.CashFlowCard
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HeroBalanceCard
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.Metric
import com.morneven.kron.ui.components.QuickActionDock
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.TransactionCardItem
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
import com.morneven.kron.ui.theme.KronPillShape
import com.morneven.kron.ui.theme.KronSubCardShape
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
    onSchedules: () -> Unit = {},
    onBudget: () -> Unit = {},
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val visible = state.valuesVisible
    var exactMoney by remember { mutableStateOf<Pair<String, Long>?>(null) }
    val cashAssets = state.totalCashAssets
    val eBudgetAssets = state.totalEBudgetAssets
    val categoryMap = remember(state.categories) { state.categories.associateBy { it.name } }
    val activePortfolioIds = state.portfolios.map { it.id }.toSet()
    val negative = state.allocations.filter { !it.portfolioArchived && it.availableAmount < 0 }
    val underfunded = state.periods.filter { it.portfolioId in activePortfolioIds && it.status == PeriodStatus.UNDERFUNDED }
    val activeRules = state.rules.filterNot { it.isPaused }
    val today = LocalDate.now()
    val vaultDeficit = state.vaultDeficit
    val stalledRules = state.stalledRules(today)
    // Interest accrual walks one step per elapsed period, so recomputing it on every recomposition
    // is real work. It only changes when the debts change or the day rolls over.
    val debtSummary = remember(state.debts, today.toEpochDay()) { DebtSummary.of(state.debts, today) }
    val activeDebts = debtSummary.active

    LazyColumn(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.size(38.dp),
                        shape = KronSubCardShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                        border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.AccountBalanceWallet,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column {
                        Text("KRON", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(
                            LocalDate.now().format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.forLanguageTag("id-ID"))).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
            HeroBalanceCard(
                totalAssets = state.totalAssets,
                cashAssets = cashAssets,
                eBudgetAssets = eBudgetAssets,
                vaultCash = state.vaultCash,
                vaultEBudget = state.vaultEBudget,
                rollover = state.rolloverCash + state.rolloverEBudget,
                visible = visible,
                accountName = state.activeAccount?.name ?: "",
                onToggleValues = onToggleValues,
                onExactMoney = { label, value -> exactMoney = label to value },
            )
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
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Tindakan cepat")
                    QuickActionDock(
                        onIncome = onIncome,
                        onExpense = onExpense,
                        onTransfer = onTransfer,
                        onDebt = onDebt,
                        readOnly = readOnly,
                    )
                }
            }
        }

        // Only until the ledger has anything in it. KRON routes money through the Main Vault before
        // it can be budgeted, which is not obvious from an all-zero screen, and the quick actions
        // alone do not say which one to press first.
        if (!readOnly && state.activities.isEmpty()) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Langkah awal")
                    HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)) {
                        Text(
                            "Dana masuk ke Main Vault dulu, lalu dibagi menjadi kategori budget.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        StartStep(1, "Catat pemasukan", "Saldo akun dan Main Vault bertambah.", onIncome)
                        StartStep(2, "Buat portfolio RAB", "Booking isi Vault ke kategori bulanan atau tahunan.", onBudget)
                        StartStep(3, "Catat pengeluaran", "Ambil dari kategori budget, atau tandai tak terduga.", onExpense)
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
                            Metric("Perlu dibayar", displayMoney(debtSummary.payable, visible), Modifier.weight(1f), MaterialTheme.colorScheme.error)
                            Metric("Akan diterima", displayMoney(debtSummary.receivable, visible), Modifier.weight(1f), KronGreen)
                        }
                        if (debtSummary.interest > 0L) Text("Bunga berjalan: ${displayMoney(debtSummary.interest, visible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        val nearestDebt = debtSummary.nearest
                        nearestDebt?.dueEpochDay?.let { dueDay ->
                            val due = LocalDate.ofEpochDay(dueDay)
                            Text(
                                "Tenggat terdekat: ${nearestDebt.counterparty} · ${due.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID")))}",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (due.isBefore(today)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (debtSummary.overdue > 0) Text("${debtSummary.overdue} hutang/piutang melewati tenggat", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }

        if (negative.isNotEmpty() || underfunded.isNotEmpty() || state.unresolvedTotal < 0 || vaultDeficit < 0 || stalledRules.isNotEmpty()) {
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
                        if (vaultDeficit < 0) AttentionRow(
                            icon = Icons.Outlined.ErrorOutline,
                            title = "Main Vault terpakai melebihi sisa",
                            value = displayMoney(vaultDeficit, visible),
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
                        if (stalledRules.isNotEmpty()) AttentionRow(
                            icon = Icons.Outlined.CalendarMonth,
                            title = "${stalledRules.size} jadwal otomatis tertunda",
                            value = "Belum tercatat",
                            color = KronGold,
                            onClick = onSchedules,
                        )
                        if (vaultDeficit < 0) Text(
                            "Pengeluaran tak terduga memakai dana yang sudah dibooking ke budget. Kurangi alokasi kategori atau tambah pemasukan agar Main Vault kembali positif.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (stalledRules.isNotEmpty()) Text(
                            "KRON mencoba ulang jadwal yang tertunda setiap hari. Penyebab tersering adalah saldo kanal belum mencukupi saat jadwal jatuh tempo.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item {
            CashFlowCard(
                income = state.cashflow.income,
                expense = state.cashflow.expense,
                visible = visible,
            )
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

        // Debt movements change the account balance just like any other transaction, so leaving
        // them out made a recorded payment look like it had not been saved.
        val financialActivities = state.activities.filter {
            it.type in setOf(
                "INCOME", "EXPENSE", "UNEXPECTED_EXPENSE", "TRANSFER", "OPENING_BALANCE",
                "CHANNEL_TRANSFER", "DEBT_OPEN", "DEBT_PAYMENT", "AUTOMATION",
            )
        }
        if (financialActivities.isNotEmpty()) {
            item { SectionHeader("Aktivitas terbaru", "Lihat semua", onAllActivities) }
            items(financialActivities.take(5), key = { it.id }) { activity ->
                TransactionCardItem(
                    activity = activity,
                    category = categoryMap[activity.title],
                    valuesVisible = visible,
                    channels = state.eventChannels[activity.id].orEmpty(),
                    readOnly = readOnly,
                    onClick = onAllActivities,
                )
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
private fun StartStep(number: Int, title: String, description: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = androidx.compose.foundation.BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                modifier = Modifier.size(28.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.16f),
                border = androidx.compose.foundation.BorderStroke(0.8.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.4f)),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        number.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun AttentionRow(icon: ImageVector, title: String, value: String, color: Color, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = KronSubCardShape,
        color = color.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(0.8.dp, color.copy(alpha = 0.25f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.weight(1f),
            ) {
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = color.copy(alpha = 0.18f),
                    modifier = Modifier.size(32.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
                    }
                }
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(value, style = MaterialTheme.typography.labelLarge, color = color, fontWeight = FontWeight.Bold)
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowForward,
                    contentDescription = null,
                    tint = color.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}
