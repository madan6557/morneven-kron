package com.morneven.kron.ui.dialogs

import android.net.Uri
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.AddAPhoto
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.sp
import com.morneven.kron.ui.components.ActivityStatusTag
import com.morneven.kron.ui.components.CategoryAvatar
import com.morneven.kron.ui.components.CategoryIconPicker
import com.morneven.kron.ui.components.primaryImpact
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.AuditSnapshotEntity
import com.morneven.kron.data.AccountSharingMode
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.AllocationDraft
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.data.ExpenseSplitInput
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.PeriodStatus
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.data.ReceiptEntity
import com.morneven.kron.data.ResolutionAudit
import com.morneven.kron.data.ResolutionAuditDetail
import com.morneven.kron.evidence.EvidenceHealth
import com.morneven.kron.evidence.EvidencePackageManager
import com.morneven.kron.evidence.EvidenceVerificationResult
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import com.morneven.kron.ui.theme.KronRed
import com.morneven.kron.ui.theme.KronBlue
import com.morneven.kron.ui.theme.KronButtonShape
import com.morneven.kron.ui.theme.KronFieldShape
import com.morneven.kron.ui.theme.KronChipShape
import com.morneven.kron.ui.theme.KronSubCardShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.Payments
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.CalculatorKeypadHostState
import com.morneven.kron.ui.components.CalculatorKeypadView
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.LocalCalculatorKeypadHost
import com.morneven.kron.ui.components.compactIdr
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.eventTypeLabel
import com.morneven.kron.ui.components.formatIdr
import com.morneven.kron.ui.components.MoneyField
import com.morneven.kron.ui.components.parseMoneyInput
import com.morneven.kron.ui.components.signedColor
import androidx.compose.ui.text.font.FontWeight
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs
import kotlinx.coroutines.delay

private val dialogDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))

private val dateStateSaver = Saver<MutableState<LocalDate>, Long>(
    save = { it.value.toEpochDay() },
    restore = { mutableStateOf(LocalDate.ofEpochDay(it)) },
)
private val nullableDateStateSaver = Saver<MutableState<LocalDate?>, Long>(
    save = { it.value?.toEpochDay() ?: Long.MIN_VALUE },
    restore = { mutableStateOf(it.takeUnless { day -> day == Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)) },
)

@Composable
private fun rememberDate(initial: LocalDate) = rememberSaveable(saver = dateStateSaver) { mutableStateOf(initial) }

@Composable
private fun rememberNullableDate(initial: LocalDate? = null) = rememberSaveable(saver = nullableDateStateSaver) { mutableStateOf(initial) }

@Composable
fun IncomeDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, String, Long, Long?, Long?, String, String, String?, LocalDate, LocalDate?, Int, Boolean, Uri?, java.io.File?) -> Unit, receiptUri: Uri? = null, cameraFile: java.io.File? = null, onGalleryPick: () -> Unit = {}, onCameraCapture: () -> Unit = {}) {
    val account = state.activeAccount
    val categories = state.categories.filter { it.direction == TransactionDirection.INCOME }
    var channel by rememberSaveable { mutableStateOf(FundingChannel.CASH) }
    var categoryId by rememberSaveable { mutableStateOf(categories.firstOrNull()?.id) }
    var targetAllocationId by rememberSaveable { mutableStateOf<Long?>(null) }
    var amount by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var recurring by rememberSaveable { mutableStateOf<String?>(null) }
    var startDate by rememberDate(LocalDate.now())
    var endDate by rememberNullableDate()
    var intervalCount by rememberSaveable { mutableIntStateOf(1) }
    var recordNow by rememberSaveable { mutableStateOf(true) }
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
        if (money(amount) > 0) {
            val current = state.accountBalances.firstOrNull { it.id == account?.id }
            val channelBalance = if (channel == FundingChannel.CASH) current?.cashBalance ?: 0L else current?.eBudgetBalance ?: 0L
            LedgerPreviewCard(
                debit = "Aset ${channelLabel(channel)} bertambah ${displayMoney(money(amount), state.valuesVisible)}",
                credit = "Pemasukan tercatat ${displayMoney(money(amount), state.valuesVisible)}",
                budget = "Main Vault ${channelLabel(channel)} bertambah. Booking budget tidak berubah.",
                after = "Saldo kanal setelah transaksi ${displayMoney(channelBalance + money(amount), state.valuesVisible)}",
            )
        }
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

private val splitDraftsSaver = Saver<SnapshotStateList<SplitDraft>, ArrayList<String>>(
    save = { drafts ->
        ArrayList(drafts.flatMap { draft ->
            listOf(
                draft.periodId?.toString().orEmpty(),
                draft.categoryId?.toString().orEmpty(),
                draft.allocationId?.toString().orEmpty(),
                draft.amount,
                draft.customCategoryName,
            )
        })
    },
    restore = { values ->
        mutableStateListOf<SplitDraft>().apply {
            values.chunked(5).forEach { value ->
                add(SplitDraft(value[0].toLongOrNull(), value[1].toLongOrNull(), value[2].toLongOrNull(), value[3], value[4]))
            }
        }
    },
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

private val budgetCategoryDraftsSaver = Saver<SnapshotStateList<BudgetCategoryDraft>, ArrayList<String>>(
    save = { drafts -> ArrayList(drafts.flatMap { listOf(it.name, it.amount, it.cashPercentage.toString()) }) },
    restore = { values ->
        mutableStateListOf<BudgetCategoryDraft>().apply {
            values.chunked(3).forEach { value -> add(BudgetCategoryDraft(value[0], value[1], value[2].toInt())) }
        }
    },
)

private data class BudgetIncreaseCategory(
    val name: String,
    val oldCash: Long = 0,
    val oldEBudget: Long = 0,
    val newCash: Long,
    val newEBudget: Long,
    val changed: Boolean = true,
)

private fun periodBudgetIncreaseCategories(
    rows: List<AllocationBalanceRow>,
    changedCategoryId: Long?,
    changedName: String,
    newCash: Long,
    newEBudget: Long,
): List<BudgetIncreaseCategory> {
    val categories = rows.groupBy { it.categoryId }.map { (categoryId, categoryRows) ->
        val oldCash = categoryRows.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { it.plannedAmount }
        val oldEBudget = categoryRows.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { it.plannedAmount }
        val changed = categoryId == changedCategoryId
        BudgetIncreaseCategory(
            name = categoryRows.first().categoryName,
            oldCash = oldCash,
            oldEBudget = oldEBudget,
            newCash = if (changed) newCash else oldCash,
            newEBudget = if (changed) newEBudget else oldEBudget,
            changed = changed,
        )
    }.toMutableList()
    if (changedCategoryId == null || categories.none { it.changed }) {
        categories += BudgetIncreaseCategory(changedName, newCash = newCash, newEBudget = newEBudget)
    }
    return categories.sortedWith(compareByDescending<BudgetIncreaseCategory> { it.changed }.thenBy { it.name })
}

private data class BudgetIncreaseRequest(
    val previousTotal: Long,
    val proposedTotal: Long,
    val categories: List<BudgetIncreaseCategory>,
    val onConfirm: () -> Unit,
)

@Composable
private fun BudgetIncreaseDialog(
    request: BudgetIncreaseRequest,
    valuesVisible: Boolean,
    onDismiss: () -> Unit,
) {
    val amount: (Long) -> String = { displayMoney(it, valuesVisible) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Total budget terlampaui") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Total sebelumnya / batas: ${amount(request.previousTotal)}")
                Text("Total rencana kategori setelah perubahan: ${amount(request.proposedTotal)}")
                Text("Kelebihan: ${amount(request.proposedTotal - request.previousTotal)}", color = MaterialTheme.colorScheme.error)
                HorizontalDivider()
                Text("Kategori dan tipe dana", style = MaterialTheme.typography.titleSmall)
                request.categories.forEach { category ->
                    Text(category.name + if (category.changed) " (diubah)" else "", style = MaterialTheme.typography.labelLarge)
                    Text("Cash: ${amount(category.oldCash)} menjadi ${amount(category.newCash)}", style = MaterialTheme.typography.bodySmall)
                    Text("eBudget: ${amount(category.oldEBudget)} menjadi ${amount(category.newEBudget)}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Jika dilanjutkan, batas total budget naik mengikuti rencana kategori di atas.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = request.onConfirm) { Text("Abaikan batas & naikkan total") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Kembali") } },
    )
}

