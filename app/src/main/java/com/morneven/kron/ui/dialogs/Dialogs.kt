package com.morneven.kron.ui.dialogs

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.AllocationDraft
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.ExpenseSplitInput
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import com.morneven.kron.ui.theme.KronRed
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.compactIdr
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.formatIdr
import com.morneven.kron.ui.components.MoneyField
import com.morneven.kron.ui.components.parseMoneyInput
import com.morneven.kron.ui.components.signedColor
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

@Composable
fun IncomeDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, String, Long, Long?, Long?, String, String, String?, LocalDate, LocalDate?, Int, Boolean, Uri?, java.io.File?) -> Unit, receiptUri: Uri? = null, cameraFile: java.io.File? = null, onGalleryPick: () -> Unit = {}, onCameraCapture: () -> Unit = {}) {
    val account = state.activeAccount
    val categories = state.categories.filter { it.direction == TransactionDirection.INCOME }
    var channel by remember { mutableStateOf(FundingChannel.CASH) }
    var categoryId by remember { mutableStateOf(categories.firstOrNull()?.id) }
    var targetAllocationId by remember { mutableStateOf<Long?>(null) }
    var amount by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf<String?>(null) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var intervalCount by remember { mutableIntStateOf(1) }
    var recordNow by remember { mutableStateOf(true) }
    FormDialog("Catat pemasukan", onDismiss, confirmEnabled = account != null && money(amount) > 0 && intervalCount > 0 && (endDate == null || !endDate!!.isBefore(startDate)), onConfirm = {
        onSubmit(requireNotNull(account).id, channel, money(amount), categoryId, targetAllocationId, title, note, recurring, startDate, endDate, intervalCount, recordNow, receiptUri, cameraFile)
    }) {
        Text("Akun aktif: ${account?.name ?: "Belum ada"}", style = MaterialTheme.typography.titleMedium)
        Text("Masuk ke kanal", style = MaterialTheme.typography.labelLarge)
        ChannelSelector(channel) { channel = it }
        MoneyField(amount, { amount = it }, "Nominal")
        ChoiceField("Kategori", categoryId, categories, { it.id }, { it.name }) { categoryId = it }
        ChoiceFieldNullable("Tujuan budget (opsional)", targetAllocationId, state.allocations.filter { it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") }, { it.id }, { "${it.portfolioName} · ${it.categoryName}" }, "Pemasukan insidental tanpa tujuan") { targetAllocationId = it }
        Text(
            "Tujuan hanya menjadi penanda. Dana masuk ke akun nyata dan Main Vault, lalu dibooking melalui tindakan budget terpisah.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        Text("Foto bukti", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGalleryPick, modifier = Modifier.weight(1f), enabled = receiptUri == null && cameraFile == null) {
                Icon(androidx.compose.material.icons.Icons.Outlined.PhotoLibrary, contentDescription = null)
                Text(" Pilih galeri")
            }
            OutlinedButton(onClick = onCameraCapture, modifier = Modifier.weight(1f), enabled = receiptUri == null && cameraFile == null) {
                Icon(androidx.compose.material.icons.Icons.Outlined.CameraAlt, contentDescription = null)
                Text(" Ambil foto")
            }
        }
        receiptUri?.let { Text("Galeri: ${it.lastPathSegment ?: "terpilih"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        cameraFile?.let { Text("Kamera: ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        RecurrencePicker(recurring) { recurring = it }
        if (recurring != null) ScheduleFields(startDate, { startDate = it }, endDate, { endDate = it }, intervalCount, { intervalCount = it }, recurring, recordNow, { recordNow = it })
    }
}

private data class SplitDraft(
    var periodId: Long?,
    var categoryId: Long?,
    var allocationId: Long?,
    var amount: String,
    var customCategoryName: String = "",
)

private val seedExpenseNames = setOf("Belanja", "Makanan", "Transportasi", "Tagihan", "Kesehatan", "Hiburan", "Lainnya")

@Composable
private fun UnexpectedCategorySelector(
    split: SplitDraft,
    categories: List<CategoryEntity>,
    onUpdate: (SplitDraft) -> Unit,
) {
    val seedCategories = remember(categories) { categories.filter { it.name in seedExpenseNames } }
    var showCustomField by remember { mutableStateOf(false) }
    var customName by remember { mutableStateOf("") }

    if (showCustomField) {
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text("Nama kategori custom") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val trimmed = customName.trim()
                if (trimmed.isNotBlank()) {
                    onUpdate(split.copy(customCategoryName = trimmed, categoryId = null))
                    showCustomField = false
                    customName = ""
                }
            }) { Text("Gunakan") }
            TextButton(onClick = { showCustomField = false; customName = "" }) { Text("Batal") }
        }
    } else {
        var expanded by remember { mutableStateOf(false) }
        Column {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                val label = if (split.customCategoryName.isNotBlank()) {
                    split.customCategoryName
                } else {
                    seedCategories.firstOrNull { it.id == split.categoryId }?.name ?: "Pilih kategori"
                }
                Text(label, modifier = Modifier.weight(1f))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                seedCategories.forEach { category ->
                    DropdownMenuItem(
                        text = { Text(category.name) },
                        onClick = {
                            onUpdate(split.copy(categoryId = category.id, customCategoryName = ""))
                            expanded = false
                        },
                    )
                }
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("Buat kategori custom...") },
                    onClick = {
                        showCustomField = true
                        expanded = false
                    },
                )
            }
        }
    }
}
private data class BudgetCategoryDraft(var name: String, var amount: String, var cashPercentage: Int = 50)

