package com.morneven.kron.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.AllocationDraft
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.ExpenseSplitInput
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.MoneyField
import com.morneven.kron.ui.components.parseMoneyInput
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun IncomeDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, Long, Long?, String, String, String?, LocalDate, LocalDate?, Int, Boolean) -> Unit) {
    val accounts = state.accounts
    val categories = state.categories.filter { it.direction == TransactionDirection.INCOME }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var categoryId by remember { mutableStateOf(categories.firstOrNull()?.id) }
    var amount by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf<String?>(null) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var intervalCount by remember { mutableIntStateOf(1) }
    var recordNow by remember { mutableStateOf(true) }
    FormDialog("Catat pemasukan", onDismiss, confirmEnabled = accountId != null && money(amount) > 0 && intervalCount > 0 && (endDate == null || !endDate!!.isBefore(startDate)), onConfirm = {
        onSubmit(requireNotNull(accountId), money(amount), categoryId, title, note, recurring, startDate, endDate, intervalCount, recordNow)
    }) {
        ChoiceField("Akun", accountId, accounts, { it.id }, { "${it.name} · ${channelLabel(it.fundingChannel)}" }) { accountId = it }
        MoneyField(amount, { amount = it }, "Nominal")
        ChoiceField("Kategori", categoryId, categories, { it.id }, { it.name }) { categoryId = it }
        OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        RecurrencePicker(recurring) { recurring = it }
        if (recurring != null) ScheduleFields(startDate, { startDate = it }, endDate, { endDate = it }, intervalCount, { intervalCount = it }, recurring, recordNow, { recordNow = it })
    }
}

private data class SplitDraft(var categoryId: Long?, var allocationId: Long?, var amount: String)
private data class BudgetCategoryDraft(var name: String, var amount: String, var cashPercentage: Int = 50)

@Composable
fun ExpenseDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, Long, List<ExpenseSplitInput>, String, String, String?, LocalDate, LocalDate?, Int, Boolean) -> Unit) {
    val accounts = state.accounts
    val categories = state.categories.filter { it.direction == TransactionDirection.EXPENSE }
    var accountId by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf<String?>(null) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var intervalCount by remember { mutableIntStateOf(1) }
    var recordNow by remember { mutableStateOf(true) }
    val splits = remember { mutableStateListOf(SplitDraft(categories.firstOrNull()?.id, null, "")) }
    val accountChannel = accounts.firstOrNull { it.id == accountId }?.fundingChannel
    val splitTotal = splits.sumOf { money(it.amount) }
    FormDialog("Catat pengeluaran", onDismiss, confirmEnabled = accountId != null && splitTotal > 0 && splits.all { money(it.amount) > 0 } && intervalCount > 0 && (endDate == null || !endDate!!.isBefore(startDate)), onConfirm = {
        onSubmit(requireNotNull(accountId), splitTotal, splits.map { ExpenseSplitInput(it.categoryId, it.allocationId, money(it.amount)) }, title, note, recurring, startDate, endDate, intervalCount, recordNow)
    }) {
        ChoiceField("Akun pembayaran", accountId, accounts, { it.id }, { "${it.name} · ${channelLabel(it.fundingChannel)}" }) {
            accountId = it
            splits.indices.forEach { index -> splits[index] = splits[index].copy(allocationId = null) }
        }
        Text("SPLIT TRANSAKSI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        splits.forEachIndexed { index, split ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bagian ${index + 1}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (splits.size > 1) IconButton(onClick = { splits.removeAt(index) }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus split") }
                }
                ChoiceField("Kategori", split.categoryId, categories, { it.id }, { it.name }) { value -> splits[index] = split.copy(categoryId = value, allocationId = null) }
                val allocations = state.allocations.filter { it.categoryId == split.categoryId && it.fundingChannel == accountChannel && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") }
                ChoiceFieldNullable("Budget", split.allocationId, allocations, { it.id }, { "${it.portfolioName} · ${displayMoney(it.availableAmount, state.valuesVisible)}" }, "Belum teralokasi") { value -> splits[index] = split.copy(allocationId = value) }
                MoneyField(split.amount, { value -> splits[index] = split.copy(amount = value) }, "Nominal bagian")
            }
        }
        TextButton(onClick = { splits.add(SplitDraft(categories.firstOrNull()?.id, null, "")) }) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text("Tambah split")
        }
        Text("Total ${displayMoney(splitTotal, state.valuesVisible)}", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        if (splits.size == 1) {
            RecurrencePicker(recurring) { recurring = it }
            if (recurring != null) ScheduleFields(startDate, { startDate = it }, endDate, { endDate = it }, intervalCount, { intervalCount = it }, recurring, recordNow, { recordNow = it })
        }
    }
}

