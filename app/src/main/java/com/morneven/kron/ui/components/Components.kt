package com.morneven.kron.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.ui.theme.KronBlue
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

fun formatIdr(value: Long): String = "Rp " + NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).apply {
    maximumFractionDigits = 0
    minimumFractionDigits = 0
}.format(value)

fun sanitizeMoneyDigits(value: String): String = value.filter(Char::isDigit).trimStart('0')

fun parseMoneyInput(value: String): Long = sanitizeMoneyDigits(value).toLongOrNull() ?: 0L

fun formatMoneyInput(value: String): String {
    val digits = sanitizeMoneyDigits(value)
    if (digits.isEmpty()) return ""
    return digits.toLongOrNull()?.let {
        NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).format(it)
    } ?: ""
}

private fun digitCountBefore(value: String, offset: Int): Int = value.take(offset).count(Char::isDigit)

private fun offsetForDigitCount(value: String, digits: Int): Int {
    if (digits <= 0) return 0
    var seen = 0
    value.forEachIndexed { index, char ->
        if (char.isDigit()) {
            seen++
            if (seen == digits) return index + 1
        }
    }
    return value.length
}

/** Keeps the raw Rupiah digits in state while presenting grouped Indonesian digits. */
@Composable
fun MoneyField(value: String, onValue: (String) -> Unit, label: String) {
    var field by remember { mutableStateOf(TextFieldValue(formatMoneyInput(value))) }
    LaunchedEffect(value) {
        val formatted = formatMoneyInput(value)
        if (field.text != formatted) field = TextFieldValue(formatted, TextRange(formatted.length))
    }
    androidx.compose.material3.OutlinedTextField(
        value = field,
        onValueChange = { candidate ->
            val raw = sanitizeMoneyDigits(candidate.text)
            if (raw.isNotEmpty() && raw.toLongOrNull() == null) return@OutlinedTextField
            val digitsBeforeCursor = digitCountBefore(candidate.text, candidate.selection.start)
            val formatted = formatMoneyInput(raw)
            val cursor = offsetForDigitCount(formatted, digitsBeforeCursor.coerceAtMost(formatted.count(Char::isDigit)))
            field = TextFieldValue(formatted, TextRange(cursor))
            onValue(raw)
        },
        label = { Text(label) },
        prefix = { Text("Rp ") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}

fun displayMoney(value: Long, visible: Boolean): String = if (visible) formatIdr(value) else "Rp ••••••"

@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 4.dp, bottomStart = 18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(onClick = onAction) { Text(action) }
        }
    }
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)))
    Spacer(Modifier.height(12.dp))
}

@Composable
fun ChannelBadge(channel: String) {
    val cash = channel == FundingChannel.CASH
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = (if (cash) KronGold else KronBlue).copy(alpha = 0.15f),
        border = BorderStroke(1.dp, (if (cash) KronGold else KronBlue).copy(alpha = 0.45f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (cash) Icons.Outlined.Payments else Icons.Outlined.AccountBalanceWallet,
                contentDescription = null,
                tint = if (cash) KronGold else KronBlue,
                modifier = Modifier.height(13.dp),
            )
            Text(if (cash) "Cash" else "eBudget", style = MaterialTheme.typography.labelSmall, color = if (cash) KronGold else KronBlue)
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun BudgetProgress(booked: Long, available: Long, modifier: Modifier = Modifier) {
    val spent = booked - available
    val progress = if (booked <= 0) 0f else (spent.toFloat() / booked.toFloat()).coerceIn(0f, 1f)
    val color = when {
        available < 0 -> MaterialTheme.colorScheme.error
        progress >= 0.9f -> KronGold
        else -> KronGreen
    }
    LinearProgressIndicator(
        progress = { progress },
        modifier = modifier.fillMaxWidth().height(6.dp),
        color = color,
        trackColor = MaterialTheme.colorScheme.surfaceVariant,
    )
}

@Composable
fun EmptyState(icon: ImageVector, title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun signedColor(value: Long): Color = when {
    value > 0 -> KronGreen
    value < 0 -> Color(0xFFFF796E)
    else -> Color.Unspecified
}

fun compactIdr(value: Long): String {
    val abs = value.absoluteValue
    val suffix = when {
        abs >= 1_000_000_000 -> "M"
        abs >= 1_000_000 -> "jt"
        abs >= 1_000 -> "rb"
        else -> ""
    }
    val divisor = when (suffix) {
        "M" -> 1_000_000_000.0
        "jt" -> 1_000_000.0
        "rb" -> 1_000.0
        else -> 1.0
    }
    val number = if (divisor == 1.0) abs.toString() else String.format(Locale.forLanguageTag("id-ID"), "%.1f", abs / divisor).removeSuffix(",0")
    return (if (value < 0) "-" else "") + "Rp $number$suffix"
}