@Composable
fun ExpenseDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, String, Long, List<ExpenseSplitInput>, String, String, Boolean, String?, LocalDate, LocalDate?, Int, Boolean, Uri?, java.io.File?) -> Unit, receiptUri: Uri? = null, cameraFile: java.io.File? = null, onGalleryPick: () -> Unit = {}, onCameraCapture: () -> Unit = {}) {
    val account = state.activeAccount
    val categories = state.categories.filter { it.direction == TransactionDirection.EXPENSE }
    var channel by remember { mutableStateOf(FundingChannel.CASH) }
    var title by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var recurring by remember { mutableStateOf<String?>(null) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var endDate by remember { mutableStateOf<LocalDate?>(null) }
    var intervalCount by remember { mutableIntStateOf(1) }
    var recordNow by remember { mutableStateOf(true) }
    var unexpected by remember { mutableStateOf(false) }
    val splits = remember { mutableStateListOf(SplitDraft(null, null, null, "")) }
    val accountRow = state.accountBalances.firstOrNull { it.id == account?.id }
    val accountBalance = if (channel == FundingChannel.CASH) accountRow?.cashBalance ?: 0L else accountRow?.eBudgetBalance ?: 0L
    val splitTotal = splits.sumOf { money(it.amount) }
    val activeAllocations = state.allocations.filter { it.fundingChannel == channel && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") }
    val activeAllocationIds = activeAllocations.mapTo(mutableSetOf()) { it.id }
    val validBudgetSplits = unexpected || splits.all { it.allocationId in activeAllocationIds && it.categoryId != null }
    val enoughBalance = splitTotal <= accountBalance
    FormDialog("Catat pengeluaran", onDismiss, confirmEnabled = account != null && splitTotal > 0 && enoughBalance && splits.all { money(it.amount) > 0 } && validBudgetSplits && intervalCount > 0 && (endDate == null || !endDate!!.isBefore(startDate)), onConfirm = {
        val customNote = splits.fold(note) { acc, split ->
            if (split.customCategoryName.isNotBlank()) "$acc [Kategori: ${split.customCategoryName}]" else acc
        }
        onSubmit(requireNotNull(account).id, channel, splitTotal, splits.map { ExpenseSplitInput(it.categoryId, if (unexpected) null else it.allocationId, money(it.amount)) }, title, customNote, unexpected, recurring, startDate, endDate, intervalCount, recordNow, receiptUri, cameraFile)
    }) {
        Text("Akun aktif: ${account?.name ?: "Belum ada"}", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !unexpected,
                onClick = {
                    unexpected = false
                    splits.indices.forEach { index ->
                        splits[index] = splits[index].copy(periodId = null, allocationId = null, categoryId = null)
                    }
                },
                label = { Text("Untuk budget") },
            )
            FilterChip(
                selected = unexpected,
                onClick = {
                    unexpected = true
                    channel = FundingChannel.CASH
                    splits.indices.forEach { index ->
                        splits[index] = splits[index].copy(periodId = null, allocationId = null)
                    }
                },
                label = { Text("Tak terduga") },
            )
        }
        if (!unexpected) {
            Text("Kanal pembayaran", style = MaterialTheme.typography.labelLarge)
            ChannelSelector(channel) { value ->
                channel = value
                splits.indices.forEach { index ->
                    splits[index] = splits[index].copy(periodId = null, allocationId = null, categoryId = null)
                }
            }
        } else {
            Text("Kanal pembayaran", style = MaterialTheme.typography.labelLarge)
            ChannelSelector(channel) { value ->
                channel = value
            }
        }
        Text(if (unexpected) "Langsung mengurangi Cash dan tidak mengurangi budget." else "Pilih alokasi budget terlebih dahulu, lalu kategori yang terhubung.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        Text("SPLIT TRANSAKSI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        splits.forEachIndexed { index, split ->
            Column(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bagian ${index + 1}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    if (splits.size > 1) IconButton(onClick = { splits.removeAt(index) }) { Icon(Icons.Outlined.DeleteOutline, contentDescription = "Hapus split") }
                }
                if (!unexpected) {
                    val budgetChoices = activeAllocations.distinctBy { it.periodId }
                    ChoiceFieldNullable(
                        "Budget",
                        split.periodId,
                        budgetChoices,
                        { it.periodId },
                        { "${it.portfolioName} (${LocalDate.ofEpochDay(it.startEpochDay)} sampai ${LocalDate.ofEpochDay(it.endEpochDay)})" },
                        "Pilih budget",
                    ) { value ->
                        splits[index] = split.copy(periodId = value, allocationId = null, categoryId = null)
                    }
                    val categoryChoices = activeAllocations.filter { it.periodId == split.periodId }
                    ChoiceFieldNullable(
                        "Kategori budget",
                        split.allocationId,
                        categoryChoices,
                        { it.id },
                        { "${it.categoryName} · sisa ${displayMoney(it.availableAmount, state.valuesVisible)}" },
                        if (split.periodId == null) "Pilih budget terlebih dahulu" else "Pilih kategori",
                    ) { allocationId ->
                        val allocation = categoryChoices.firstOrNull { it.id == allocationId }
                        splits[index] = split.copy(allocationId = allocationId, categoryId = allocation?.categoryId)
                    }
                } else {
                    UnexpectedCategorySelector(
                        split = split,
                        categories = categories,
                        onUpdate = { updated -> splits[index] = updated },
                    )
                }
                MoneyField(split.amount, { value -> splits[index] = split.copy(amount = value) }, "Nominal bagian")
            }
        }
        TextButton(onClick = { splits.add(SplitDraft(null, null, null, "")) }) {
            Icon(Icons.Outlined.Add, contentDescription = null)
            Text("Tambah split")
        }
        Text("Total ${displayMoney(splitTotal, state.valuesVisible)}", style = MaterialTheme.typography.titleMedium)
        if (splitTotal > accountBalance && splitTotal > 0) {
            Text("Saldo akun tidak mencukupi. Tersedia ${displayMoney(accountBalance, state.valuesVisible)}.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        Text("Foto bukti", style = MaterialTheme.typography.labelLarge)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onGalleryPick, modifier = Modifier.weight(1f), enabled = receiptUri == null && cameraFile == null) {
                Icon(androidx.compose.material.icons.Icons.Outlined.PhotoLibrary, contentDescription = null)
                Text(" Pilih galeri")
            }
            OutlinedButton(onClick = onCameraCapture, modifier = Modifier.weight(1f), enabled = receiptUri == null && cameraFile == null) {
                Icon(androidx.compose.material.icons.Icons.Outlined.CameraAlt, contentDescription = null)
                Text(" Ambil foto")
            }
        }
        receiptUri?.let { Text("Galeri: ${it.lastPathSegment ?: "terpilih"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        cameraFile?.let { Text("Kamera: ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
        if (splits.size == 1) {
            RecurrencePicker(recurring) { recurring = it }
            if (recurring != null) ScheduleFields(startDate, { startDate = it }, endDate, { endDate = it }, intervalCount, { intervalCount = it }, recurring, recordNow, { recordNow = it })
        }
    }
}

@Composable
fun TransferDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, String, Long, String, Long, String) -> Unit) {
    val accounts = state.accounts
    val sourceAccount = state.activeAccount
    var fromChannel by remember { mutableStateOf(FundingChannel.CASH) }
    var toAccountId by remember { mutableStateOf(sourceAccount?.id ?: accounts.firstOrNull()?.id) }
    var toChannel by remember { mutableStateOf(FundingChannel.EBUDGET) }
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val sourceRow = state.accountBalances.firstOrNull { it.id == sourceAccount?.id }
    val sourceBalance = if (fromChannel == FundingChannel.CASH) sourceRow?.cashBalance ?: 0L else sourceRow?.eBudgetBalance ?: 0L
    val targetAccount = accounts.firstOrNull { it.id == toAccountId }
    val distinctTarget = sourceAccount?.id != toAccountId || fromChannel != toChannel
    FormDialog("Transfer dana", onDismiss, confirmEnabled = sourceAccount != null && toAccountId != null && distinctTarget && money(amount) in 1..sourceBalance, onConfirm = {
        onSubmit(requireNotNull(sourceAccount).id, fromChannel, requireNotNull(toAccountId), toChannel, money(amount), note)
    }) {
        Text("Akun sumber: ${sourceAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.titleMedium)
        Text("Kanal sumber", style = MaterialTheme.typography.labelLarge)
        ChannelSelector(fromChannel) { fromChannel = it }
        Text("Tersedia ${displayMoney(sourceBalance, state.valuesVisible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceField("Akun tujuan", toAccountId, accounts, { it.id }, { it.name }) { toAccountId = it }
        Text("Kanal tujuan", style = MaterialTheme.typography.labelLarge)
        ChannelSelector(toChannel) { toChannel = it }
        Text("Dari ${sourceAccount?.name ?: "-"} · ${channelLabel(fromChannel)} ke ${targetAccount?.name ?: "-"} · ${channelLabel(toChannel)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        if (fromChannel != toChannel) {
            Text("Transfer lintas kanal hanya memakai Main Vault. Dana kategori yang sudah terbooking tidak dapat dipindahkan dari sini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
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
        MoneyField(plannedIncome, { plannedIncome = it }, "Proyeksi hasil RAB (opsional)")
        Text("Isi hanya jika RAB ini diharapkan menghasilkan pemasukan, misalnya kegiatan bisnis. Nilai ini bukan total budget dan tidak menambah saldo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun BudgetDetailDialog(state: KronUiState, periodId: Long, readOnly: Boolean = false, onDismiss: () -> Unit, onCorrect: (Long, Long, String) -> Unit) {
    val rows = state.allocations.filter { it.periodId == periodId }
    var correctionId by remember { mutableStateOf<Long?>(null) }
    var correctedAmount by remember { mutableStateOf("") }
    var reason by remember { mutableStateOf("Koreksi nominal budget") }
    val selected = rows.firstOrNull { it.id == correctionId }
    val validCorrection = selected != null && money(correctedAmount) >= 0 && money(correctedAmount) != selected.plannedAmount && reason.isNotBlank()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (readOnly) "Detail budget arsip" else "Detail budget", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Tutup") }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                rows.firstOrNull()?.let { row ->
                    Text(row.portfolioName, style = MaterialTheme.typography.titleLarge)
                    Text("${LocalDate.ofEpochDay(row.startEpochDay)} sampai ${LocalDate.ofEpochDay(row.endEpochDay)} • ${row.periodStatus.replace('_', ' ')}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (readOnly) Text("Mode read-only. Pulihkan portfolio untuk melakukan perubahan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                val budgetMoney: (Long) -> String = { value ->
                    if (!state.valuesVisible) "Rp ***"
                    else if (abs(value) >= 1_000_000) compactIdr(value)
                    else formatIdr(value)
                }
                rows.forEach { row ->
                    HudCard {
                        Column(Modifier.fillMaxWidth()) {
                            ChannelBadge(row.fundingChannel)
                            Text(row.categoryName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Rencana", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(budgetMoney(row.plannedAmount), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Terpakai", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(budgetMoney(row.spentAmount), style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Sisa", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    budgetMoney(row.availableAmount),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (row.availableAmount < 0) MaterialTheme.colorScheme.error
                                    else if (row.availableAmount > 0) KronGold
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!readOnly) TextButton(onClick = { correctionId = row.id; correctedAmount = row.plannedAmount.toString() }) { Text("Koreksi") }
                            }
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
                if (!readOnly) {
                    HorizontalDivider()
                    Button(
                        onClick = { onCorrect(requireNotNull(correctionId), money(correctedAmount), reason); onDismiss() },
                        enabled = validCorrection,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).height(52.dp),
                    ) { Text("Simpan koreksi") }
                }
            }
        }
    }
}

@Composable
fun BudgetHistoryDialog(
    state: KronUiState,
    portfolioId: Long,
    onDismiss: () -> Unit,
    onDetail: (Long) -> Unit,
) {
    val portfolio = state.portfolios.firstOrNull { it.id == portfolioId }
        ?: state.archivedPortfolios.firstOrNull { it.id == portfolioId }
        ?: return
    val periodIds = state.periods.filter { it.portfolioId == portfolioId }.map { it.id }.toSet()
    val periodAllocations = state.allocations.filter { it.periodId in periodIds }
    val periods = state.periods
        .filter { it.portfolioId == portfolioId }
        .sortedByDescending { it.startEpochDay }
    val budgetMoney: (Long) -> String = { value ->
        if (!state.valuesVisible) "Rp ***"
        else if (abs(value) >= 1_000_000) compactIdr(value)
        else formatIdr(value)
    }
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu", "Sep", "Okt", "Nov", "Des")
    val chartData = remember(periods, periodAllocations) {
        periods.sortedBy { it.startEpochDay }.map { period ->
            val d = LocalDate.ofEpochDay(period.startEpochDay)
            val rows = periodAllocations.filter { it.periodId == period.id }
            PeriodChartData(
                label = "${monthNames[d.monthValue - 1]} ${d.year % 100}",
                planned = rows.sumOf { it.plannedAmount },
                spent = rows.sumOf { it.spentAmount },
            )
        }
    }
    FormDialog("Riwayat ${portfolio.name}", onDismiss, "Tutup", true, onDismiss) {
        Text("Semua periode ${portfolio.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (chartData.isNotEmpty() && state.valuesVisible) {
            Spacer(Modifier.height(8.dp))
            BudgetBarChart(
                data = chartData,
                modifier = Modifier.fillMaxWidth().height(180.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
        periods.forEach { period ->
            val rows = periodAllocations.filter { it.periodId == period.id }
            val totalPlanned = rows.sumOf { it.plannedAmount }
            val totalSpent = rows.sumOf { it.spentAmount }
            val totalRemaining = rows.sumOf { it.availableAmount }
            val cashRemaining = rows.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.availableAmount }
            val eBudgetRemaining = rows.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.availableAmount }
            HudCard(accent = if (totalRemaining < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${LocalDate.ofEpochDay(period.startEpochDay)} sampai ${LocalDate.ofEpochDay(period.endEpochDay)}", style = MaterialTheme.typography.titleMedium)
                        Text(period.status.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(budgetMoney(totalRemaining), style = MaterialTheme.typography.titleMedium, color = if (totalRemaining < 0) MaterialTheme.colorScheme.error else if (totalRemaining > 0) KronGold else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Rencana", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(budgetMoney(totalPlanned), style = MaterialTheme.typography.bodyMedium)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Terpakai", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(budgetMoney(totalSpent), style = MaterialTheme.typography.bodyMedium)
                }
                if (rows.any { it.fundingChannel == FundingChannel.CASH }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sisa Cash", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(budgetMoney(cashRemaining), style = MaterialTheme.typography.bodySmall, color = signedColor(cashRemaining))
                    }
                }
                if (rows.any { it.fundingChannel == FundingChannel.EBUDGET }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sisa eBudget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(budgetMoney(eBudgetRemaining), style = MaterialTheme.typography.bodySmall, color = signedColor(eBudgetRemaining))
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                TextButton(onClick = { onDetail(period.id) }) { Text("Detail periode ini") }
            }
        }
    }
}

private data class PeriodChartData(val label: String, val planned: Long, val spent: Long)

@Composable
private fun BudgetBarChart(data: List<PeriodChartData>, modifier: Modifier) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f)
    val maxValue = data.maxOf { maxOf(it.planned, it.spent) }.coerceAtLeast(1)
    val barCount = data.size
    Column(modifier) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Canvas(Modifier.fillMaxSize()) {
                val chartH = size.height * 0.88f
                val groupW = size.width / barCount
                val barW = (groupW * 0.35f).coerceAtMost(32f)
                val gap = (groupW - barW * 2) / 3f
                repeat(4) { i ->
                    val y = size.height * (0.06f + 0.88f * i / 3f)
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                data.forEachIndexed { idx, item ->
                    val cx = idx * groupW
                    val plannedH = (item.planned.toFloat() / maxValue) * chartH
                    val spentH = (item.spent.toFloat() / maxValue) * chartH
                    val spentColor = if (item.spent <= item.planned) KronGreen else KronRed
                    drawRect(
                        color = primaryColor.copy(alpha = 0.25f),
                        topLeft = Offset(cx + gap, size.height * 0.06f + chartH - plannedH),
                        size = Size(barW, plannedH),
                    )
                    drawRect(
                        color = spentColor,
                        topLeft = Offset(cx + gap * 2 + barW, size.height * 0.06f + chartH - spentH),
                        size = Size(barW, spentH),
                    )
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            data.forEach { item ->
                Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
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
    val account = state.activeAccount
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val accountRow = state.accountBalances.firstOrNull { it.id == account?.id }
    val accountBalance = if (source?.fundingChannel == FundingChannel.CASH) accountRow?.cashBalance ?: 0L else accountRow?.eBudgetBalance ?: 0L
    val limit = minOf(source?.availableAmount ?: 0, accountBalance)
    FormDialog("Pindahkan Cash dan eBudget", onDismiss, confirmEnabled = source != null && account != null && money(amount) in 1..limit, onConfirm = {
        onSubmit(requireNotNull(allocationId), requireNotNull(account).id, account.id, money(amount), note)
    }) {
        ChoiceField("Kategori sumber", allocationId, sources, { it.id }, { "${it.categoryName} · ${channelLabel(it.fundingChannel)} · ${displayMoney(it.availableAmount, state.valuesVisible)}" }) { allocationId = it }
        Text("Akun aktif: ${account?.name ?: "Belum ada"}", style = MaterialTheme.typography.titleMedium)
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
fun AccountDialog(onDismiss: () -> Unit, onSubmit: (String, Long, Long) -> Unit) {
    var name by remember { mutableStateOf("") }
    var openingCash by remember { mutableStateOf("") }
    var openingEBudget by remember { mutableStateOf("") }
    FormDialog("Tambah akun", onDismiss, confirmEnabled = name.isNotBlank(), onConfirm = { onSubmit(name, money(openingCash), money(openingEBudget)) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama akun") }, modifier = Modifier.fillMaxWidth())
        Text("Setiap akun otomatis memiliki kanal Cash dan eBudget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyField(openingCash, { openingCash = it }, "Saldo awal Cash")
        MoneyField(openingEBudget, { openingEBudget = it }, "Saldo awal eBudget")
    }
}

@Composable
fun EditAccountDialog(account: AccountEntity, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var name by remember { mutableStateOf(account.name) }
    FormDialog("Edit akun", onDismiss, confirmEnabled = name.isNotBlank(), onConfirm = { onSubmit(name.trim()) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama akun") }, modifier = Modifier.fillMaxWidth())
        Text("Kanal Cash dan eBudget selalu tersedia dan tidak dapat dihapus.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AuditDialog(
    event: ActivityRow,
    state: KronUiState,
    onDismiss: () -> Unit,
    onGalleryPick: (String) -> Unit,
    onCameraCapture: (String) -> Unit,
    onRevert: (String, String) -> Unit,
) {
    var reason by remember { mutableStateOf("") }
    val lifecycleEvent = event.type in setOf("ARCHIVE", "RESTORE")
    val receipts = state.receipts.filter { it.eventId == event.id }
    FormDialog("Detail audit", onDismiss, confirmText = "Revert", confirmEnabled = !lifecycleEvent && event.reversedByEventId == null && event.type != "REVERSAL" && reason.isNotBlank(), onConfirm = { onRevert(event.id, reason) }) {
        Text(event.title, style = MaterialTheme.typography.titleLarge)
        Text(event.type.replace('_', ' '), color = MaterialTheme.colorScheme.tertiary)
        Text("Tanggal efektif: ${LocalDate.ofEpochDay(event.effectiveEpochDay)}")
        Text("Sumber: ${event.source}")
        Text("Dampak akun: ${displayMoney(event.cashImpact, state.valuesVisible)}")
        Text("Dampak Vault: ${displayMoney(event.vaultImpact, state.valuesVisible)}")
        Text("Dampak kategori: ${displayMoney(event.budgetImpact, state.valuesVisible)}")
        if (event.note.isNotBlank()) Text("Catatan: ${event.note}")
        HorizontalDivider()
        Text("Foto bukti (${receipts.size})", style = MaterialTheme.typography.titleMedium)
        if (receipts.isEmpty()) {
            Text("Belum ada foto bukti untuk event ini.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            receipts.forEach { receipt ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(receipt.displayName, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${receipt.mimeType} · ${receipt.byteSize.coerceAtLeast(0) / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (event.type in setOf("INCOME", "EXPENSE", "UNEXPECTED_EXPENSE")) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onCameraCapture(event.id) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.CameraAlt, contentDescription = null)
                    Text(" Ambil foto")
                }
                OutlinedButton(onClick = { onGalleryPick(event.id) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                    Text(" Pilih galeri")
                }
            }
            Text("Foto disalin ke penyimpanan privat dan dienkripsi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (lifecycleEvent) Text("Event lifecycle bersifat read-only. Gunakan tab Arsip untuk memulihkan atau mengarsipkan kembali.", color = MaterialTheme.colorScheme.tertiary)
        else if (event.reversedByEventId != null) Text("Event sudah direvert", color = MaterialTheme.colorScheme.error)
        else OutlinedTextField(reason, { reason = it }, label = { Text("Alasan revert") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, confirmText: String = "Simpan", confirmEnabled: Boolean, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Batal") }
                }
                HorizontalDivider()
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    content()
                    Spacer(Modifier.height(24.dp))
                }
                HorizontalDivider()
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp).height(52.dp),
                ) { Text(confirmText) }
            }
        }
    }
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

@Composable
private fun ChannelSelector(selected: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = selected == FundingChannel.CASH, onClick = { onSelect(FundingChannel.CASH) }, label = { Text("Cash") })
        FilterChip(selected = selected == FundingChannel.EBUDGET, onClick = { onSelect(FundingChannel.EBUDGET) }, label = { Text("eBudget") })
    }
}
