package com.morneven.kron.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.DebtCalculator
import com.morneven.kron.data.DebtEntity
import com.morneven.kron.data.DebtEntryEntity
import com.morneven.kron.data.DebtRole
import com.morneven.kron.data.DebtStatus
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.data.InterestInterval
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.MoneyField
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.components.parseMoneyInput
import com.morneven.kron.ui.dialogs.FormDialog
import com.morneven.kron.ui.theme.KronGreen
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DebtScreen(
    state: KronUiState,
    onBack: () -> Unit,
    onCreate: (String, String, String, Long, Int, Int, String, LocalDate, LocalDate?, String) -> Unit,
    onPayment: (String, String, Long, Long?, String, LocalDate) -> Unit,
    onArchive: (String, String) -> Unit,
    onViewEvent: (String) -> Unit,
    readOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    var roleFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var statusFilter by rememberSaveable { mutableStateOf<String?>(DebtStatus.OPEN) }
    var createRole by rememberSaveable { mutableStateOf<String?>(null) }
    var paymentDebt by remember { mutableStateOf<DebtEntity?>(null) }
    var detailDebt by remember { mutableStateOf<DebtEntity?>(null) }
    var archiveDebt by remember { mutableStateOf<DebtEntity?>(null) }
    val today = LocalDate.now()
    val debts = state.debts
        .filter { roleFilter == null || it.role == roleFilter }
        .filter { statusFilter == null || it.status == statusFilter }
        .sortedWith(compareBy<DebtEntity> { it.dueEpochDay ?: Long.MAX_VALUE }.thenByDescending { it.createdAt })

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onBack, modifier = Modifier.height(48.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Kembali")
                    Text(" Kembali")
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Hutang & Piutang", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Tracker terpisah dari saldo. Pembayaran tetap dicatat ke ledger.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!readOnly) {
            item {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { createRole = DebtRole.DEBTOR }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.ArrowDownward, contentDescription = null)
                        Text(" Saya berhutang")
                    }
                    OutlinedButton(onClick = { createRole = DebtRole.CREDITOR }, modifier = Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.ArrowUpward, contentDescription = null)
                        Text(" Saya dihutangi")
                    }
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Filter", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = roleFilter == null, onClick = { roleFilter = null }, label = { Text("Semua") })
                    FilterChip(selected = roleFilter == DebtRole.DEBTOR, onClick = { roleFilter = DebtRole.DEBTOR }, label = { Text("Hutang") })
                    FilterChip(selected = roleFilter == DebtRole.CREDITOR, onClick = { roleFilter = DebtRole.CREDITOR }, label = { Text("Piutang") })
                }
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(selected = statusFilter == DebtStatus.OPEN, onClick = { statusFilter = DebtStatus.OPEN }, label = { Text("Aktif") })
                    FilterChip(selected = statusFilter == DebtStatus.SETTLED, onClick = { statusFilter = DebtStatus.SETTLED }, label = { Text("Lunas") })
                    FilterChip(selected = statusFilter == DebtStatus.ARCHIVED, onClick = { statusFilter = DebtStatus.ARCHIVED }, label = { Text("Arsip") })
                    FilterChip(selected = statusFilter == null, onClick = { statusFilter = null }, label = { Text("Semua status") })
                }
            }
        }
        if (debts.isEmpty()) {
            item {
                HudCard {
                    Text("Tidak ada hutang/piutang pada filter ini", style = MaterialTheme.typography.titleMedium)
                    Text("Saat semuanya lunas, bagian ini sengaja tenang.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            item { SectionHeader("Daftar hutang") }
            items(debts, key = DebtEntity::id) { debt ->
                DebtCard(
                    debt = debt,
                    valuesVisible = state.valuesVisible,
                    today = today,
                    readOnly = readOnly,
                    onClick = { detailDebt = debt },
                    onPayment = { paymentDebt = debt },
                    onArchive = { archiveDebt = debt },
                )
            }
        }
        item { Spacer(Modifier.height(76.dp)) }
    }

    createRole?.let { role ->
        DebtCreateDialog(
            role = role,
            onDismiss = { createRole = null },
            onCreate = { counterparty, title, principal, rateBps, interval, unit, start, due, note ->
                onCreate(role, counterparty, title, principal, rateBps, interval, unit, start, due, note)
                createRole = null
            },
        )
    }
    paymentDebt?.let { debt ->
        DebtPaymentDialog(
            debt = debt,
            state = state,
            onDismiss = { paymentDebt = null },
            onSave = { channel, amount, categoryId, note, date ->
                onPayment(debt.id, channel, amount, categoryId, note, date)
                paymentDebt = null
            },
        )
    }
    detailDebt?.let { debt ->
        DebtDetailDialog(
            debt = debt,
            entries = state.debtEntries.filter { it.debtId == debt.id },
            valuesVisible = state.valuesVisible,
            onDismiss = { detailDebt = null },
            onViewEvent = { eventId ->
                detailDebt = null
                onViewEvent(eventId)
            },
        )
    }
    archiveDebt?.let { debt ->
        DebtArchiveDialog(debt, onDismiss = { archiveDebt = null }) { note ->
            onArchive(debt.id, note)
            archiveDebt = null
        }
    }
}

@Composable
private fun DebtCard(
    debt: DebtEntity,
    valuesVisible: Boolean,
    today: LocalDate,
    readOnly: Boolean,
    onClick: () -> Unit,
    onPayment: () -> Unit,
    onArchive: () -> Unit,
) {
    val interest = DebtCalculator.currentInterest(debt, today)
    val due = debt.dueEpochDay?.let(LocalDate::ofEpochDay)
    val isOverdue = DebtCalculator.isOverdue(debt, today)
    val color = when {
        debt.status == DebtStatus.ARCHIVED -> MaterialTheme.colorScheme.outline
        isOverdue -> MaterialTheme.colorScheme.error
        debt.role == DebtRole.DEBTOR -> MaterialTheme.colorScheme.primary
        else -> KronGreen
    }
    HudCard(
        accent = color.copy(alpha = 0.7f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (debt.role == DebtRole.DEBTOR) "HUTANG" else "PIUTANG", style = MaterialTheme.typography.labelSmall, color = color)
                Text(debt.title, style = MaterialTheme.typography.titleMedium)
                Text(debt.counterparty, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(statusLabel(debt.status), style = MaterialTheme.typography.labelMedium, color = color)
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("Pokok tersisa", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayMoney(debt.principalOutstanding, valuesVisible), style = MaterialTheme.typography.titleMedium)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Bunga berjalan", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(displayMoney(interest, valuesVisible), style = MaterialTheme.typography.titleMedium)
            }
        }
        due?.let {
            Text(
                "Tenggat ${it.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID")))}${if (isOverdue) " · Terlambat" else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isOverdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        if (!readOnly && debt.status != DebtStatus.ARCHIVED) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (debt.status == DebtStatus.OPEN) {
                    Button(onClick = onPayment, modifier = Modifier.weight(1f).height(48.dp)) {
                        Icon(Icons.Outlined.Payments, contentDescription = null)
                        Text(if (debt.role == DebtRole.DEBTOR) " Bayar" else " Terima")
                    }
                }
                OutlinedButton(
                    onClick = onArchive,
                    modifier = if (debt.status == DebtStatus.OPEN) Modifier.height(48.dp) else Modifier.fillMaxWidth().height(48.dp),
                ) { Text("Arsip") }
            }
        }
    }
}