@Composable
fun TransferDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, Long, Long, String) -> Unit) {
    val accounts = state.accounts
    var from by remember { mutableStateOf(accounts.firstOrNull()?.id) }
    var to by remember { mutableStateOf(accounts.drop(1).firstOrNull()?.id) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val fromRow = state.accountBalances.firstOrNull { it.id == from }
    val fromChannel = accounts.firstOrNull { it.id == from }?.fundingChannel
    val toChannel = accounts.firstOrNull { it.id == to }?.fundingChannel
    FormDialog("Transfer dan konversi Vault", onDismiss, confirmEnabled = from != null && to != null && from != to && money(amount) in 1..(fromRow?.balance ?: 0), onConfirm = {
        onSubmit(requireNotNull(from), requireNotNull(to), money(amount), note)
    }) {
        ChoiceField("Dari akun", from, accounts, { it.id }, { "${it.name} · ${channelLabel(it.fundingChannel)}" }) { from = it }
        ChoiceField("Ke akun", to, accounts, { it.id }, { "${it.name} · ${channelLabel(it.fundingChannel)}" }) { to = it }
        if (fromChannel != null && toChannel != null && fromChannel != toChannel) {
            Text("Transfer lintas kanal akan memindahkan komposisi Main Vault. Dana kategori terbooking tidak berubah.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        MoneyField(amount, { amount = it }, "Nominal")
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
fun PortfolioDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (String, String, Long, Boolean, List<AllocationDraft>, LocalDate, LocalDate?, Int) -> Unit) {
    var name by remember { mutableStateOf("") }
    var cadence by remember { mutableStateOf("MONTHLY") }
    var plannedIncome by remember { mutableStateOf("") }
    var rollover by remember { mutableStateOf(false) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var intervalCount by remember { mutableIntStateOf(1) }
    val budgetCategories = remember { mutableStateListOf(BudgetCategoryDraft("", "")) }
    val drafts = budgetCategories.flatMap { category ->
        val total = money(category.amount)
        val percent = category.cashPercentage
        val cash = total * percent / 100
        val eBudget = total - cash
        buildList {
            if (cash > 0) add(AllocationDraft(0, FundingChannel.CASH, cash, category.name))
            if (eBudget > 0) add(AllocationDraft(0, FundingChannel.EBUDGET, eBudget, category.name))
        }
    }
    val totalBudget = budgetCategories.sumOf { money(it.amount) }
    FormDialog("Buat portfolio RAB", onDismiss, confirmEnabled = name.isNotBlank() && budgetCategories.all { it.name.isNotBlank() && money(it.amount) > 0 } && intervalCount > 0 && (endDate == null || !endDate!!.isBefore(startDate)), onConfirm = {
        onSubmit(name, cadence, money(plannedIncome), rollover, drafts, startDate, endDate, intervalCount)
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama portfolio") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cadence == "MONTHLY", onClick = { cadence = "MONTHLY" }, label = { Text("Bulanan") })
            FilterChip(selected = cadence == "YEARLY", onClick = { cadence = "YEARLY" }, label = { Text("Tahunan") })
        }
        ScheduleFields(startDate, { startDate = it }, endDate, { endDate = it }, intervalCount, { intervalCount = it }, cadence, null, null)
        MoneyField(plannedIncome, { plannedIncome = it }, "Target pemasukan periode (opsional)")
        Text("Target pemasukan adalah perkiraan pemasukan untuk periode ini, bukan total budget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Total budget kategori: ${displayMoney(totalBudget, state.valuesVisible)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Rollover sisa", style = MaterialTheme.typography.titleMedium)
                Text("Bawa sisa positif ke periode berikutnya", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(rollover, { rollover = it })
        }
        Text("KATEGORI BUDGET CUSTOM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        Text("Buat kategori sendiri. Total budget dihitung otomatis dari seluruh kategori.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        budgetCategories.forEachIndexed { index, category ->
            val percentage = category.cashPercentage
            Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(category.name, { budgetCategories[index] = category.copy(name = it) }, label = { Text("Nama kategori") }, modifier = Modifier.weight(1f), singleLine = true)
                    if (budgetCategories.size > 1) IconButton(onClick = { budgetCategories.removeAt(index) }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus kategori") }
                }
                MoneyField(category.amount, { budgetCategories[index] = category.copy(amount = it) }, "Budget kategori")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cash $percentage%", style = MaterialTheme.typography.labelSmall)
                    Text("eBudget ${100 - percentage}%", style = MaterialTheme.typography.labelSmall)
                }
                Slider(value = percentage.toFloat(), onValueChange = { budgetCategories[index] = category.copy(cashPercentage = it.toInt()) }, valueRange = 0f..100f, steps = 19)
            }
        }
        OutlinedButton(onClick = { budgetCategories.add(BudgetCategoryDraft("", "")) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text("Tambah kategori")
        }
    }
}

@Composable
fun BudgetDetailDialog(state: KronUiState, periodId: Long, onDismiss: () -> Unit, onCorrect: (Long, Long, String) -> Unit) {
    val rows = state.allocations.filter { it.periodId == periodId }
    var correctionId by remember { mutableStateOf<Long?>(null) }
    var correctedAmount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("Koreksi nominal budget") }
    val selected = rows.firstOrNull { it.id == correctionId }
    val validCorrection = selected != null && money(correctedAmount) >= 0 && money(correctedAmount) != selected.plannedAmount && reason.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Detail budget") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                rows.firstOrNull()?.let { row ->
                    Text(row.portfolioName, style = MaterialTheme.typography.titleLarge)
                    Text("${LocalDate.ofEpochDay(row.startEpochDay)} sampai ${LocalDate.ofEpochDay(row.endEpochDay)} · ${row.periodStatus.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                rows.forEach { row ->
                    HudCard {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { ChannelBadge(row.fundingChannel); Text(row.categoryName) }
                                Text("Rencana ${displayMoney(row.plannedAmount, state.valuesVisible)}", style = MaterialTheme.typography.bodySmall)
                                Text("Booking ${displayMoney(row.bookedAmount, state.valuesVisible)} · Terpakai ${displayMoney(row.spentAmount, state.valuesVisible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("Sisa ${displayMoney(row.availableAmount, state.valuesVisible)}", style = MaterialTheme.typography.titleMedium, color = if (row.availableAmount < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                            }
                            TextButton(onClick = { correctionId = row.id; correctedAmount = row.plannedAmount.toString() }) { Text("Koreksi") }
                        }
                        if (row.id == correctionId) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(10.dp))
                            Text("Koreksi ${row.categoryName}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                            MoneyField(correctedAmount, { correctedAmount = it }, "Nominal rencana baru")
                            OutlinedTextField(reason, { reason = it }, label = { Text("Alasan koreksi") }, modifier = Modifier.fillMaxWidth())
                            Text("Jurnal lama tetap tersimpan dan ditandai dikoreksi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onCorrect(requireNotNull(correctionId), money(correctedAmount), reason); onDismiss() }, enabled = validCorrection) { Text("Simpan koreksi") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}

@Composable
fun ResolveDialog(state: KronUiState, onDismiss: () -> Unit, onAllocation: (Long, Long, Long, String) -> Unit, onVault: (Long, Long, String) -> Unit, onRollover: (Long, Long, String) -> Unit, onUnallocated: (Long, Long, String) -> Unit) {
    val targets = state.allocations.filter { it.availableAmount < 0 }
    val hasUnallocated = state.unallocatedCash < 0 || state.unallocatedEBudget < 0
    var mode by remember { mutableStateOf(if (targets.isNotEmpty()) "MINUS" else "UNALLOCATED") }
    var targetId by remember { mutableStateOf(targets.firstOrNull()?.id) }
    var sourceId by remember { mutableStateOf<Long?>(null) }
    var sourceMode by remember { mutableStateOf("VAULT") }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("Resolusi budget minus") }
    val target = targets.firstOrNull { it.id == targetId }
    val sources = target?.let { selected -> state.allocations.filter { it.fundingChannel == selected.fundingChannel && it.availableAmount > 0 && it.id != targetId && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") } }.orEmpty()
    val vault = if (target?.fundingChannel == FundingChannel.CASH) state.vaultCash else state.vaultEBudget
    val rollover = if (target?.fundingChannel == FundingChannel.CASH) state.rolloverCash else state.rolloverEBudget
    val sourceAvailable = when (sourceMode) {
        "ROLLOVER" -> rollover
        "ALLOCATION" -> sources.firstOrNull { it.id == sourceId }?.availableAmount ?: 0
        else -> vault
    }
    val requested = money(amount)
    val limit = minOf(sourceAvailable, -(target?.availableAmount ?: 0))
    var unallocatedChannel by remember { mutableStateOf(if (state.unallocatedCash < 0) FundingChannel.CASH else FundingChannel.EBUDGET) }
    val destinations = state.allocations.filter { it.fundingChannel == unallocatedChannel && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") }
    var destinationId by remember(unallocatedChannel) { mutableStateOf(destinations.firstOrNull()?.id) }
    val unallocatedAmount = if (unallocatedChannel == FundingChannel.CASH) state.unallocatedCash else state.unallocatedEBudget
    val confirmEnabled = if (mode == "MINUS") target != null && requested in 1..limit else destinationId != null && requested in 1..(-unallocatedAmount).coerceAtLeast(0)
    FormDialog("Resolving Center", onDismiss, confirmEnabled = confirmEnabled, onConfirm = {
        if (mode == "UNALLOCATED") onUnallocated(requireNotNull(destinationId), requested, note)
        else when (sourceMode) {
            "ROLLOVER" -> onRollover(requireNotNull(targetId), requested, note)
            "ALLOCATION" -> onAllocation(requireNotNull(sourceId), requireNotNull(targetId), requested, note)
            else -> onVault(requireNotNull(targetId), requested, note)
        }
    }) {
        if (targets.isNotEmpty() && hasUnallocated) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == "MINUS", onClick = { mode = "MINUS" }, label = { Text("Budget minus") })
                FilterChip(selected = mode == "UNALLOCATED", onClick = { mode = "UNALLOCATED" }, label = { Text("Belum dialokasikan") })
            }
        }
        if (targets.isEmpty() && !hasUnallocated) {
            Text("Tidak ada kategori minus. Semua budget sehat.")
        } else if (mode == "MINUS") {
            ChoiceField("Kategori minus", targetId, targets, { it.id }, { "${it.categoryName} · ${channelLabel(it.fundingChannel)} · ${displayMoney(it.availableAmount, state.valuesVisible)}" }) {
                targetId = it
                sourceId = null
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = sourceMode == "VAULT", onClick = { sourceMode = "VAULT"; sourceId = null }, label = { Text("Main Vault") })
                if (rollover > 0) FilterChip(selected = sourceMode == "ROLLOVER", onClick = { sourceMode = "ROLLOVER"; sourceId = null }, label = { Text("Reserve rollover") })
                if (sources.isNotEmpty()) FilterChip(selected = sourceMode == "ALLOCATION", onClick = { sourceMode = "ALLOCATION" }, label = { Text("Kategori") })
            }
            if (sourceMode == "ALLOCATION") ChoiceField("Sumber kategori", sourceId, sources, { it.id }, { "${it.portfolioName} / ${it.categoryName} · ${displayMoney(it.availableAmount, state.valuesVisible)}" }) { sourceId = it }
            MoneyField(amount, { amount = it }, "Nominal resolusi")
            OutlinedTextField(note, { note = it }, label = { Text("Alasan") }, modifier = Modifier.fillMaxWidth())
            Text("Preview setelah konfirmasi", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            Text("Target: ${displayMoney((target?.availableAmount ?: 0) + requested, state.valuesVisible)}")
            Text("Sumber: ${displayMoney(sourceAvailable - requested, state.valuesVisible)}")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.unallocatedCash < 0) FilterChip(selected = unallocatedChannel == FundingChannel.CASH, onClick = { unallocatedChannel = FundingChannel.CASH }, label = { Text("Cash") })
                if (state.unallocatedEBudget < 0) FilterChip(selected = unallocatedChannel == FundingChannel.EBUDGET, onClick = { unallocatedChannel = FundingChannel.EBUDGET }, label = { Text("eBudget") })
            }
            Text("Belum teralokasi: ${displayMoney(unallocatedAmount, state.valuesVisible)}", color = MaterialTheme.colorScheme.error)
            ChoiceField("Tujuan budget", destinationId, destinations, { it.id }, { "${it.portfolioName} / ${it.categoryName} · ${displayMoney(it.availableAmount, state.valuesVisible)}" }) { destinationId = it }
            MoneyField(amount, { amount = it }, "Nominal alokasi")
            OutlinedTextField(note, { note = it }, label = { Text("Alasan") }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ChannelTransferDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, Long, Long, Long, String) -> Unit) {
    val sources = state.allocations.filter { it.availableAmount > 0 }
    var allocationId by remember { mutableStateOf(sources.firstOrNull()?.id) }
    val source = sources.firstOrNull { it.id == allocationId }
    val fromAccounts = state.accounts.filter { it.fundingChannel == source?.fundingChannel }
    val toAccounts = state.accounts.filter { it.fundingChannel != source?.fundingChannel }
    var fromId by remember(source?.fundingChannel) { mutableStateOf(fromAccounts.firstOrNull()?.id) }
    var toId by remember(source?.fundingChannel) { mutableStateOf(toAccounts.firstOrNull()?.id) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val accountBalance = state.accountBalances.firstOrNull { it.id == fromId }?.balance ?: 0
    val limit = minOf(source?.availableAmount ?: 0, accountBalance)
    FormDialog("Pindahkan Cash dan eBudget", onDismiss, confirmEnabled = source != null && fromId != null && toId != null && money(amount) in 1..limit, onConfirm = {
        onSubmit(requireNotNull(allocationId), requireNotNull(fromId), requireNotNull(toId), money(amount), note)
    }) {
        ChoiceField("Kategori sumber", allocationId, sources, { it.id }, { "${it.categoryName} · ${channelLabel(it.fundingChannel)} · ${displayMoney(it.availableAmount, state.valuesVisible)}" }) { allocationId = it }
        ChoiceField("Akun asal", fromId, fromAccounts, { it.id }, { it.name }) { fromId = it }
        ChoiceField("Akun tujuan", toId, toAccounts, { it.id }, { it.name }) { toId = it }
        MoneyField(amount, { amount = it }, "Nominal")
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        if (source != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChannelBadge(source.fundingChannel)
                Text("menjadi")
                ChannelBadge(if (source.fundingChannel == FundingChannel.CASH) FundingChannel.EBUDGET else FundingChannel.CASH)
            }
            Text("Akun nyata dan booking kategori dipindahkan dalam satu event atomik.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun AccountDialog(onDismiss: () -> Unit, onSubmit: (String, String, String, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("CASH") }
    var channel by remember { mutableStateOf(FundingChannel.CASH) }
    var opening by remember { mutableStateOf("") }
    FormDialog("Tambah akun", onDismiss, confirmEnabled = name.isNotBlank(), onConfirm = { onSubmit(name, type, channel, money(opening)) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama akun") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("CASH", "BANK", "E_WALLET").forEach { value -> FilterChip(selected = type == value, onClick = { type = value }, label = { Text(value.replace('_', ' ')) }) }
        }
        Text("Kanal dana", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = channel == FundingChannel.CASH, onClick = { channel = FundingChannel.CASH }, label = { Text("Cash") })
            FilterChip(selected = channel == FundingChannel.EBUDGET, onClick = { channel = FundingChannel.EBUDGET }, label = { Text("eBudget") })
        }
        MoneyField(opening, { opening = it }, "Saldo awal")
    }
}

@Composable
fun AuditDialog(event: ActivityRow, state: KronUiState, onDismiss: () -> Unit, onRevert: (String, String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    FormDialog("Detail audit", onDismiss, confirmText = "Revert", confirmEnabled = event.reversedByEventId == null && event.type != "REVERSAL" && reason.isNotBlank(), onConfirm = { onRevert(event.id, reason) }) {
        Text(event.title, style = MaterialTheme.typography.titleLarge)
        Text(event.type.replace('_', ' '), color = MaterialTheme.colorScheme.tertiary)
        Text("Tanggal efektif: ${LocalDate.ofEpochDay(event.effectiveEpochDay)}")
        Text("Sumber: ${event.source}")
        Text("Dampak akun: ${displayMoney(event.cashImpact, state.valuesVisible)}")
        Text("Dampak Vault: ${displayMoney(event.vaultImpact, state.valuesVisible)}")
        Text("Dampak kategori: ${displayMoney(event.budgetImpact, state.valuesVisible)}")
        if (event.note.isNotBlank()) Text("Catatan: ${event.note}")
        if (event.reversedByEventId != null) Text("Event sudah direvert", color = MaterialTheme.colorScheme.error)
        else OutlinedTextField(reason, { reason = it }, label = { Text("Alasan revert") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, confirmText: String = "Simpan", confirmEnabled: Boolean, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() } },
        confirmButton = { Button(onClick = onConfirm, enabled = confirmEnabled) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun RecurrencePicker(value: String?, onValue: (String?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text("Pengulangan", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            listOf(null to "Sekali", "MONTHLY" to "Bulanan", "YEARLY" to "Tahunan").forEach { (key, label) ->
                FilterChip(selected = value == key, onClick = { onValue(key) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun ScheduleFields(
    startDate: LocalDate,
    onStartDate: (LocalDate) -> Unit,
    endDate: LocalDate?,
    onEndDate: (LocalDate?) -> Unit,
    intervalCount: Int,
    onIntervalCount: (Int) -> Unit,
    cadence: String?,
    recordNow: Boolean?,
    onRecordNow: ((Boolean) -> Unit)?,
) {
    val unit = if (cadence == "YEARLY") "tahun" else "bulan"
    DateField("Tanggal mulai", startDate, { selected -> selected?.let(onStartDate) })
    DateField("Tanggal akhir (opsional)", endDate, onEndDate, allowClear = true)
    OutlinedTextField(
        value = intervalCount.toString(),
        onValueChange = { onIntervalCount(it.filter(Char::isDigit).toIntOrNull()?.coerceAtLeast(1) ?: 1) },
        label = { Text("Interval") },
        supportingText = { Text("Setiap $intervalCount $unit") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
    if (recordNow != null && onRecordNow != null) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Catat occurrence sekarang", style = MaterialTheme.typography.titleMedium)
                Text("Buat transaksi hari ini lalu jadwalkan occurrence berikutnya", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = recordNow, onCheckedChange = onRecordNow)
        }
    }
}

@Composable
private fun DateField(label: String, date: LocalDate?, onDate: (LocalDate?) -> Unit, allowClear: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f)) {
            Text("$label: ${date ?: "Pilih tanggal"}")
        }
        if (allowClear && date != null) TextButton(onClick = { onDate(null) }) { Text("Hapus") }
    }
    if (open) {
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis -> onDate(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Batal") } },
        ) { DatePicker(state = picker) }
    }
}

@Composable
private fun <T, K> ChoiceField(label: String, selected: K?, values: List<T>, key: (T) -> K, text: (T) -> String, onSelect: (K) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(values.firstOrNull { key(it) == selected }?.let(text) ?: label, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(text(value)) }, onClick = { onSelect(key(value)); expanded = false }) }
        }
    }
}

@Composable
private fun <T, K> ChoiceFieldNullable(label: String, selected: K?, values: List<T>, key: (T) -> K, text: (T) -> String, nullText: String, onSelect: (K?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(values.firstOrNull { key(it) == selected }?.let(text) ?: nullText, modifier = Modifier.weight(1f))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text(nullText) }, onClick = { onSelect(null); expanded = false })
            values.forEach { value -> DropdownMenuItem(text = { Text(text(value)) }, onClick = { onSelect(key(value)); expanded = false }) }
        }
    }
}

private fun money(value: String): Long = parseMoneyInput(value)
private fun channelLabel(channel: String): String = if (channel == FundingChannel.CASH) "Cash" else "eBudget"
