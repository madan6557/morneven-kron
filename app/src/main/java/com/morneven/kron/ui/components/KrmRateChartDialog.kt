package com.morneven.kron.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import com.morneven.kron.ui.theme.KronPillShape
import com.morneven.kron.ui.theme.KronSubCardShape
import com.morneven.kron.ui.theme.LocalKronDarkTheme
import com.morneven.kron.widget.KrmRateHistoryEntry
import com.morneven.kron.widget.KronCurrencyManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private enum class KrmChartRange(val label: String, val days: Int) {
    SEVEN_DAYS("7 hari", 7),
    THIRTY_DAYS("30 hari", 30),
    NINETY_DAYS("90 hari", 90),
    ONE_YEAR("1 tahun", 365),
    CUSTOM("Custom", 0),
}

@Composable
fun KrmRateChartDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val allHistory = remember { KronCurrencyManager.getKrmRateHistory(context) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    var selectedRange by rememberSaveable { mutableStateOf(KrmChartRange.THIRTY_DAYS) }

    val today = remember { LocalDate.now() }
    var customStartDay by rememberSaveable { mutableLongStateOf(today.minusDays(29).toEpochDay()) }
    var customEndDay by rememberSaveable { mutableLongStateOf(today.toEpochDay()) }
    var pickingStart by rememberSaveable { mutableStateOf(true) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }

    val customStart = LocalDate.ofEpochDay(customStartDay)
    val customEnd = LocalDate.ofEpochDay(customEndDay)

    val (startMs, endMs) = remember(selectedRange, customStartDay, customEndDay) {
        when (selectedRange) {
            KrmChartRange.CUSTOM -> {
                val s = customStart.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val e = customEnd.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1L
                s to e
            }
            else -> {
                val now = System.currentTimeMillis()
                val s = now - (selectedRange.days.toLong() * 24L * 60L * 60L * 1000L)
                s to now
            }
        }
    }

    val history = remember(allHistory, startMs, endMs) {
        val filtered = allHistory.filter { it.timestampMs in startMs..endMs }
        if (filtered.isEmpty() && allHistory.isNotEmpty()) {
            listOf(allHistory.last())
        } else {
            filtered
        }
    }

    val symbols = remember {
        DecimalFormatSymbols(Locale.forLanguageTag("id-ID")).apply {
            groupingSeparator = '.'
            decimalSeparator = ','
        }
    }
    val idrFormatter = remember(symbols) { DecimalFormat("#,##0", symbols) }
    val deltaFormatter = remember(symbols) { DecimalFormat("#,##0.0", symbols) }
    val timeFormatter = remember {
        DateTimeFormatter.ofPattern("HH:mm:ss", Locale.forLanguageTag("id-ID"))
    }
    val dateFormatter = remember {
        DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("id-ID"))
    }
    val shortDateFormatter = remember {
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
    }

    val currentPoint = history.lastOrNull() ?: allHistory.lastOrNull() ?: KrmRateHistoryEntry(System.currentTimeMillis(), KronCurrencyManager.KRM_BASE_IDR)
    val displayPoint = selectedIndex?.let { history.getOrNull(it) } ?: currentPoint

    val rates = history.map { it.rate }
    val minRate = rates.minOrNull() ?: currentPoint.rate
    val maxRate = rates.maxOrNull() ?: currentPoint.rate
    val avgRate = if (rates.isNotEmpty()) rates.average() else currentPoint.rate

    val initialRate = history.firstOrNull()?.rate ?: currentPoint.rate
    val totalDeltaNominal = currentPoint.rate - initialRate
    val totalDeltaPercent = if (initialRate > 0.0) (totalDeltaNominal / initialRate * 100.0) else 0.0

    AlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = modifier
            .padding(16.dp)
            .fillMaxWidth(),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = KronGold.copy(alpha = 0.16f),
                        border = BorderStroke(0.8.dp, KronGold.copy(alpha = 0.35f)),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.ShowChart,
                                contentDescription = null,
                                tint = KronGold,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Column {
                        Text(
                            "KRON",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "Mata uang Morneven (KRM)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Tutup dialog",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Header card with current/selected rate and delta
                item {
                    HudCard(
                        modifier = Modifier.fillMaxWidth(),
                        accent = KronGold.copy(alpha = 0.4f),
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    if (selectedIndex != null) "KURS PADA TITIK TERPILIH" else "KURS TERKINI KRM",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KronGold,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 0.5.sp,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "1 KRM = Rp ${idrFormatter.format(displayPoint.rate.roundToInt())}",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val timeText = Instant.ofEpochMilli(displayPoint.timestampMs)
                                    .atZone(ZoneId.systemDefault())
                                    .format(dateFormatter)
                                Text(
                                    text = "Tercatat: $timeText",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Trend badge vs initial point in selected range
                            val isPos = totalDeltaNominal >= 0.0
                            val badgeColor = if (isPos) KronGreen else MaterialTheme.colorScheme.error
                            val sign = if (isPos) "+" else ""
                            Surface(
                                shape = KronPillShape,
                                color = badgeColor.copy(alpha = 0.12f),
                                border = BorderStroke(0.8.dp, badgeColor.copy(alpha = 0.35f)),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        if (isPos) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                                        contentDescription = null,
                                        tint = badgeColor,
                                        modifier = Modifier.size(14.dp),
                                    )
                                    Text(
                                        text = "$sign${deltaFormatter.format(totalDeltaPercent)}%",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = badgeColor,
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                        Spacer(Modifier.height(8.dp))

                        // Min, Avg, Max 3-box row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            StatMiniBox(
                                label = "Terendah",
                                value = "Rp ${idrFormatter.format(minRate.roundToInt())}",
                                modifier = Modifier.weight(1f),
                            )
                            StatMiniBox(
                                label = "Rata-rata",
                                value = "Rp ${idrFormatter.format(avgRate.roundToInt())}",
                                modifier = Modifier.weight(1f),
                            )
                            StatMiniBox(
                                label = "Tertinggi",
                                value = "Rp ${idrFormatter.format(maxRate.roundToInt())}",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }

                // Range Selector (7 hari, 30 hari, 90 hari, 1 tahun, Custom)
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp),
                        ) {
                            items(KrmChartRange.entries, key = { it.name }) { item ->
                                FilterChip(
                                    selected = selectedRange == item,
                                    onClick = {
                                        selectedRange = item
                                        selectedIndex = null
                                    },
                                    label = { Text(item.label) },
                                )
                            }
                        }

                        if (selectedRange == KrmChartRange.CUSTOM) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Surface(
                                    shape = KronSubCardShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            pickingStart = true
                                            showDatePicker = true
                                        },
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text("Mulai", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(customStart.format(shortDateFormatter), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }

                                Surface(
                                    shape = KronSubCardShape,
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            pickingStart = false
                                            showDatePicker = true
                                        },
                                ) {
                                    Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                                        Text("Sampai", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(customEnd.format(shortDateFormatter), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }

                // Interactive Chart
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Grafik Riwayat (${history.size} data)",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                            )
                            if (selectedIndex != null) {
                                Text(
                                    "Reset pilihan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KronGold,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.clickable { selectedIndex = null },
                                )
                            }
                        }

                        Surface(
                            shape = KronSubCardShape,
                            color = MaterialTheme.colorScheme.surfaceContainerLow,
                            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                KrmLineChart(
                                    history = history,
                                    selectedIndex = selectedIndex,
                                    onSelectIndex = { selectedIndex = it },
                                    idrFormatter = idrFormatter,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(180.dp),
                                )
                                Spacer(Modifier.height(6.dp))
                                KrmChartTimeAxis(
                                    history = history,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 4.dp),
                                )
                            }
                        }

                        if (selectedIndex != null && selectedIndex!! in history.indices) {
                            val selEntry = history[selectedIndex!!]
                            val prevEntry = if (selectedIndex!! > 0) history[selectedIndex!! - 1] else null
                            KrmPointInspectionCard(
                                entry = selEntry,
                                prevEntry = prevEntry,
                                initialRate = initialRate,
                                index = selectedIndex!!,
                                totalCount = history.size,
                                minRate = minRate,
                                maxRate = maxRate,
                                idrFormatter = idrFormatter,
                                deltaFormatter = deltaFormatter,
                                onPrev = if (selectedIndex!! > 0) { { selectedIndex = selectedIndex!! - 1 } } else null,
                                onNext = if (selectedIndex!! < history.size - 1) { { selectedIndex = selectedIndex!! + 1 } } else null,
                                onClose = { selectedIndex = null },
                            )
                        } else {
                            Surface(
                                shape = KronSubCardShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.5f),
                                border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ShowChart,
                                        contentDescription = null,
                                        tint = KronGold,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Text(
                                        "Ketuk atau geser kurva untuk melihat detail kurs, waktu, dan perubahan per titik.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }

                // Recent ticks table
                item {
                    Text(
                        "Riwayat Data Terakhir",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                val recentEntries = history.asReversed().take(15)
                itemsIndexed(recentEntries) { idx, entry ->
                    val nextOldest = recentEntries.getOrNull(idx + 1)
                    val stepDelta = if (nextOldest != null) entry.rate - nextOldest.rate else 0.0
                    val stepDeltaPercent = if (nextOldest != null && nextOldest.rate > 0.0) (stepDelta / nextOldest.rate * 100.0) else 0.0
                    val isStepPos = stepDelta >= 0.0
                    val stepColor = if (stepDelta == 0.0) MaterialTheme.colorScheme.onSurfaceVariant else if (isStepPos) KronGreen else MaterialTheme.colorScheme.error

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                val origIndex = history.indexOf(entry)
                                if (origIndex >= 0) selectedIndex = origIndex
                            },
                        shape = KronSubCardShape,
                        color = if (displayPoint == entry) KronGold.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceContainerLow,
                        border = BorderStroke(
                            0.6.dp,
                            if (displayPoint == entry) KronGold.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                val tStr = Instant.ofEpochMilli(entry.timestampMs)
                                    .atZone(ZoneId.systemDefault())
                                    .format(timeFormatter)
                                Text(
                                    "Pukul $tStr",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "Rp ${idrFormatter.format(entry.rate.roundToInt())}",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }

                            if (nextOldest != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Icon(
                                        if (isStepPos) Icons.AutoMirrored.Outlined.TrendingUp else Icons.AutoMirrored.Outlined.TrendingDown,
                                        contentDescription = null,
                                        tint = stepColor,
                                        modifier = Modifier.size(13.dp),
                                    )
                                    Text(
                                        text = "${if (isStepPos) "+" else ""}${deltaFormatter.format(stepDeltaPercent)}%",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = stepColor,
                                    )
                                }
                            } else {
                                Text(
                                    "Awal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Tutup")
            }
        },
    )

    if (showDatePicker) {
        val selectedDate = if (pickingStart) customStart else customEnd
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    picker.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        if (pickingStart) {
                            customStartDay = minOf(date, customEnd).toEpochDay()
                        } else {
                            customEndDay = maxOf(date, customStart).toEpochDay()
                        }
                    }
                    selectedIndex = null
                    showDatePicker = false
                }) { Text("Pilih") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Batal") } },
        ) { DatePicker(picker) }
    }
}