@Composable
fun ExpenseDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, String, Long, List<ExpenseSplitInput>, String, String, Boolean, String?, LocalDate, LocalDate?, Int, Boolean, Uri?, java.io.File?) -> Unit, receiptUri: Uri? = null, cameraFile: java.io.File? = null, onGalleryPick: () -> Unit = {}, onCameraCapture: () -> Unit = {}) {
    val account = state.activeAccount
    val categories = state.categories.filter { it.direction == TransactionDirection.EXPENSE }
    var channel by rememberSaveable { mutableStateOf(FundingChannel.CASH) }
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var recurring by rememberSaveable { mutableStateOf<String?>(null) }
    var startDate by rememberDate(LocalDate.now())
    var endDate by rememberNullableDate()
    var intervalCount by rememberSaveable { mutableIntStateOf(1) }
    var recordNow by rememberSaveable { mutableStateOf(true) }
    var unexpected by rememberSaveable { mutableStateOf(false) }
    val splits = rememberSaveable(saver = splitDraftsSaver) { mutableStateListOf(SplitDraft(null, null, null, "")) }
    val accountRow = state.accountBalances.firstOrNull { it.id == account?.id }
    val accountBalance = if (channel == FundingChannel.CASH) accountRow?.cashBalance ?: 0L else accountRow?.eBudgetBalance ?: 0L
    val splitTotal = splits.sumOf { money(it.amount) }
    val activeAllocations = state.allocations.filter { it.fundingChannel == channel && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") && it.isActive }
    val activeAllocationIds = activeAllocations.mapTo(mutableSetOf()) { it.id }
    val validBudgetSplits = unexpected || splits.all { it.allocationId in activeAllocationIds && it.categoryId != null }
    val enoughBalance = splitTotal <= accountBalance
    val channelVault = if (channel == FundingChannel.CASH) state.vaultCash else state.vaultEBudget
    val bookedUsage = (splitTotal - channelVault).coerceAtLeast(0L)
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
        Text(
            if (unexpected) {
                "Langsung mengurangi saldo ${channelLabel(channel)} dan Main Vault, tanpa memakai alokasi kategori."
            } else {
                "Pilih alokasi budget terlebih dahulu, lalu kategori yang terhubung."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
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
        // An unexpected expense is drawn from the Main Vault, and the account balance check above
        // cannot tell whether that money is already promised to a budget category.
        if (unexpected && bookedUsage > 0L && enoughBalance) {
            Text(
                "Pengeluaran ini memakai ${displayMoney(bookedUsage, state.valuesVisible)} dana yang sudah dibooking ke budget. " +
                    "Main Vault ${channelLabel(channel)} tersisa ${displayMoney(channelVault, state.valuesVisible)} dan akan menjadi minus.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        if (splitTotal > 0) {
            LedgerPreviewCard(
                debit = "Biaya bertambah ${displayMoney(splitTotal, state.valuesVisible)}",
                credit = "Aset ${channelLabel(channel)} berkurang ${displayMoney(splitTotal, state.valuesVisible)}",
                budget = if (unexpected) "Tidak mengurangi budget. Dicatat sebagai pengeluaran tak terduga." else "Sisa kategori terpilih berkurang sesuai setiap split.",
                after = "Saldo kanal setelah transaksi ${displayMoney(accountBalance - splitTotal, state.valuesVisible)}",
            )
        }
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
    val sourceAccount = state.activeAccount
    val allowedAccounts = remember(state.accounts, sourceAccount) {
        state.accounts.filter { target ->
            !target.isArchived && (sourceAccount?.sharingMode == AccountSharingMode.TEAM || target.sharingMode != AccountSharingMode.TEAM)
        }
    }
    var fromChannel by rememberSaveable { mutableStateOf(FundingChannel.CASH) }
    var toAccountId by rememberSaveable { mutableStateOf(sourceAccount?.id ?: allowedAccounts.firstOrNull()?.id) }
    var toChannel by rememberSaveable { mutableStateOf(FundingChannel.EBUDGET) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val availableVault = if (fromChannel == FundingChannel.CASH) state.vaultCash else state.vaultEBudget
    val targetAccount = allowedAccounts.firstOrNull { it.id == toAccountId } ?: state.accounts.firstOrNull { it.id == toAccountId }
    val distinctTarget = sourceAccount?.id != toAccountId || fromChannel != toChannel
    FormDialog("Transfer dana", onDismiss, confirmEnabled = sourceAccount != null && toAccountId != null && distinctTarget && money(amount) in 1..availableVault, onConfirm = {
        onSubmit(requireNotNull(sourceAccount).id, fromChannel, requireNotNull(toAccountId), toChannel, money(amount), note)
    }) {
        Text("Akun sumber: ${sourceAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.titleMedium)
        Text("Kanal sumber", style = MaterialTheme.typography.labelLarge)
        ChannelSelector(fromChannel) { fromChannel = it }
        Text("Tersedia di Main Vault ${displayMoney(availableVault, state.valuesVisible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceField("Akun tujuan", toAccountId, allowedAccounts, { it.id }, { it.name }) { toAccountId = it }
        Text("Kanal tujuan", style = MaterialTheme.typography.labelLarge)
        ChannelSelector(toChannel) { toChannel = it }
        Text("Dari ${sourceAccount?.name ?: "-"} · ${channelLabel(fromChannel)} ke ${targetAccount?.name ?: "-"} · ${channelLabel(toChannel)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        Text("Transfer dana selalu bersumber dari Main Vault. Dana kategori yang sudah terbooking tidak dapat ditransfer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        MoneyField(amount, { amount = it }, "Nominal")
        OutlinedTextField(note, { note = it }, label = { Text("Catatan") }, modifier = Modifier.fillMaxWidth())
        if (money(amount) > 0 && sourceAccount != null && targetAccount != null) {
            val isInterAccount = sourceAccount.id != targetAccount.id
            val flowDesc = if (isInterAccount) "Transfer antar akun mengubah saldo kas masing-masing akun." else "Konversi kanal tidak mengubah total saldo akun."
            LedgerPreviewCard(
                debit = "Aset tujuan ${targetAccount.name} ${channelLabel(toChannel)} bertambah ${displayMoney(money(amount), state.valuesVisible)}",
                credit = "Aset sumber ${sourceAccount.name} ${channelLabel(fromChannel)} berkurang ${displayMoney(money(amount), state.valuesVisible)}",
                budget = flowDesc,
                after = "Sisa Main Vault sumber setelah transfer ${displayMoney(availableVault - money(amount), state.valuesVisible)}",
            )
        }
    }
}

@Composable
private fun LedgerPreviewCard(debit: String, credit: String, budget: String, after: String) {
    HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)) {
        Text("Preview pencatatan", style = MaterialTheme.typography.titleMedium)
        Text("Debit: $debit", style = MaterialTheme.typography.bodySmall)
        Text("Kredit: $credit", style = MaterialTheme.typography.bodySmall)
        Text(budget, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(after, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
fun PortfolioDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (String, String, Long, Boolean, List<AllocationDraft>, Long, LocalDate, LocalDate?, Int) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var cadence by rememberSaveable { mutableStateOf("MONTHLY") }
    var plannedIncome by rememberSaveable { mutableStateOf("") }
    var budgetLimit by rememberSaveable { mutableStateOf("") }
    var pendingIncrease by remember { mutableStateOf<BudgetIncreaseRequest?>(null) }
    var rollover by rememberSaveable { mutableStateOf(true) }
    var startDate by rememberDate(LocalDate.now())
    var endDate by rememberNullableDate()
    var intervalCount by rememberSaveable { mutableIntStateOf(1) }
    val budgetCategories = rememberSaveable(saver = budgetCategoryDraftsSaver) { mutableStateListOf(BudgetCategoryDraft("", "")) }
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
    val limit = money(budgetLimit)
    FormDialog("Buat portfolio RAB", onDismiss, confirmEnabled = name.isNotBlank() && limit > 0 && budgetCategories.all { it.name.isNotBlank() && money(it.amount) > 0 } && intervalCount > 0 && (endDate == null || !endDate!!.isBefore(startDate)), onConfirm = {
        if (totalBudget > limit) {
            pendingIncrease = BudgetIncreaseRequest(
                previousTotal = limit,
                proposedTotal = totalBudget,
                categories = budgetCategories.map { category ->
                    val total = money(category.amount)
                    val cash = total * category.cashPercentage / 100
                    BudgetIncreaseCategory(category.name.trim(), newCash = cash, newEBudget = total - cash)
                },
                onConfirm = { pendingIncrease = null; onSubmit(name, cadence, money(plannedIncome), rollover, drafts, totalBudget, startDate, endDate, intervalCount) },
            )
        } else {
            onSubmit(name, cadence, money(plannedIncome), rollover, drafts, limit, startDate, endDate, intervalCount)
        }
    }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama portfolio") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = cadence == "MONTHLY", onClick = { cadence = "MONTHLY" }, label = { Text("Bulanan") })
            FilterChip(selected = cadence == "YEARLY", onClick = { cadence = "YEARLY" }, label = { Text("Tahunan") })
        }
        ScheduleFields(startDate, { startDate = it }, endDate, { endDate = it }, intervalCount, { intervalCount = it }, cadence, null, null)
        MoneyField(budgetLimit, { budgetLimit = it }, "Total budget awal")
        Text("Tentukan batas total dahulu, lalu isi kategori. Sisa yang belum dibagi tetap di Main Vault dan batasnya tersimpan untuk koreksi berikutnya.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyField(plannedIncome, { plannedIncome = it }, "Proyeksi hasil RAB (opsional)")
        Text("Isi hanya jika RAB ini diharapkan menghasilkan pemasukan, misalnya kegiatan bisnis. Nilai ini bukan total budget dan tidak menambah saldo.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Total budget kategori: ${displayMoney(totalBudget, state.valuesVisible)}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
        if (totalBudget > limit && limit > 0) Text("Melebihi total awal sebesar ${displayMoney(totalBudget - limit, state.valuesVisible)}. Rangkuman akan ditampilkan sebelum menyimpan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Rollover sisa", style = MaterialTheme.typography.titleMedium)
                Text("Bawa sisa positif ke periode berikutnya", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(rollover, { rollover = it })
        }
        Text("KATEGORI BUDGET CUSTOM", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        Text("Buat kategori sendiri. Jumlah kategori boleh di bawah batas total.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    pendingIncrease?.let { request -> BudgetIncreaseDialog(request, state.valuesVisible) { pendingIncrease = null } }
}

@Composable
fun BudgetDetailDialog(
    state: KronUiState,
    periodId: Long,
    readOnly: Boolean = false,
    onDismiss: () -> Unit,
    onUpdatePeriodTotal: ((Long, Long) -> Unit)? = null,
    onCorrect: (Long, Long, String, Long?) -> Unit,
    onCorrectSplit: ((Long, Long, Int, String, Long?) -> Unit)? = null,
    onAddCategory: ((String, Long, Int, String, Long?) -> Unit)? = null,
    onRenameCategory: ((Long, String) -> Unit)? = null,
    onDeleteCategory: ((Long, String) -> Unit)? = null,
    onRestoreCategory: ((Long, Long, Int, String, Long?) -> Unit)? = null,
    onUpdateCategoryVisual: ((Long, String, Long?) -> Unit)? = null,
) {
    val rows = state.allocations.filter { it.periodId == periodId }
    val visibleRows = rows.filter { it.isActive }
    val periodUsed = rows.sumOf { it.plannedAmount }
    val periodTotal = state.periods.firstOrNull { it.id == periodId }?.plannedTotal ?: periodUsed
    val visibleCategoryIds = remember(visibleRows) { visibleRows.map { it.categoryId }.toSet() }
    val activeCategoryAllocations = remember(rows, visibleCategoryIds) {
        rows.filter { it.categoryId in visibleCategoryIds }
    }
    val categoryGroups = remember(activeCategoryAllocations) {
        activeCategoryAllocations.groupBy { it.categoryId }.values.toList()
    }
    val archivedExpenseCategories = state.archivedCategories.filter { it.direction == TransactionDirection.EXPENSE }
    var showArchivedSection by rememberSaveable { mutableStateOf(false) }
    var showEditPeriodTotal by rememberSaveable { mutableStateOf(false) }
    var editPeriodTotal by rememberSaveable { mutableStateOf("") }
    var restoreCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoreAmount by rememberSaveable { mutableStateOf("") }
    var restoreCashPct by rememberSaveable { mutableIntStateOf(50) }
    var restoreNote by rememberSaveable { mutableStateOf("Pulihkan kategori") }
    var correctionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var splitTotalAmount by rememberSaveable { mutableStateOf("") }
    var splitCashPct by rememberSaveable { mutableIntStateOf(50) }
    var splitReason by rememberSaveable { mutableStateOf("Koreksi budget") }
    var showAddCategory by rememberSaveable { mutableStateOf(false) }
    var addName by rememberSaveable { mutableStateOf("") }
    var addAmount by rememberSaveable { mutableStateOf("") }
    var addCashPct by rememberSaveable { mutableIntStateOf(50) }
    var addNote by rememberSaveable { mutableStateOf("Tambah kategori") }
    var pendingIncrease by remember { mutableStateOf<BudgetIncreaseRequest?>(null) }
    val validAdd = addName.isNotBlank() && money(addAmount) > 0 && addNote.isNotBlank() && !visibleRows.any { it.categoryName.equals(addName.trim(), ignoreCase = true) }
    var renameCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    var deleteCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var deleteNote by rememberSaveable { mutableStateOf("Hapus kategori") }
    var iconEditCategoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var selectedIconKey by rememberSaveable { mutableStateOf("") }
    val keypadHost = remember { CalculatorKeypadHostState() }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val isImeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && keypadHost.isVisible) {
            keypadHost.dismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (keypadHost.isVisible) {
                keypadHost.dismiss()
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalCalculatorKeypadHost provides keypadHost) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(if (readOnly) "Detail budget arsip" else "Detail budget", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Tutup") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
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
                val unallocated = (periodTotal - periodUsed).coerceAtLeast(0)
                HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Total budget periode saat ini", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(budgetMoney(periodTotal), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                            }
                            if (!readOnly && onUpdatePeriodTotal != null) {
                                OutlinedButton(
                                    onClick = {
                                        showEditPeriodTotal = !showEditPeriodTotal
                                        if (showEditPeriodTotal) {
                                            editPeriodTotal = if (periodTotal > 0L) periodTotal.toString() else ""
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                ) {
                                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (showEditPeriodTotal) "Tutup" else "Ubah total")
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Rencana kategori", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(budgetMoney(periodUsed), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Sisa belum dialokasikan", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(budgetMoney(unallocated), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = if (unallocated > 0) KronGold else MaterialTheme.colorScheme.onSurfaceVariant)
                        }

                        if (showEditPeriodTotal && !readOnly && onUpdatePeriodTotal != null) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                            Text("Atur alokasi total budget", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text(
                                "Tentukan batas total budget untuk periode ini. Sisa batas yang belum dibagi tetap berada di Main Vault dan tidak dibooking.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MoneyField(editPeriodTotal, { editPeriodTotal = it }, "Total alokasi budget")
                            val parsedNewTotal = money(editPeriodTotal)
                            val isBelowCategories = parsedNewTotal < periodUsed && editPeriodTotal.isNotBlank()
                            if (isBelowCategories) {
                                Text(
                                    "Total alokasi tidak boleh lebih kecil dari rencana kategori saat ini (${budgetMoney(periodUsed)}). Kurangi alokasi kategori terlebih dahulu jika ingin menurunkan total di bawah nilai tersebut.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                            if (unallocated > 0) {
                                OutlinedButton(
                                    onClick = { editPeriodTotal = periodUsed.toString() },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Sesuaikan ke total kategori (${budgetMoney(periodUsed)})", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Button(
                                onClick = {
                                    onUpdatePeriodTotal(periodId, parsedNewTotal)
                                    showEditPeriodTotal = false
                                },
                                enabled = parsedNewTotal >= periodUsed && parsedNewTotal > 0 && parsedNewTotal != periodTotal,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Simpan total budget")
                            }
                        }
                    }
                }
                if (!readOnly && onAddCategory != null) {
                    OutlinedButton(
                        onClick = { showAddCategory = !showAddCategory; if (!showAddCategory) { addName = ""; addAmount = ""; addCashPct = 50; addNote = "Tambah kategori" } },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Text(if (showAddCategory) " Batal tambah kategori" else " Tambah kategori")
                    }
                    if (showAddCategory) {
                        HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)) {
                            Text("Kategori baru", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                            OutlinedTextField(addName, { addName = it }, label = { Text("Nama kategori") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            if (visibleRows.any { it.categoryName.equals(addName.trim(), ignoreCase = true) }) Text("Nama sudah ada di periode ini", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            MoneyField(addAmount, { addAmount = it }, "Nominal budget")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Cash $addCashPct%", style = MaterialTheme.typography.labelSmall)
                                Text("eBudget ${100 - addCashPct}%", style = MaterialTheme.typography.labelSmall)
                            }
                            Slider(value = addCashPct.toFloat(), onValueChange = { addCashPct = it.toInt() }, valueRange = 0f..100f, steps = 19)
                            OutlinedTextField(addNote, { addNote = it }, label = { Text("Alasan") }, modifier = Modifier.fillMaxWidth())
                            Text("Dana akan dibooking dari Main Vault. Pastikan saldo cukup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(
                                onClick = {
                                    val categoryName = addName.trim()
                                    val categoryAmount = money(addAmount)
                                    val cashAmount = categoryAmount * addCashPct / 100
                                    val cashPct = addCashPct
                                    val note = addNote.trim()
                                    val proposedUsed = periodUsed + categoryAmount
                                    val saveCategory: (Long?) -> Unit = { approvedTotal ->
                                        onAddCategory(categoryName, categoryAmount, cashPct, note, approvedTotal)
                                        showAddCategory = false; addName = ""; addAmount = ""; addCashPct = 50; addNote = "Tambah kategori"
                                    }
                                    if (proposedUsed > periodTotal) {
                                        pendingIncrease = BudgetIncreaseRequest(
                                            previousTotal = periodTotal,
                                            proposedTotal = proposedUsed,
                                            categories = periodBudgetIncreaseCategories(rows, null, categoryName, cashAmount, categoryAmount - cashAmount),
                                            onConfirm = { pendingIncrease = null; saveCategory(proposedUsed) },
                                        )
                                    } else saveCategory(null)
                                },
                                enabled = validAdd,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("Simpan kategori") }
                        }
                    }
                }
                if (categoryGroups.isEmpty() && !showAddCategory) {
                    HudCard { Text("Belum ada kategori. Tambahkan kategori baru atau pulihkan.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                categoryGroups.forEach { catRows ->
                    val firstRow = catRows.first()
                    val categoryId = firstRow.categoryId
                    val categoryName = firstRow.categoryName
                    val cAlloc = catRows.firstOrNull { it.fundingChannel == FundingChannel.CASH }
                    val ebAlloc = catRows.firstOrNull { it.fundingChannel == FundingChannel.EBUDGET }
                    val isPeriodActiveOrClosed = firstRow.periodStatus != PeriodStatus.DRAFT
                    fun allocEffectivePlanned(alloc: AllocationBalanceRow?): Long {
                        if (alloc == null) return 0L
                        return alloc.plannedAmount
                    }
                    val totalPlanned = catRows.sumOf { allocEffectivePlanned(it) }
                    val totalSpent = catRows.sumOf { it.spentAmount }
                    val totalAvailable = catRows.sumOf { it.availableAmount }
                    val isCategoryUnfunded = firstRow.periodStatus != PeriodStatus.DRAFT &&
                        catRows.all { it.bookedAmount == 0L && it.availableAmount == 0L && it.spentAmount == 0L } &&
                        totalPlanned > 0L
                    val hasCash = cAlloc != null && (allocEffectivePlanned(cAlloc) > 0L || cAlloc.availableAmount > 0L || cAlloc.spentAmount > 0L)
                    val hasEBudget = ebAlloc != null && (allocEffectivePlanned(ebAlloc) > 0L || ebAlloc.availableAmount > 0L || ebAlloc.spentAmount > 0L)
                    val isSplit = hasCash && hasEBudget

                    HudCard {
                        Column(Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    if (hasCash) ChannelBadge(FundingChannel.CASH)
                                    if (hasEBudget) ChannelBadge(FundingChannel.EBUDGET)
                                    if (!hasCash && !hasEBudget) ChannelBadge(firstRow.fundingChannel)
                                }
                                if (isCategoryUnfunded) {
                                    Surface(
                                        color = KronGold.copy(alpha = 0.14f),
                                        shape = KronChipShape,
                                        border = BorderStroke(0.6.dp, KronGold.copy(alpha = 0.4f)),
                                    ) {
                                        Text(
                                            text = "Belum didanai",
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            color = KronGold,
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(categoryName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Rencana", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(budgetMoney(totalPlanned), style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Terpakai", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(budgetMoney(totalSpent), style = MaterialTheme.typography.bodyMedium)
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Sisa", style = MaterialTheme.typography.bodyMedium)
                                val remaining = if (firstRow.periodStatus == PeriodStatus.CLOSED) totalPlanned - totalSpent else totalAvailable
                                Text(
                                    budgetMoney(remaining),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (remaining < 0) MaterialTheme.colorScheme.error
                                    else if (remaining > 0) KronGold
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!readOnly) {
                                    TextButton(onClick = {
                                        if (correctionId == categoryId) {
                                            correctionId = null
                                            splitTotalAmount = ""
                                        } else {
                                            correctionId = categoryId
                                            val curCash = allocEffectivePlanned(cAlloc)
                                            val curEBudget = allocEffectivePlanned(ebAlloc)
                                            val curTotal = curCash + curEBudget
                                            splitTotalAmount = if (curTotal > 0L) curTotal.toString() else if (totalPlanned > 0L) totalPlanned.toString() else ""
                                            splitCashPct = if (curTotal > 0L) {
                                                ((curCash * 100) / curTotal).toInt()
                                            } else if (hasCash && !hasEBudget) {
                                                100
                                            } else if (!hasCash && hasEBudget) {
                                                0
                                            } else {
                                                50
                                            }
                                            splitReason = "Koreksi budget $categoryName"
                                            renameCategoryId = null
                                            deleteCategoryId = null
                                        }
                                    }) { Text("Koreksi") }
                                }
                            }
                            if (isSplit) {
                                Spacer(Modifier.height(4.dp))
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val cRem = if (firstRow.periodStatus == PeriodStatus.CLOSED) cAlloc.plannedAmount - cAlloc.spentAmount else cAlloc.availableAmount
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("Cash", style = MaterialTheme.typography.labelSmall, color = KronGold, fontWeight = FontWeight.SemiBold)
                                            Text("Rencana: ${budgetMoney(cAlloc.plannedAmount)} • Sisa: ${budgetMoney(cRem)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        val ebRem = if (firstRow.periodStatus == PeriodStatus.CLOSED) ebAlloc.plannedAmount - ebAlloc.spentAmount else ebAlloc.availableAmount
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("eBudget", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary, fontWeight = FontWeight.SemiBold)
                                            Text("Rencana: ${budgetMoney(ebAlloc.plannedAmount)} • Sisa: ${budgetMoney(ebRem)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
                            if (!readOnly && (onRenameCategory != null || onDeleteCategory != null || onUpdateCategoryVisual != null)) {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    if (onRenameCategory != null) TextButton(onClick = { renameCategoryId = categoryId; renameValue = categoryName; deleteCategoryId = null; correctionId = null; iconEditCategoryId = null }) { Text("Ganti nama") }
                                    if (onUpdateCategoryVisual != null) TextButton(onClick = { iconEditCategoryId = categoryId; selectedIconKey = state.categories.firstOrNull { it.id == categoryId }?.icon ?: "category"; renameCategoryId = null; deleteCategoryId = null; correctionId = null }) { Text("Ubah icon") }
                                    if (onDeleteCategory != null) TextButton(onClick = { deleteCategoryId = categoryId; deleteNote = "Arsipkan kategori $categoryName"; renameCategoryId = null; correctionId = null; iconEditCategoryId = null }) { Text("Arsipkan", color = MaterialTheme.colorScheme.error) }
                                }
                            }
                            if (!readOnly && iconEditCategoryId == categoryId && onUpdateCategoryVisual != null) {
                                Spacer(Modifier.height(8.dp))
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = KronSubCardShape,
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text("Pilih icon untuk $categoryName", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                                        CategoryIconPicker(selectedKey = selectedIconKey, onSelectKey = { selectedIconKey = it })
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Button(
                                                onClick = {
                                                    onUpdateCategoryVisual(categoryId, selectedIconKey, null)
                                                    iconEditCategoryId = null
                                                },
                                                shape = KronButtonShape,
                                            ) {
                                                Text("Simpan icon")
                                            }
                                            TextButton(onClick = { iconEditCategoryId = null }) { Text("Batal") }
                                        }
                                    }
                                }
                            }
                        }
                        if (categoryId == correctionId) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(10.dp))

                            val oldCashPlanned = allocEffectivePlanned(cAlloc)
                            val oldEBudgetPlanned = allocEffectivePlanned(ebAlloc)
                            val oldTotalPlanned = oldCashPlanned + oldEBudgetPlanned
                            val cashSpent = cAlloc?.spentAmount ?: 0L
                            val eBudgetSpent = ebAlloc?.spentAmount ?: 0L

                            val targetTotal = money(splitTotalAmount)
                            val targetCash = targetTotal * splitCashPct / 100
                            val targetEBudget = targetTotal - targetCash

                            val currentCashAvailable = cAlloc?.availableAmount ?: 0L
                            val currentEBudgetAvailable = ebAlloc?.availableAmount ?: 0L
                            val currentCashBooked = currentCashAvailable + cashSpent
                            val currentEBudgetBooked = currentEBudgetAvailable + eBudgetSpent

                            val deltaCash = targetCash - currentCashBooked
                            val deltaEBudget = targetEBudget - currentEBudgetBooked

                            val exceedsCashSpent = targetCash >= cashSpent
                            val exceedsEBudgetSpent = targetEBudget >= eBudgetSpent
                            val enoughVaultCash = deltaCash <= 0 || deltaCash <= state.vaultCash
                            val enoughVaultEBudget = deltaEBudget <= 0 || deltaEBudget <= state.vaultEBudget
                            val hasChanges = (targetCash != oldCashPlanned || targetEBudget != oldEBudgetPlanned || deltaCash != 0L || deltaEBudget != 0L) && splitTotalAmount.isNotBlank()
                            val splitCorrectionValid = exceedsCashSpent && exceedsEBudgetSpent && enoughVaultCash && enoughVaultEBudget && hasChanges && splitReason.isNotBlank()

                            Text(
                                "Koreksi Budget & Split • $categoryName",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Total saat ini: ${budgetMoney(oldTotalPlanned)} (Cash: ${budgetMoney(oldCashPlanned)}, eBudget: ${budgetMoney(oldEBudgetPlanned)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text("Total budget periode sebelum koreksi: ${budgetMoney(periodTotal)}", style = MaterialTheme.typography.bodySmall)
                            val proposedUsed = periodUsed - catRows.sumOf { it.plannedAmount } + targetTotal
                            Text("Rencana kategori setelah koreksi: ${budgetMoney(proposedUsed)}", style = MaterialTheme.typography.bodySmall, color = if (proposedUsed > periodTotal) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
                            MoneyField(splitTotalAmount, { splitTotalAmount = it }, "Total nominal budget")
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Cash $splitCashPct% (${budgetMoney(targetCash)})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = KronGold)
                                Text("eBudget ${100 - splitCashPct}% (${budgetMoney(targetEBudget)})", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Slider(
                                value = splitCashPct.toFloat(),
                                onValueChange = { splitCashPct = it.toInt() },
                                valueRange = 0f..100f,
                                steps = 19,
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(
                                    onClick = { splitCashPct = 100 },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                ) {
                                    Text("100% Cash", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { splitCashPct = 50 },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                ) {
                                    Text("50 : 50", style = MaterialTheme.typography.labelSmall)
                                }
                                OutlinedButton(
                                    onClick = { splitCashPct = 0 },
                                    modifier = Modifier.weight(1f),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
                                ) {
                                    Text("100% eBudget", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            if (!exceedsCashSpent && splitTotalAmount.isNotBlank()) {
                                Text(
                                    "Pagu Cash (${budgetMoney(targetCash)}) tidak boleh lebih kecil dari dana Cash terpakai (${budgetMoney(cashSpent)})",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (!exceedsEBudgetSpent && splitTotalAmount.isNotBlank()) {
                                Text(
                                    "Pagu eBudget (${budgetMoney(targetEBudget)}) tidak boleh lebih kecil dari dana eBudget terpakai (${budgetMoney(eBudgetSpent)})",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (deltaCash > 0 && !enoughVaultCash) {
                                Text(
                                    "Main Vault Cash tidak cukup (tersedia ${budgetMoney(state.vaultCash)}, butuh +${budgetMoney(deltaCash)})",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (deltaCash > 0) {
                                Text(
                                    "Cash: Menambah +${budgetMoney(deltaCash)} dari Main Vault Cash",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (deltaCash < 0) {
                                Text(
                                    "Cash: Mengembalikan ${budgetMoney(-deltaCash)} ke Main Vault Cash",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (deltaEBudget > 0 && !enoughVaultEBudget) {
                                Text(
                                    "Main Vault eBudget tidak cukup (tersedia ${budgetMoney(state.vaultEBudget)}, butuh +${budgetMoney(deltaEBudget)})",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (deltaEBudget > 0) {
                                Text(
                                    "eBudget: Menambah +${budgetMoney(deltaEBudget)} dari Main Vault eBudget",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (deltaEBudget < 0) {
                                Text(
                                    "eBudget: Mengembalikan ${budgetMoney(-deltaEBudget)} ke Main Vault eBudget",
                                    color = MaterialTheme.colorScheme.tertiary,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            OutlinedTextField(
                                value = splitReason,
                                onValueChange = { splitReason = it },
                                label = { Text("Alasan koreksi") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )
                            Text(
                                "Jurnal lama tetap tersimpan. Alokasi Cash & eBudget disesuaikan secara atomik.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = {
                                        correctionId = null
                                        splitTotalAmount = ""
                                        splitReason = "Koreksi budget $categoryName"
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Batal")
                                }
                                Button(
                                    onClick = {
                                        keypadHost.dismiss()
                                        val cashPct = splitCashPct
                                        val reason = splitReason.trim()
                                        val saveCorrection: (Long?) -> Unit = { approvedTotal ->
                                            if (onCorrectSplit != null) {
                                                onCorrectSplit(categoryId, targetTotal, cashPct, reason, approvedTotal)
                                            } else if (cAlloc != null) {
                                                onCorrect(cAlloc.id, targetTotal, reason, approvedTotal)
                                            } else if (ebAlloc != null) {
                                                onCorrect(ebAlloc.id, targetTotal, reason, approvedTotal)
                                            }
                                            correctionId = null
                                            splitTotalAmount = ""
                                            splitReason = "Koreksi budget $categoryName"
                                        }
                                        if (proposedUsed > periodTotal) {
                                            pendingIncrease = BudgetIncreaseRequest(
                                                previousTotal = periodTotal,
                                                proposedTotal = proposedUsed,
                                                categories = periodBudgetIncreaseCategories(rows, categoryId, categoryName, targetCash, targetEBudget),
                                                onConfirm = { pendingIncrease = null; saveCorrection(proposedUsed) },
                                            )
                                        } else {
                                            saveCorrection(null)
                                        }
                                    },
                                    enabled = splitCorrectionValid,
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("Simpan koreksi")
                                }
                            }
                        }
                        if (!readOnly && renameCategoryId == categoryId && onRenameCategory != null) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(10.dp))
                            Text("Ganti nama $categoryName", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                            OutlinedTextField(renameValue, { renameValue = it }, label = { Text("Nama baru") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Text("Nama baru hanya berlaku mulai periode ini dan selanjutnya. Riwayat sebelumnya tetap memakai nama lama.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { renameCategoryId = null; renameValue = "" }) { Text("Batal") }
                                Button(
                                    onClick = { onRenameCategory(categoryId, renameValue.trim()); renameCategoryId = null; renameValue = "" },
                                    enabled = renameValue.trim().isNotBlank() && !renameValue.trim().equals(categoryName, ignoreCase = true),
                                ) { Text("Simpan nama") }
                            }
                        }
                        if (!readOnly && deleteCategoryId == categoryId && onDeleteCategory != null) {
                            Spacer(Modifier.height(10.dp))
                            HorizontalDivider()
                            Spacer(Modifier.height(10.dp))
                            Text("Arsipkan $categoryName?", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
                            Text("Sisa akan dikembalikan ke Main Vault. Kategori yang diarsipkan dapat dipulihkan kembali kapan saja.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            OutlinedTextField(deleteNote, { deleteNote = it }, label = { Text("Alasan arsip") }, modifier = Modifier.fillMaxWidth())
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { deleteCategoryId = null; deleteNote = "Arsipkan kategori" }) { Text("Batal") }
                                Button(
                                    onClick = { onDeleteCategory(categoryId, deleteNote.trim()); deleteCategoryId = null; deleteNote = "Arsipkan kategori" },
                                    enabled = deleteNote.trim().isNotBlank(),
                                ) { Text("Arsipkan kategori") }
                            }
                        }
                    }
                }
                if (!readOnly && onRestoreCategory != null && archivedExpenseCategories.isNotEmpty()) {
                    HudCard(accent = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.55f)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showArchivedSection = !showArchivedSection }.padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Archive, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                                Text("Kategori terarsip (${archivedExpenseCategories.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Icon(if (showArchivedSection) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
                        }
                        if (showArchivedSection) {
                            Spacer(Modifier.height(8.dp))
                            archivedExpenseCategories.forEach { cat ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(cat.name, style = MaterialTheme.typography.bodyLarge)
                                        OutlinedButton(
                                            onClick = {
                                                restoreCategoryId = if (restoreCategoryId == cat.id) null else cat.id
                                                restoreAmount = ""
                                                restoreCashPct = 50
                                                restoreNote = "Pulihkan kategori ${cat.name}"
                                            },
                                        ) {
                                            Icon(Icons.Outlined.Restore, contentDescription = null)
                                            Text(if (restoreCategoryId == cat.id) " Batal" else " Pulihkan")
                                        }
                                    }
                                    if (restoreCategoryId == cat.id) {
                                        Spacer(Modifier.height(8.dp))
                                        HudCard(accent = MaterialTheme.colorScheme.tertiary) {
                                            Text("Pulihkan ${cat.name}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                                            MoneyField(restoreAmount, { restoreAmount = it }, "Nominal budget baru")
                                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                                Text("Cash $restoreCashPct%", style = MaterialTheme.typography.labelSmall)
                                                Text("eBudget ${100 - restoreCashPct}%", style = MaterialTheme.typography.labelSmall)
                                            }
                                            Slider(value = restoreCashPct.toFloat(), onValueChange = { restoreCashPct = it.toInt() }, valueRange = 0f..100f, steps = 19)
                                            OutlinedTextField(restoreNote, { restoreNote = it }, label = { Text("Alasan") }, modifier = Modifier.fillMaxWidth())
                                            Text("Dana akan dibooking dari Main Vault. Pastikan saldo cukup.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Button(
                                                onClick = {
                                                    val amount = money(restoreAmount)
                                                    val cash = amount * restoreCashPct / 100
                                                    val cashPct = restoreCashPct
                                                    val note = restoreNote.trim()
                                                    val proposedUsed = periodUsed + amount
                                                    val saveRestore: (Long?) -> Unit = { approvedTotal ->
                                                        onRestoreCategory(cat.id, amount, cashPct, note, approvedTotal)
                                                        restoreCategoryId = null
                                                        restoreAmount = ""
                                                        restoreCashPct = 50
                                                        restoreNote = "Pulihkan kategori"
                                                    }
                                                    if (proposedUsed > periodTotal) {
                                                        pendingIncrease = BudgetIncreaseRequest(
                                                            previousTotal = periodTotal,
                                                            proposedTotal = proposedUsed,
                                                            categories = periodBudgetIncreaseCategories(rows, cat.id, cat.name, cash, amount - cash),
                                                            onConfirm = { pendingIncrease = null; saveRestore(proposedUsed) },
                                                        )
                                                    } else saveRestore(null)
                                                },
                                                enabled = money(restoreAmount) > 0 && restoreNote.isNotBlank(),
                                                modifier = Modifier.fillMaxWidth(),
                                            ) { Text("Simpan & pulihkan") }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                }
                    // Floating Keypad docked at bottom like system keyboard
                    AnimatedVisibility(
                        visible = keypadHost.isVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            shadowElevation = 16.dp,
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                CalculatorKeypadView(host = keypadHost)
                            }
                        }
                    }
                }
            }
        }
    }
    pendingIncrease?.let { request -> BudgetIncreaseDialog(request, state.valuesVisible) { pendingIncrease = null } }
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
            val isClosed = period.status == PeriodStatus.CLOSED
            fun effPlanned(r: AllocationBalanceRow): Long =
                if (r.periodStatus != PeriodStatus.DRAFT && r.bookedAmount == 0L && r.availableAmount == 0L && r.spentAmount == 0L) 0L else r.plannedAmount
            val totalPlanned = rows.sumOf { effPlanned(it) }
            val totalSpent = rows.sumOf { it.spentAmount }
            val totalRemaining = rows.sumOf { if (isClosed) effPlanned(it) - it.spentAmount else it.availableAmount }
            val cashRemaining = rows.filter { it.fundingChannel == FundingChannel.CASH }.sumOf { if (isClosed) effPlanned(it) - it.spentAmount else it.availableAmount }
            val eBudgetRemaining = rows.filter { it.fundingChannel == FundingChannel.EBUDGET }.sumOf { if (isClosed) effPlanned(it) - it.spentAmount else it.availableAmount }
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
    val withinBudgetColor = KronGreen
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
                    val spentColor = if (item.spent <= item.planned) withinBudgetColor else KronRed
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
    // An overdrawn Vault is not a minus category, so nothing here can resolve it. Saying "semua
    // budget sehat" while the Vault is negative would contradict the warning that sent the user
    // here, so the state is named and the actual remedy is spelled out instead.
    val vaultDeficit = state.vaultDeficit
    var mode by rememberSaveable { mutableStateOf(if (targets.isNotEmpty()) "MINUS" else "UNALLOCATED") }
    var targetId by rememberSaveable { mutableStateOf(targets.firstOrNull()?.id) }
    var sourceId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sourceMode by rememberSaveable { mutableStateOf("VAULT") }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("Resolusi budget minus") }
    val target = targets.firstOrNull { it.id == targetId }
    val sources = target?.let { selected -> state.allocations.filter { it.fundingChannel == selected.fundingChannel && it.availableAmount > 0 && it.id != targetId && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") && it.isActive } }.orEmpty()
    val vault = if (target?.fundingChannel == FundingChannel.CASH) state.vaultCash else state.vaultEBudget
    val rollover = if (target?.fundingChannel == FundingChannel.CASH) state.rolloverCash else state.rolloverEBudget
    val sourceAvailable = when (sourceMode) {
        "ROLLOVER" -> rollover
        "ALLOCATION" -> sources.firstOrNull { it.id == sourceId }?.availableAmount ?: 0
        else -> vault
    }
    val requested = money(amount)
    val limit = minOf(sourceAvailable, -(target?.availableAmount ?: 0))
    var unallocatedChannel by rememberSaveable { mutableStateOf(if (state.unallocatedCash < 0) FundingChannel.CASH else FundingChannel.EBUDGET) }
    val destinations = state.allocations.filter { it.fundingChannel == unallocatedChannel && it.periodStatus in setOf("ACTIVE", "RESOLUTION_REQUIRED") && it.isActive }
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
        if (vaultDeficit < 0) {
            HudCard(accent = MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) {
                Text(
                    "Main Vault minus ${displayMoney(vaultDeficit, state.valuesVisible)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Pengeluaran tak terduga memakai dana yang sudah dibooking ke kategori budget. " +
                        "Kurangi alokasi kategori lewat Koreksi pada halaman Budget agar dana kembali ke Main Vault, " +
                        "atau catat pemasukan baru.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (targets.isEmpty() && !hasUnallocated) {
            Text(
                if (vaultDeficit < 0) {
                    "Tidak ada kategori minus, tetapi Main Vault masih perlu dipulihkan."
                } else {
                    "Tidak ada kategori minus. Semua budget sehat."
                },
            )
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
fun ChannelTransferDialog(state: KronUiState, onDismiss: () -> Unit, onSubmit: (Long, Long, Long, String) -> Unit) {
    val sources = state.allocations.filter { it.availableAmount > 0 && it.isActive }
    var allocationId by rememberSaveable { mutableStateOf(sources.firstOrNull()?.id) }
    val source = sources.firstOrNull { it.id == allocationId }
    val account = state.activeAccount
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    val accountRow = state.accountBalances.firstOrNull { it.id == account?.id }
    val accountBalance = if (source?.fundingChannel == FundingChannel.CASH) accountRow?.cashBalance ?: 0L else accountRow?.eBudgetBalance ?: 0L
    val limit = minOf(source?.availableAmount ?: 0, accountBalance)
    FormDialog("Pindahkan Cash dan eBudget", onDismiss, confirmEnabled = source != null && account != null && money(amount) in 1..limit, onConfirm = {
        onSubmit(requireNotNull(allocationId), requireNotNull(account).id, money(amount), note)
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
    var name by rememberSaveable { mutableStateOf("") }
    var openingCash by rememberSaveable { mutableStateOf("") }
    var openingEBudget by rememberSaveable { mutableStateOf("") }
    FormDialog("Tambah akun", onDismiss, confirmEnabled = name.isNotBlank(), onConfirm = { onSubmit(name, money(openingCash), money(openingEBudget)) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama akun") }, modifier = Modifier.fillMaxWidth())
        Text("Setiap akun otomatis memiliki kanal Cash dan eBudget.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MoneyField(openingCash, { openingCash = it }, "Saldo awal Cash")
        MoneyField(openingEBudget, { openingEBudget = it }, "Saldo awal eBudget")
    }
}

@Composable
fun EditAccountDialog(account: AccountEntity, onDismiss: () -> Unit, onSubmit: (String) -> Unit) {
    var name by rememberSaveable(account.id) { mutableStateOf(account.name) }
    FormDialog("Edit akun", onDismiss, confirmEnabled = name.isNotBlank(), onConfirm = { onSubmit(name.trim()) }) {
        OutlinedTextField(name, { name = it }, label = { Text("Nama akun") }, modifier = Modifier.fillMaxWidth())
        Text("Kanal Cash dan eBudget selalu tersedia dan tidak dapat dihapus.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AuditDialog(
    event: ActivityRow,
    state: KronUiState,
    auditDetails: List<AuditSnapshotEntity> = emptyList(),
    onDismiss: () -> Unit,
    onGalleryPick: (String) -> Unit,
    onCameraCapture: (String) -> Unit,
    onExportEvidence: (String) -> Unit,
    onRevert: (String, String) -> Unit,
    onCorrect: (String, String, String, String) -> Unit,
    onRestoreReversal: ((String) -> Unit)? = null,
    onReceiptPreview: suspend (ReceiptEntity) -> Bitmap? = { null },
    onOpenReceipt: (ReceiptEntity) -> Unit = {},
    reversalRestored: Boolean = false,
    readOnly: Boolean = false,
) {
    var reason by rememberSaveable(event.id) { mutableStateOf("") }
    var correctionMode by rememberSaveable(event.id) { mutableStateOf(false) }
    var correctedTitle by rememberSaveable(event.id) { mutableStateOf(event.title) }
    var correctedNote by rememberSaveable(event.id) { mutableStateOf(event.note) }
    val lifecycleEvent = event.type in setOf(
        "ARCHIVE",
        "RESTORE",
        "DEBT_OPEN",
        "DEBT_ARCHIVE",
        "PORTFOLIO_BOOKING",
        "RELEASE",
        "ROLLOVER",
        "SYSTEM",
        "EVIDENCE_KEY_ROTATION",
        "ATTACH_EVIDENCE",
        "CORRECTION",
    )
    val isAttachEvidence = event.type == "ATTACH_EVIDENCE"
    val parentEvent = if (isAttachEvidence && event.relatedEventId != null) {
        state.activities.firstOrNull { it.id == event.relatedEventId }
    } else null
    val receipts = if (isAttachEvidence && parentEvent != null) {
        state.receipts.filter { it.eventId == parentEvent.id }
    } else {
        state.receipts.filter { it.eventId == event.id }
    }
    val actionEnabled = !readOnly && !lifecycleEvent && event.reversedByEventId == null && event.type != "REVERSAL" &&
        reason.isNotBlank() && (!correctionMode || correctedTitle.isNotBlank())
    val resolutionDetail = auditDetails.asSequence()
        .mapNotNull { ResolutionAudit.parse(it.beforeJson, it.afterJson) }
        .firstOrNull()

    val category = remember(state.categories, event.title) {
        state.categories.firstOrNull { it.name == event.title }
    }
    val eventChannels = state.eventChannels[event.id].orEmpty()

    FormDialog(
        "Detail audit",
        onDismiss,
        confirmText = if (correctionMode) "Simpan koreksi" else "Batalkan dengan reversal",
        confirmEnabled = actionEnabled,
        onConfirm = {
            if (correctionMode) onCorrect(event.id, correctedTitle, correctedNote, reason)
            else onRevert(event.id, reason)
        },
    ) {
        AuditHeroCard(
            event = event,
            category = category,
            eventChannels = eventChannels,
            valuesVisible = state.valuesVisible,
        )

        if (isAttachEvidence && parentEvent != null) {
            AuditParentEventCard(
                parentEvent = parentEvent,
                valuesVisible = state.valuesVisible,
            )
        }

        resolutionDetail?.let { detail ->
            AuditResolutionCard(
                detail = detail,
                valuesVisible = state.valuesVisible,
            )
        }

        AuditFinancialImpactCard(
            event = event,
            resolutionDetail = resolutionDetail,
            valuesVisible = state.valuesVisible,
        )

        AuditMetadataCard(
            event = event,
        )

        AuditEvidenceCard(
            eventId = event.id,
            eventType = event.type,
            receipts = receipts,
            readOnly = readOnly,
            onCameraCapture = onCameraCapture,
            onGalleryPick = onGalleryPick,
            onExportEvidence = onExportEvidence,
            onReceiptPreview = onReceiptPreview,
            onOpenReceipt = onOpenReceipt,
        )

        AuditActionCard(
            event = event,
            readOnly = readOnly,
            lifecycleEvent = lifecycleEvent,
            reversalRestored = reversalRestored,
            onRestoreReversal = onRestoreReversal,
            correctionMode = correctionMode,
            onCorrectionModeChange = { correctionMode = it },
            correctedTitle = correctedTitle,
            onCorrectedTitleChange = { correctedTitle = it },
            correctedNote = correctedNote,
            onCorrectedNoteChange = { correctedNote = it },
            reason = reason,
            onReasonChange = { reason = it },
        )
    }
}

@Composable
private fun AuditHeroCard(
    event: ActivityRow,
    category: CategoryEntity?,
    eventChannels: Set<String>,
    valuesVisible: Boolean,
) {
    val impact = event.primaryImpact()
    val money = displayMoney(impact, valuesVisible)
    val reversed = event.reversedByEventId != null
    val isIntegrityOk = event.auditStatus != "Integrity problem"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryAvatar(
                    categoryName = category?.name ?: event.title,
                    iconKey = category?.icon,
                    colorLong = category?.color,
                    type = event.type,
                    size = 46.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = event.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            textDecoration = if (reversed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        ),
                        color = if (reversed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (category != null && category.name != event.title) "${category.name} · ${eventTypeLabel(event.type)}" else eventTypeLabel(event.type),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        ActivityStatusTag(event)
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = KronFieldShape,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "DAMPAK UTAMA",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (reversed) "Dibatalkan" else eventTypeLabel(event.type),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        )
                    }
                    Text(
                        text = money,
                        color = if (reversed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else signedColor(impact),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (eventChannels.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        eventChannels.sorted().forEach { ChannelBadge(it) }
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }

                val integrityTint = if (isIntegrityOk) KronGreen else MaterialTheme.colorScheme.error
                Surface(
                    color = integrityTint.copy(alpha = 0.12f),
                    shape = KronChipShape,
                    border = BorderStroke(0.6.dp, integrityTint.copy(alpha = 0.35f)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = if (isIntegrityOk) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                            contentDescription = null,
                            tint = integrityTint,
                            modifier = Modifier.size(13.dp),
                        )
                        Text(
                            text = if (isIntegrityOk) "Audit Valid" else event.auditStatus,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = integrityTint,
                        )
                    }
                }
            }

            if (event.note.isNotBlank()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = KronFieldShape,
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Edit,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp).padding(top = 2.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = "Catatan",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = event.note,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditParentEventCard(
    parentEvent: ActivityRow,
    valuesVisible: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "TRANSAKSI INDUK",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 0.8.sp,
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = KronFieldShape,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(parentEvent.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${eventTypeLabel(parentEvent.type)} · ${LocalDate.ofEpochDay(parentEvent.effectiveEpochDay).format(dialogDateFormat)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (parentEvent.cashImpact != 0L) {
                        Text(
                            displayMoney(parentEvent.cashImpact, valuesVisible),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = signedColor(parentEvent.cashImpact),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditResolutionCard(
    detail: ResolutionAuditDetail,
    valuesVisible: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "RINGKASAN RESOLUSI DANA",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    letterSpacing = 0.8.sp,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "${displayMoney(detail.amount, valuesVisible)} · ${detail.channel}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                    Text(
                        text = "Akun: ${detail.account}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = "Alur Mutasi Dana",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )

            AuditFlowNode("Sumber", detail.source.label, detail.source.detail, detail.source.before, detail.source.after, valuesVisible)

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                    modifier = Modifier.size(28.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "↓",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }

            AuditFlowNode("Tujuan", detail.target.label, detail.target.detail, detail.target.before, detail.target.after, valuesVisible)
        }
    }
}

@Composable
private fun AuditFinancialImpactCard(
    event: ActivityRow,
    resolutionDetail: ResolutionAuditDetail?,
    valuesVisible: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "DAMPAK BUKU BESAR & MUTASI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    letterSpacing = 0.8.sp,
                )
                Text(
                    text = "Distribusi dampak saldo per akun dan pos anggaran.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                shape = KronFieldShape,
                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                    AuditImpactRow("Dampak Akun Kas/Bank", event.cashImpact, valuesVisible)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    AuditImpactRow("Dampak Main Vault", event.vaultImpact, valuesVisible)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                    if (resolutionDetail == null) {
                        AuditImpactRow("Dampak Pos Anggaran", event.budgetImpact, valuesVisible)
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "Dampak Pos Anggaran",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Lihat alur resolusi di atas",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                    if (event.rolloverImpact != 0L) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                        AuditImpactRow("Dampak Rollover", event.rolloverImpact, valuesVisible)
                    }
                    if (event.unallocatedImpact != 0L) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f))
                        AuditImpactRow("Belum Dialokasikan", event.unallocatedImpact, valuesVisible)
                    }
                }
            }

            val isBalanced = event.ledgerDebit == event.ledgerCredit
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.6f),
                shape = KronFieldShape,
                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "PEMBUKUAN BERPASANGAN",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Surface(
                            color = if (isBalanced) KronGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.error.copy(alpha = 0.14f),
                            shape = KronChipShape,
                            border = BorderStroke(0.6.dp, if (isBalanced) KronGreen.copy(alpha = 0.4f) else MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = if (isBalanced) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                                    contentDescription = null,
                                    tint = if (isBalanced) KronGreen else MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(12.dp),
                                )
                                Text(
                                    text = if (isBalanced) "Seimbang" else "Tidak Seimbang",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isBalanced) KronGreen else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                text = "Total Debit",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = displayMoney(event.ledgerDebit, valuesVisible),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "Total Kredit",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = displayMoney(event.ledgerCredit, valuesVisible),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AuditImpactRow(
    label: String,
    amount: Long,
    valuesVisible: Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (amount != 0L) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = displayMoney(amount, valuesVisible),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (amount != 0L) FontWeight.SemiBold else FontWeight.Normal,
            color = if (amount != 0L) signedColor(amount) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
    }
}

@Composable
private fun AuditMetadataCard(
    event: ActivityRow,
) {
    val createdAtTime = java.time.Instant.ofEpochMilli(event.createdAt)
        .atZone(java.time.ZoneId.systemDefault())
        .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss", java.util.Locale.forLanguageTag("id-ID")))
    val effectiveDateStr = LocalDate.ofEpochDay(event.effectiveEpochDay).format(dialogDateFormat)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "JEJAK AUDIT & METADATA",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 0.8.sp,
            )

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuditMetaTile(
                    label = "Tanggal & Waktu",
                    value = "$effectiveDateStr, $createdAtTime",
                    icon = Icons.Outlined.CalendarMonth,
                    modifier = Modifier.weight(1f),
                )
                AuditMetaTile(
                    label = "Pelaku (Actor)",
                    value = event.actor.ifBlank { "User" },
                    icon = Icons.Outlined.Person,
                    modifier = Modifier.weight(1f),
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AuditMetaTile(
                    label = "Sumber Transaksi",
                    value = event.source,
                    icon = Icons.Outlined.Security,
                    modifier = Modifier.weight(1f),
                )
                AuditMetaTile(
                    label = "Perangkat",
                    value = event.deviceId.take(16).ifBlank { "-" },
                    icon = Icons.Outlined.Devices,
                    modifier = Modifier.weight(1f),
                    isMonospace = true,
                )
            }
        }
    }
}

@Composable
private fun AuditMetaTile(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = false,
) {
    Surface(
        modifier = modifier,
        shape = KronFieldShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
                    style = if (isMonospace) MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                    else MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun AuditEvidenceCard(
    eventId: String,
    eventType: String,
    receipts: List<ReceiptEntity>,
    readOnly: Boolean,
    onCameraCapture: (String) -> Unit,
    onGalleryPick: (String) -> Unit,
    onExportEvidence: (String) -> Unit,
    onReceiptPreview: suspend (ReceiptEntity) -> Bitmap?,
    onOpenReceipt: (ReceiptEntity) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "BUKTI TRANSAKSI",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    letterSpacing = 0.8.sp,
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = KronChipShape,
                ) {
                    Text(
                        text = "${receipts.size} berkas",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (receipts.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = KronFieldShape,
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.4f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 18.dp, horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(28.dp),
                        )
                        Text(
                            text = "Belum ada foto bukti untuk event ini.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    receipts.take(10).forEach { receipt ->
                        ReceiptEvidenceItem(receipt, onReceiptPreview, onOpenReceipt)
                    }
                    if (receipts.size > 10) {
                        Text(
                            text = "${receipts.size - 10} bukti lain tersedia di Pusat Bukti.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (!readOnly && eventType in setOf("INCOME", "EXPENSE", "UNEXPECTED_EXPENSE")) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = { onCameraCapture(eventId) },
                        modifier = Modifier.weight(1f),
                        shape = KronButtonShape,
                    ) {
                        Icon(Icons.Outlined.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Ambil foto")
                    }
                    OutlinedButton(
                        onClick = { onGalleryPick(eventId) },
                        modifier = Modifier.weight(1f),
                        shape = KronButtonShape,
                    ) {
                        Icon(Icons.Outlined.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pilih galeri")
                    }
                }
                Text(
                    text = "Foto disalin ke penyimpanan privat dan dienkripsi.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (!readOnly) {
                OutlinedButton(
                    onClick = { onExportEvidence(eventId) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = KronButtonShape,
                ) {
                    Icon(Icons.Outlined.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Ekspor paket bukti transaksi")
                }
            }
        }
    }
}

@Composable
private fun AuditActionCard(
    event: ActivityRow,
    readOnly: Boolean,
    lifecycleEvent: Boolean,
    reversalRestored: Boolean,
    onRestoreReversal: ((String) -> Unit)?,
    correctionMode: Boolean,
    onCorrectionModeChange: (Boolean) -> Unit,
    correctedTitle: String,
    onCorrectedTitleChange: (String) -> Unit,
    correctedNote: String,
    onCorrectedNoteChange: (String) -> Unit,
    reason: String,
    onReasonChange: (String) -> Unit,
) {
    if (readOnly) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KronSubCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Text("Role Viewer hanya dapat membaca audit transaksi.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
        }
        return
    }

    if (lifecycleEvent) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KronSubCardShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(20.dp))
                Text(
                    text = if (event.type in setOf("DEBT_OPEN", "DEBT_ARCHIVE")) {
                        "Event tracker hutang bersifat read-only. Gunakan detail hutang untuk melihat riwayatnya."
                    } else {
                        "Event lifecycle bersifat read-only. Gunakan tab Arsip untuk memulihkan atau mengarsipkan kembali."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
        return
    }

    if (event.reversedByEventId != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KronSubCardShape,
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f)),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Transaksi Telah Dibatalkan", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                    Text("Event ini sudah dibatalkan dengan reversal dan tidak dapat diubah lagi.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    if (event.type == "REVERSAL" && onRestoreReversal != null && event.relatedEventId != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KronSubCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "PEMULIHAN TRANSAKSI REVERSAL",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                    letterSpacing = 0.8.sp,
                )
                if (reversalRestored) {
                    Text("Event sudah dipulihkan", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                } else {
                    var remainingMillis by remember { mutableLongStateOf(0L) }
                    LaunchedEffect(event.id) {
                        while (true) {
                            remainingMillis = 7L * 24 * 60 * 60 * 1000 - (System.currentTimeMillis() - event.createdAt)
                            if (remainingMillis <= 0L) break
                            delay(60_000L)
                        }
                    }
                    val remainingText = when {
                        remainingMillis <= 0L -> "Periode pemulihan telah berakhir"
                        remainingMillis >= 2 * 24 * 60 * 60 * 1000L -> "${remainingMillis / (24 * 60 * 60 * 1000L)} hari tersisa"
                        remainingMillis >= 24 * 60 * 60 * 1000L -> "1 hari ${remainingMillis % (24 * 60 * 60 * 1000L) / (60 * 60 * 1000L)} jam tersisa"
                        remainingMillis >= 60 * 60 * 1000L -> "${remainingMillis / (60 * 60 * 1000L)} jam ${remainingMillis % (60 * 60 * 1000L) / (60 * 1000L)} menit tersisa"
                        else -> "${remainingMillis / (60 * 1000L)} menit tersisa"
                    }
                    Button(
                        onClick = { onRestoreReversal(event.relatedEventId) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = KronButtonShape,
                        enabled = remainingMillis > 0L,
                    ) {
                        Icon(Icons.Outlined.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Pulihkan transaksi")
                    }
                    Text(remainingText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        return
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                text = "TINDAKAN AUDIT & KOREKSI",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.tertiary,
                letterSpacing = 0.8.sp,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = !correctionMode,
                    onClick = { onCorrectionModeChange(false) },
                    label = { Text("Batalkan") },
                    shape = KronChipShape,
                )
                FilterChip(
                    selected = correctionMode,
                    onClick = { onCorrectionModeChange(true) },
                    label = { Text("Koreksi") },
                    shape = KronChipShape,
                )
            }

            if (correctionMode) {
                OutlinedTextField(
                    value = correctedTitle,
                    onValueChange = onCorrectedTitleChange,
                    label = { Text("Judul pengganti") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = KronFieldShape,
                )
                OutlinedTextField(
                    value = correctedNote,
                    onValueChange = onCorrectedNoteChange,
                    label = { Text("Catatan pengganti") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = KronFieldShape,
                )
                Text(
                    text = "KRON membuat reversal dan event pengganti. Event lama tidak diubah untuk menjaga integritas jurnal.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedTextField(
                value = reason,
                onValueChange = onReasonChange,
                label = { Text(if (correctionMode) "Alasan koreksi (wajib diisi)" else "Alasan pembatalan (wajib diisi)") },
                modifier = Modifier.fillMaxWidth(),
                shape = KronFieldShape,
            )
        }
    }
}

@Composable
private fun AuditFlowNode(
    heading: String,
    label: String,
    detail: String,
    before: Long,
    after: Long,
    valuesVisible: Boolean,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = KronFieldShape,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = heading.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                Text(
                    text = "${displayMoney(before, valuesVisible)} → ${displayMoney(after, valuesVisible)}",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (detail.isNotBlank()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ReceiptEvidenceItem(
    receipt: ReceiptEntity,
    previewLoader: suspend (ReceiptEntity) -> Bitmap?,
    onOpen: (ReceiptEntity) -> Unit,
) {
    val available = receipt.localPath?.let { java.io.File(it).isFile } == true
    val preview by produceState<Bitmap?>(initialValue = null, receipt.id, receipt.localPath) {
        value = if (available) previewLoader(receipt) else null
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available) { onOpen(receipt) },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        shape = KronFieldShape,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            preview?.let { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Bukti ${receipt.displayName}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(KronFieldShape),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(receipt.displayName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (!available) {
                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f), shape = KronChipShape) {
                        Text("Belum tersedia", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${receipt.mimeType} · ${receipt.byteSize.coerceAtLeast(0) / 1024} KB · ${receipt.origin}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (available) {
                    Text(
                        if (preview == null) "Ketuk untuk membuka" else "Buka penuh",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
fun EvidenceCenterDialog(
    health: EvidenceHealth?,
    verification: EvidenceVerificationResult?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onExportPdf: (Long, Long) -> Unit,
    onExportPackage: (Long, Long) -> Unit,
    onVerifyPackage: () -> Unit,
    readOnly: Boolean = false,
) {
    var startDate by rememberDate(LocalDate.now().minusDays(29))
    var endDate by rememberDate(LocalDate.now())
    FormDialog("Pusat Bukti", onDismiss, confirmText = "Periksa ulang", confirmEnabled = true, onConfirm = onRefresh) {
        HudCard {
            Text(
                when {
                    health == null -> "Belum diperiksa"
                    health.valid -> "Valid"
                    else -> "Integrity problem"
                },
                style = MaterialTheme.typography.titleLarge,
                color = if (health?.valid == true) KronGreen else MaterialTheme.colorScheme.error,
            )
            Text(health?.message ?: "Jalankan pemeriksaan ledger dan bukti.")
            if (health != null) {
                Text("Event ${health.eventCount} · Seal ${health.sealCount}")
                Text("Hilang ${health.missingEvidence} · Berubah ${health.changedEvidence} · Legacy ${health.legacyEvidence}")
            }
        }
        DateField("Tanggal mulai", startDate, { it?.let { selected -> startDate = selected } })
        DateField("Tanggal akhir", endDate, { it?.let { selected -> endDate = selected } })
        if (endDate.isBefore(startDate)) Text("Tanggal akhir tidak boleh sebelum tanggal mulai.", color = MaterialTheme.colorScheme.error)
        if (readOnly) {
            Text("Ekspor dinonaktifkan untuk role Viewer.", color = MaterialTheme.colorScheme.tertiary)
        } else {
            Button(
                onClick = { onExportPdf(startDate.toEpochDay(), endDate.toEpochDay()) },
                enabled = !endDate.isBefore(startDate),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ekspor PDF ringkasan") }
            Button(
                onClick = { onExportPackage(startDate.toEpochDay(), endDate.toEpochDay()) },
                enabled = health?.valid == true && !endDate.isBefore(startDate),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Ekspor .kronevidence") }
        }
        OutlinedButton(onClick = onVerifyPackage, modifier = Modifier.fillMaxWidth()) { Text("Verifikasi paket bukti") }
        if (verification != null) {
            HudCard(accent = if (verification.valid) KronGreen else MaterialTheme.colorScheme.error) {
                Text(if (verification.valid) "Paket valid" else "Paket tidak valid", style = MaterialTheme.typography.titleMedium)
                Text(verification.message)
                Text("Event ${verification.eventCount} · Bukti ${verification.attachmentCount}")
                Text("Chain ${verification.chainHead.take(16)}…", style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(EvidencePackageManager.DISCLAIMER, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun FormDialog(title: String, onDismiss: () -> Unit, confirmText: String = "Simpan", confirmEnabled: Boolean, onConfirm: () -> Unit, content: @Composable () -> Unit) {
    val keypadHost = remember { CalculatorKeypadHostState() }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val isImeVisible = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    LaunchedEffect(isImeVisible) {
        if (isImeVisible && keypadHost.isVisible) {
            keypadHost.dismiss()
        }
    }

    Dialog(
        onDismissRequest = {
            if (keypadHost.isVisible) {
                keypadHost.dismiss()
            } else {
                onDismiss()
            }
        },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        CompositionLocalProvider(LocalCalculatorKeypadHost provides keypadHost) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Tutup") }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                    Column(
                        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        content()
                        Spacer(Modifier.height(16.dp))
                    }

                    // Hover Keypad docked at bottom like system keyboard
                    AnimatedVisibility(
                        visible = keypadHost.isVisible,
                        enter = slideInVertically { it } + fadeIn(),
                        exit = slideOutVertically { it } + fadeOut(),
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp,
                            shadowElevation = 16.dp,
                        ) {
                            Column {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                                CalculatorKeypadView(host = keypadHost)
                            }
                        }
                    }

                    // Confirm button bar
                    AnimatedVisibility(
                        visible = !keypadHost.isVisible,
                        enter = fadeIn(),
                        exit = fadeOut(),
                    ) {
                        Column {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                            Button(
                                onClick = onConfirm,
                                enabled = confirmEnabled,
                                shape = KronButtonShape,
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp).height(48.dp),
                            ) { Text(confirmText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }
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
                FilterChip(selected = value == key, onClick = { onValue(key) }, label = { Text(label) }, shape = KronChipShape)
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = { open = true },
            modifier = Modifier.weight(1f).height(54.dp),
            shape = KronFieldShape,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        date?.let { dialogDateFormat.format(it) } ?: "Pilih tanggal",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (date != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(Icons.Outlined.CalendarMonth, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    val selectedItem = values.firstOrNull { key(it) == selected }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = KronFieldShape,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedItem?.let(text) ?: "Pilih $label",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedItem != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(text(value)) }, onClick = { onSelect(key(value)); expanded = false }) }
        }
    }
}

@Composable
private fun <T, K> ChoiceFieldNullable(label: String, selected: K?, values: List<T>, key: (T) -> K, text: (T) -> String, nullText: String, onSelect: (K?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selectedItem = values.firstOrNull { key(it) == selected }
    Column {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(54.dp),
            shape = KronFieldShape,
            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.28f)),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.Center) {
                    Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        selectedItem?.let(text) ?: nullText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (selectedItem != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val isCash = selected == FundingChannel.CASH
        val isEBudget = selected == FundingChannel.EBUDGET

        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable { onSelect(FundingChannel.CASH) },
            shape = KronSubCardShape,
            color = if (isCash) KronGold.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(if (isCash) 1.5.dp else 0.8.dp, if (isCash) KronGold else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = KronGold.copy(alpha = 0.2f),
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.Payments, contentDescription = null, tint = KronGold, modifier = Modifier.size(15.dp))
                    }
                }
                Column {
                    Text("Cash", style = MaterialTheme.typography.titleSmall, fontWeight = if (isCash) FontWeight.Bold else FontWeight.Medium, color = if (isCash) KronGold else MaterialTheme.colorScheme.onSurface)
                    Text("Tunai & fisik", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Surface(
            modifier = Modifier
                .weight(1f)
                .clickable { onSelect(FundingChannel.EBUDGET) },
            shape = KronSubCardShape,
            color = if (isEBudget) KronBlue.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(if (isEBudget) 1.5.dp else 0.8.dp, if (isEBudget) KronBlue else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = CircleShape,
                    color = KronBlue.copy(alpha = 0.2f),
                    modifier = Modifier.size(26.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(androidx.compose.material.icons.Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = KronBlue, modifier = Modifier.size(15.dp))
                    }
                }
                Column {
                    Text("eBudget", style = MaterialTheme.typography.titleSmall, fontWeight = if (isEBudget) FontWeight.Bold else FontWeight.Medium, color = if (isEBudget) KronBlue else MaterialTheme.colorScheme.onSurface)
                    Text("Bank & e-wallet", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