@Composable
private fun DebtCreateDialog(
    role: String,
    onDismiss: () -> Unit,
    onCreate: (String, String, Long, Int, Int, String, LocalDate, LocalDate?, String) -> Unit,
) {
    var counterparty by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var principal by rememberSaveable { mutableStateOf("") }
    var rate by rememberSaveable { mutableStateOf("0") }
    var interval by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf(InterestInterval.MONTHS) }
    var note by rememberSaveable { mutableStateOf("") }
    var startDate by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    var dueDate by rememberSaveable { mutableStateOf<Long?>(null) }
    val parsedRate = rateToBps(rate)
    val parsedInterval = interval.toIntOrNull()?.coerceAtLeast(1) ?: 0
    val start = LocalDate.ofEpochDay(startDate)
    val due = dueDate?.let(LocalDate::ofEpochDay)
    val valid = counterparty.isNotBlank() && title.isNotBlank() && parseMoneyInput(principal) > 0 &&
        parsedRate != null && parsedInterval > 0 && (due == null || !due.isBefore(start))
    FormDialog(
        title = if (role == DebtRole.DEBTOR) "Catat hutang" else "Catat piutang",
        onDismiss = onDismiss,
        confirmText = "Simpan tracker",
        confirmEnabled = valid,
        onConfirm = { onCreate(counterparty.trim(), title.trim(), parseMoneyInput(principal), parsedRate!!, parsedInterval, unit, start, due, note.trim()) },
    ) {
        Text("Pembukaan tracker tidak mengubah saldo. Saldo berubah saat pembayaran dicatat.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        OutlinedTextField(counterparty, { counterparty = it }, label = { Text(if (role == DebtRole.DEBTOR) "Pemberi hutang" else "Pihak yang berhutang") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(title, { title = it }, label = { Text("Judul") }, modifier = Modifier.fillMaxWidth())
        MoneyField(principal, { principal = it }, "Pokok awal")
        OutlinedTextField(
            value = rate,
            onValueChange = { rate = it.filter { char -> char.isDigit() || char == '.' || char == ',' } },
            label = { Text("Bunga per interval (%)") },
            supportingText = { Text("Opsional. Isi 0 jika tanpa bunga.") },
            isError = parsedRate == null,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        if (parsedRate == null) Text("Bunga harus antara 0% dan 1000%.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if ((parsedRate ?: 0) > 0) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = unit == InterestInterval.MONTHS, onClick = { unit = InterestInterval.MONTHS }, label = { Text("Bulan") })
                FilterChip(selected = unit == InterestInterval.DAYS, onClick = { unit = InterestInterval.DAYS }, label = { Text("Hari") })
            }
            OutlinedTextField(
                value = interval,
                onValueChange = { interval = it.filter(Char::isDigit) },
                label = { Text(if (unit == InterestInterval.DAYS) "Interval bunga (hari)" else "Interval bunga (bulan)") },
                supportingText = { Text(if (unit == InterestInterval.DAYS) "Bunga sederhana dihitung setelah hari penuh." else "Bunga sederhana dihitung setelah bulan penuh.") },
                isError = parsedInterval <= 0,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        DebtDateField("Tanggal mulai", start, allowClear = false) { startDate = requireNotNull(it).toEpochDay() }
        DebtDateField("Tenggat (opsional)", due, allowClear = true) { dueDate = it?.toEpochDay() }
        if (due != null && due.isBefore(start)) Text("Tenggat tidak boleh sebelum tanggal mulai.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(note, { note = it }, label = { Text("Catatan (opsional)") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DebtPaymentDialog(
    debt: DebtEntity,
    state: KronUiState,
    onDismiss: () -> Unit,
    onSave: (String, Long, Long?, String, LocalDate) -> Unit,
) {
    val direction = if (debt.role == DebtRole.DEBTOR) TransactionDirection.EXPENSE else TransactionDirection.INCOME
    val categories = state.categories.filter { it.direction == direction }
    var channel by rememberSaveable { mutableStateOf(FundingChannel.CASH) }
    var categoryId by rememberSaveable { mutableStateOf(categories.firstOrNull()?.id) }
    var amount by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var dateDay by rememberSaveable { mutableStateOf(LocalDate.now().toEpochDay()) }
    val paymentDate = LocalDate.ofEpochDay(dateDay)
    val dateValid = !paymentDate.isBefore(LocalDate.ofEpochDay(debt.interestAnchorEpochDay))
    val interest = DebtCalculator.currentInterest(debt, paymentDate)
    val maximum = debt.principalOutstanding + interest
    val amountValue = parseMoneyInput(amount)
    val valid = dateValid && amountValue in 1..maximum && (categories.isEmpty() || categoryId != null)
    FormDialog(
        title = if (debt.role == DebtRole.DEBTOR) "Bayar hutang" else "Terima piutang",
        onDismiss = onDismiss,
        confirmText = "Catat pembayaran",
        confirmEnabled = valid,
        onConfirm = { onSave(channel, amountValue, categoryId, note.trim(), paymentDate) },
    ) {
        Text(debt.title, style = MaterialTheme.typography.titleMedium)
        Text("Pokok ${displayMoney(debt.principalOutstanding, state.valuesVisible)} · bunga ${displayMoney(interest, state.valuesVisible)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Pembayaran menutup bunga terlebih dahulu, lalu pokok.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        Text("Kanal", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = channel == FundingChannel.CASH, onClick = { channel = FundingChannel.CASH }, label = { Text("Cash") })
            FilterChip(selected = channel == FundingChannel.EBUDGET, onClick = { channel = FundingChannel.EBUDGET }, label = { Text("eBudget") })
        }
        MoneyField(amount, { amount = it }, "Nominal pembayaran")
        Text("Maksimum ${displayMoney(maximum, state.valuesVisible)}", style = MaterialTheme.typography.bodySmall, color = if (amountValue > maximum) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (categories.isNotEmpty()) {
            Text("Kategori transaksi", style = MaterialTheme.typography.labelLarge)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                categories.forEach { category ->
                    FilterChip(selected = categoryId == category.id, onClick = { categoryId = category.id }, label = { Text(category.name) })
                }
            }
        }
        DebtDateField("Tanggal pembayaran", paymentDate, allowClear = false) { dateDay = requireNotNull(it).toEpochDay() }
        if (!dateValid) Text("Tanggal pembayaran tidak boleh sebelum pembayaran terakhir.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(note, { note = it }, label = { Text("Catatan (opsional)") }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun DebtDetailDialog(
    debt: DebtEntity,
    entries: List<DebtEntryEntity>,
    valuesVisible: Boolean,
    onDismiss: () -> Unit,
    onViewEvent: (String) -> Unit,
) {
    val interest = DebtCalculator.currentInterest(debt, LocalDate.now())
    FormDialog("Detail hutang", onDismiss, confirmText = "Tutup", confirmEnabled = true, onConfirm = onDismiss) {
        Text(debt.title, style = MaterialTheme.typography.headlineSmall)
        Text(debt.counterparty, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.tertiary)
        Text(if (debt.role == DebtRole.DEBTOR) "Saya berhutang" else "Saya dihutangi", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text("Pokok awal: ${displayMoney(debt.principalOriginal, valuesVisible)}")
        Text("Pokok tersisa: ${displayMoney(debt.principalOutstanding, valuesVisible)}")
        Text("Bunga berjalan: ${displayMoney(interest, valuesVisible)}")
        Text("Status: ${statusLabel(debt.status)}")
        debt.dueEpochDay?.let { Text("Tenggat: ${LocalDate.ofEpochDay(it)}") }
        if (debt.interestRateBps > 0) Text("Bunga ${debt.interestRateBps / 100.0}% setiap ${debt.interestIntervalMonths} ${if (debt.interestIntervalUnit == InterestInterval.DAYS) "hari" else "bulan"}")
        HorizontalDivider()
        Text("Riwayat", style = MaterialTheme.typography.titleMedium)
        entries.sortedByDescending { it.createdAt }.forEach { entry ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entryLabel(entry.type), style = MaterialTheme.typography.bodyLarge)
                Text(
                    "${LocalDate.ofEpochDay(entry.effectiveEpochDay)} · Pokok ${displayMoney(entry.principalAmount, valuesVisible)} · Bunga ${displayMoney(entry.interestAmount, valuesVisible)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entry.note.isNotBlank()) Text(entry.note, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onViewEvent(entry.eventId) }, modifier = Modifier.height(48.dp)) {
                    Text("Lihat transaksi & audit")
                }
            }
        }
    }
}

@Composable
private fun DebtArchiveDialog(debt: DebtEntity, onDismiss: () -> Unit, onArchive: (String) -> Unit) {
    var note by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Arsipkan hutang") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Riwayat dan transaksi tidak dihapus. Tracker ${debt.title} hanya dipindahkan ke arsip.")
                OutlinedTextField(note, { note = it }, label = { Text("Alasan arsip") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { onArchive(note.trim()) }, enabled = note.isNotBlank()) { Text("Arsipkan") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun DebtDateField(label: String, date: LocalDate?, allowClear: Boolean, onDate: (LocalDate?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.weight(1f).height(48.dp)) { Text("$label: ${date ?: "Pilih"}") }
        if (allowClear && date != null) TextButton(onClick = { onDate(null) }) { Text("Hapus") }
    }
    if (open) {
        val picker = rememberDatePickerState(initialSelectedDateMillis = date?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { onDate(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Batal") } },
        ) { DatePicker(picker) }
    }
}

private fun rateToBps(value: String): Int? = runCatching {
    value.trim().replace(',', '.').toBigDecimal()
        .movePointRight(2)
        .setScale(0, RoundingMode.HALF_UP)
        .intValueExact()
        .takeIf { it in 0..100_000 }
}.getOrNull()

private fun statusLabel(status: String): String = when (status) {
    DebtStatus.OPEN -> "Aktif"
    DebtStatus.SETTLED -> "Lunas"
    DebtStatus.ARCHIVED -> "Arsip"
    else -> status
}

private fun entryLabel(type: String): String = when (type) {
    "OPEN" -> "Tracker dibuat"
    "PAYMENT" -> "Pembayaran"
    "SETTLEMENT" -> "Pelunasan"
    "REVERSAL" -> "Pembayaran dibatalkan"
    "ARCHIVE" -> "Diarsipkan"
    else -> type.replace('_', ' ')
}