@Composable
private fun StatMiniBox(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f), KronSubCardShape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
        )
        Text(
            value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun KrmLineChart(
    history: List<KrmRateHistoryEntry>,
    selectedIndex: Int?,
    onSelectIndex: (Int?) -> Unit,
    idrFormatter: DecimalFormat,
    modifier: Modifier = Modifier,
) {
    val goldColor = KronGold
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val pointCount = history.size
    val textMeasurer = rememberTextMeasurer()

    val rates = history.map { it.rate }
    val rawMin = rates.minOrNull() ?: KronCurrencyManager.KRM_BASE_IDR
    val rawMax = rates.maxOrNull() ?: KronCurrencyManager.KRM_BASE_IDR
    val spread = (rawMax - rawMin).coerceAtLeast(10.0)
    val displayMin = rawMin - spread * 0.08
    val displayMax = rawMax + spread * 0.08
    val displayRange = (displayMax - displayMin).coerceAtLeast(1.0)

    val dashedEffect = remember { PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f) }

    Canvas(
        modifier = modifier
            .pointerInput(pointCount) {
                detectTapGestures { offset ->
                    if (pointCount < 2) return@detectTapGestures
                    val padLeft = 10f
                    val padRight = 10f
                    val plotWidth = (size.width - padLeft - padRight).coerceAtLeast(1f)
                    val relX = (offset.x - padLeft).coerceIn(0f, plotWidth)
                    val index = ((relX / plotWidth) * (pointCount - 1)).roundToInt().coerceIn(0, pointCount - 1)
                    if (selectedIndex == index) {
                        onSelectIndex(null)
                    } else {
                        onSelectIndex(index)
                    }
                }
            }
            .pointerInput(pointCount) {
                detectDragGestures { change, _ ->
                    if (pointCount < 2) return@detectDragGestures
                    val padLeft = 10f
                    val padRight = 10f
                    val plotWidth = (size.width - padLeft - padRight).coerceAtLeast(1f)
                    val relX = (change.position.x - padLeft).coerceIn(0f, plotWidth)
                    val index = ((relX / plotWidth) * (pointCount - 1)).roundToInt().coerceIn(0, pointCount - 1)
                    onSelectIndex(index)
                }
            },
    ) {
        val w = size.width
        val h = size.height

        val padLeft = 10f
        val padRight = 10f
        val padTop = 16f
        val padBottom = 16f

        val plotW = (w - padLeft - padRight).coerceAtLeast(1f)
        val plotH = (h - padTop - padBottom).coerceAtLeast(1f)

        // Draw 3 horizontal guide lines: top, mid, bottom
        val yTop = padTop
        val yMid = padTop + plotH * 0.5f
        val yBot = padTop + plotH

        drawLine(gridColor, Offset(padLeft, yTop), Offset(w - padRight, yTop), 1f, pathEffect = dashedEffect)
        drawLine(gridColor, Offset(padLeft, yMid), Offset(w - padRight, yMid), 1f, pathEffect = dashedEffect)
        drawLine(gridColor, Offset(padLeft, yBot), Offset(w - padRight, yBot), 1f, pathEffect = dashedEffect)

        if (pointCount < 2) {
            val y = padTop + plotH * 0.5f
            drawLine(goldColor, Offset(padLeft, y), Offset(w - padRight, y), strokeWidth = 3f, cap = StrokeCap.Round)
            drawCircle(goldColor, radius = 6f, center = Offset(w * 0.5f, y))
            return@Canvas
        }

        fun getX(i: Int): Float {
            return padLeft + plotW * (i.toFloat() / (pointCount - 1).toFloat())
        }

        fun getY(rate: Double): Float {
            val ratio = ((rate - displayMin) / displayRange).toFloat().coerceIn(0f, 1f)
            return padTop + plotH * (1f - ratio)
        }

        // Build path
        val strokePath = Path()
        val fillPath = Path()

        val x0 = getX(0)
        val y0 = getY(history[0].rate)

        strokePath.moveTo(x0, y0)
        fillPath.moveTo(x0, yBot)
        fillPath.lineTo(x0, y0)

        for (i in 1 until pointCount) {
            val prevX = getX(i - 1)
            val prevY = getY(history[i - 1].rate)
            val curX = getX(i)
            val curY = getY(history[i].rate)

            val cX1 = (prevX + curX) / 2f
            val cY1 = prevY
            val cX2 = (prevX + curX) / 2f
            val cY2 = curY

            strokePath.cubicTo(cX1, cY1, cX2, cY2, curX, curY)
            fillPath.cubicTo(cX1, cY1, cX2, cY2, curX, curY)
        }

        val lastX = getX(pointCount - 1)
        fillPath.lineTo(lastX, yBot)
        fillPath.close()

        // Draw gradient area
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(
                    goldColor.copy(alpha = 0.35f),
                    goldColor.copy(alpha = 0.05f),
                    Color.Transparent,
                ),
                startY = padTop,
                endY = yBot,
            ),
        )

        // Draw line
        drawPath(
            path = strokePath,
            color = goldColor,
            style = Stroke(
                width = 3.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )

        // Draw dots on points
        for (i in 0 until pointCount) {
            val px = getX(i)
            val py = getY(history[i].rate)
            if (pointCount <= 30) {
                drawCircle(goldColor.copy(alpha = 0.45f), radius = 2.5.dp.toPx(), center = Offset(px, py))
            }
        }

        // Draw latest point halo
        val lastY = getY(history.last().rate)
        drawCircle(goldColor.copy(alpha = 0.22f), radius = 8.dp.toPx(), center = Offset(lastX, lastY))
        drawCircle(goldColor, radius = 4.dp.toPx(), center = Offset(lastX, lastY))

        // Draw selected indicator if active
        if (selectedIndex != null && selectedIndex in history.indices) {
            val selX = getX(selectedIndex)
            val selY = getY(history[selectedIndex].rate)

            drawLine(
                color = goldColor.copy(alpha = 0.7f),
                start = Offset(selX, padTop),
                end = Offset(selX, yBot),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = dashedEffect,
            )

            drawCircle(
                color = goldColor.copy(alpha = 0.35f),
                radius = 11.dp.toPx(),
                center = Offset(selX, selY),
            )
            drawCircle(
                color = Color.White,
                radius = 5.dp.toPx(),
                center = Offset(selX, selY),
            )
            drawCircle(
                color = goldColor,
                radius = 3.dp.toPx(),
                center = Offset(selX, selY),
            )

            // On-canvas floating rate badge
            val rateText = "Rp ${idrFormatter.format(history[selectedIndex].rate.roundToInt())}"
            val textLayout = textMeasurer.measure(
                text = rateText,
                style = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                ),
            )
            val padH = 6.dp.toPx()
            val padV = 3.dp.toPx()
            val badgeW = textLayout.size.width + padH * 2
            val badgeH = textLayout.size.height + padV * 2

            val badgeY = if (selY - badgeH - 8f >= padTop) {
                selY - badgeH - 8f
            } else {
                selY + 8f
            }
            val badgeX = (selX - badgeW / 2f).coerceIn(padLeft, w - padRight - badgeW)

            drawRoundRect(
                color = Color(0xFF1E1519).copy(alpha = 0.92f),
                topLeft = Offset(badgeX, badgeY),
                size = androidx.compose.ui.geometry.Size(badgeW, badgeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
            )
            drawRoundRect(
                color = goldColor.copy(alpha = 0.6f),
                topLeft = Offset(badgeX, badgeY),
                size = androidx.compose.ui.geometry.Size(badgeW, badgeH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                style = Stroke(width = 1.dp.toPx()),
            )
            drawText(
                textLayoutResult = textLayout,
                topLeft = Offset(badgeX + padH, badgeY + padV),
            )
        }
    }
}

