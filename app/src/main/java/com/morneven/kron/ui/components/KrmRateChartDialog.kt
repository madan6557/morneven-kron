package com.morneven.kron.ui.components

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.morneven.kron.ui.theme.KronButtonShape
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import com.morneven.kron.ui.theme.KronPillShape
import com.morneven.kron.ui.theme.KronSubCardShape
import com.morneven.kron.widget.KrmRateHistoryEntry
import com.morneven.kron.widget.KronCurrencyManager
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun KrmRateChartDialog(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var history by remember { mutableStateOf(KronCurrencyManager.getKrmRateHistory(context)) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

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

    val currentPoint = history.lastOrNull() ?: KrmRateHistoryEntry(System.currentTimeMillis(), KronCurrencyManager.KRM_BASE_IDR)
    val displayPoint = selectedIndex?.let { history.getOrNull(it) } ?: currentPoint

    val rates = history.map { it.rate }
    val minRate = rates.minOrNull() ?: currentPoint.rate
    val maxRate = rates.maxOrNull() ?: currentPoint.rate
    val avgRate = if (rates.isNotEmpty()) rates.average() else currentPoint.rate

    val initialRate = history.firstOrNull()?.rate ?: KronCurrencyManager.KRM_BASE_IDR
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
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                            "Fluktuasi Kurs KRON",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Mata uang Morneven (KRM) · Sintetis tanpa batas atas",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismissRequest) {
                    Icon(Icons.Outlined.Close, contentDescription = "Tutup dialog")
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

                            // Trend badge vs initial
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

                // Interactive Chart
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                                    "Ketuk lagi untuk reset pilihan",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = KronGold,
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
                            KrmLineChart(
                                history = history,
                                selectedIndex = selectedIndex,
                                onSelectIndex = { selectedIndex = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(180.dp)
                                    .padding(vertical = 12.dp, horizontal = 12.dp),
                            )
                        }
                    }
                }

                // Action buttons: simulate tick and reset
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = {
                                KronCurrencyManager.refreshKronFluctuation(context)
                                history = KronCurrencyManager.getKrmRateHistory(context)
                                selectedIndex = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = KronButtonShape,
                            colors = ButtonDefaults.buttonColors(containerColor = KronGold),
                        ) {
                            Icon(Icons.Outlined.AutoGraph, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Simulasikan Pasar", color = Color.Black, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                KronCurrencyManager.clearKrmRateHistory(context)
                                history = KronCurrencyManager.getKrmRateHistory(context)
                                selectedIndex = null
                            },
                            shape = KronButtonShape,
                        ) {
                            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Reset")
                        }
                    }
                }

                // Recent ticks table
                item {
                    Text(
                        "Riwayat Data Terakhir",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
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
    modifier: Modifier = Modifier,
) {
    val goldColor = KronGold
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
    val pointCount = history.size

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
            // Draw single horizontal point
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

            // Smooth cubic bezier segment
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

            // Vertical guideline
            drawLine(
                color = goldColor.copy(alpha = 0.6f),
                start = Offset(selX, padTop),
                end = Offset(selX, yBot),
                strokeWidth = 1.5.dp.toPx(),
                pathEffect = dashedEffect,
            )

            // Outer ring and center dot
            drawCircle(
                color = goldColor.copy(alpha = 0.3f),
                radius = 10.dp.toPx(),
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
        }
    }
}