@Composable
private fun KrmChartTimeAxis(
    history: List<KrmRateHistoryEntry>,
    modifier: Modifier = Modifier,
) {
    if (history.isEmpty()) return
    val shortDateFormatter = remember {
        DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
    }

    val firstMs = history.first().timestampMs
    val lastMs = history.last().timestampMs
    val diffDays = (lastMs - firstMs) / (24L * 60L * 60L * 1000L)

    val formatTime: (Long) -> String = { ms ->
        val zdt = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
        if (diffDays <= 2) {
            zdt.format(DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.forLanguageTag("id-ID")))
        } else {
            zdt.format(shortDateFormatter)
        }
    }

    val startLabel = formatTime(firstMs)
    val endLabel = formatTime(lastMs)
    val midLabel = if (history.size > 2) formatTime(history[history.size / 2].timestampMs) else null

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = startLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
        if (midLabel != null && midLabel != startLabel && midLabel != endLabel) {
            Text(
                text = midLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontSize = 10.sp,
            )
        }
        Text(
            text = endLabel,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun KrmPointInspectionCard(
    entry: KrmRateHistoryEntry,
    prevEntry: KrmRateHistoryEntry?,
    initialRate: Double,
    index: Int,
    totalCount: Int,
    minRate: Double,
    maxRate: Double,
    idrFormatter: DecimalFormat,
    deltaFormatter: DecimalFormat,
    onPrev: (() -> Unit)?,
    onNext: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val isDark = LocalKronDarkTheme.current
    val dateTimeFormatter = remember {
        DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, HH:mm:ss", Locale.forLanguageTag("id-ID"))
    }
    val pointZdt = Instant.ofEpochMilli(entry.timestampMs).atZone(ZoneId.systemDefault())
    val pointDateStr = pointZdt.format(dateTimeFormatter)

    val stepDelta = if (prevEntry != null) entry.rate - prevEntry.rate else 0.0
    val stepDeltaPercent = if (prevEntry != null && prevEntry.rate > 0.0) (stepDelta / prevEntry.rate * 100.0) else 0.0
    val isStepPos = stepDelta >= 0.0
    val stepColor = if (stepDelta == 0.0) MaterialTheme.colorScheme.onSurfaceVariant else if (isStepPos) KronGreen else MaterialTheme.colorScheme.error

    val cumDelta = entry.rate - initialRate
    val cumDeltaPercent = if (initialRate > 0.0) (cumDelta / initialRate * 100.0) else 0.0
    val isCumPos = cumDelta >= 0.0
    val cumColor = if (cumDelta == 0.0) MaterialTheme.colorScheme.onSurfaceVariant else if (isCumPos) KronGreen else MaterialTheme.colorScheme.error

    Surface(
        shape = KronSubCardShape,
        color = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, KronGold.copy(alpha = if (isDark) 0.5f else 0.8f)),
        shadowElevation = if (isDark) 0.dp else 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header: Title + point counter + prev/next step buttons + close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .background(KronGold.copy(alpha = 0.16f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ShowChart,
                            contentDescription = null,
                            tint = KronGold,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Column {
                        Text(
                            "Inspeksi Titik Riwayat",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = KronGold,
                        )
                        Text(
                            "Titik ${index + 1} dari $totalCount data",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(
                        onClick = { onPrev?.invoke() },
                        enabled = onPrev != null,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                            contentDescription = "Titik sebelumnya",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { onNext?.invoke() },
                        enabled = onNext != null,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = "Titik berikutnya",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Close,
                            contentDescription = "Tutup inspeksi",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f))

            // Main Rate & Date
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "1 KRM = Rp ${idrFormatter.format(entry.rate.roundToInt())}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pointDateStr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Metrics row: Perubahan vs Titik Sebelumnya & Perubahan vs Awal Rentang
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    shape = KronSubCardShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "VS TITIK SEBELUMNYA",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (prevEntry != null) {
                            val sign = if (isStepPos) "+" else ""
                            Text(
                                text = "$sign${deltaFormatter.format(stepDeltaPercent)}% ($sign${idrFormatter.format(stepDelta.roundToInt())})",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = stepColor,
                            )
                        } else {
                            Text(
                                text = "Titik Awal",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                Surface(
                    shape = KronSubCardShape,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.45f),
                    border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            "VS AWAL RENTANG",
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = 9.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val sign = if (isCumPos) "+" else ""
                        Text(
                            text = "$sign${deltaFormatter.format(cumDeltaPercent)}% ($sign${idrFormatter.format(cumDelta.roundToInt())})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = cumColor,
                        )
                    }
                }
            }

            // Min/Max indicator tag if applicable
            if (entry.rate == maxRate && entry.rate != minRate) {
                Surface(
                    shape = KronPillShape,
                    color = KronGold.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, KronGold.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = "★ Nilai Tertinggi dalam rentang terpilih",
                        style = MaterialTheme.typography.labelSmall,
                        color = KronGold,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            } else if (entry.rate == minRate && entry.rate != maxRate) {
                Surface(
                    shape = KronPillShape,
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                    border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
                ) {
                    Text(
                        text = "▼ Nilai Terendah dalam rentang terpilih",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
