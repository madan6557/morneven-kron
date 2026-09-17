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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.absoluteValue

fun formatIdr(value: Long): String = "Rp " + NumberFormat.getIntegerInstance(Locale.forLanguageTag("id-ID")).apply {
    maximumFractionDigits = 0
    minimumFractionDigits = 0
}.format(value)

fun sanitizeMoneyDigits(value: String): String = value.filter(Char::isDigit).trimStart('0')

fun parseMoneyInput(value: String): Long {
    if (MoneyExpressionEvaluator.isExpression(value)) {
        val evaluated = MoneyExpressionEvaluator.evaluate(value)
        if (evaluated != null) return evaluated.coerceAtLeast(0L)
    }
    return sanitizeMoneyDigits(value).toLongOrNull() ?: 0L
}

fun formatMoneyInput(value: String): String {
    if (MoneyExpressionEvaluator.isExpression(value)) {
        return MoneyExpressionEvaluator.sanitizeExpression(value)
    }
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

@Composable
fun kronTextFieldColors(isCustomFocused: Boolean = false) = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = if (isCustomFocused) KronGold else MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = if (isCustomFocused) KronGold else MaterialTheme.colorScheme.outline.copy(alpha = 0.28f),
    focusedLabelColor = if (isCustomFocused) KronGold else MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = if (isCustomFocused) KronGold else MaterialTheme.colorScheme.onSurfaceVariant,
    focusedContainerColor = if (isCustomFocused) KronGold.copy(alpha = 0.08f) else Color.Transparent,
    unfocusedContainerColor = if (isCustomFocused) KronGold.copy(alpha = 0.08f) else Color.Transparent,
    cursorColor = KronGold,
    focusedPrefixColor = if (isCustomFocused) KronGold else MaterialTheme.colorScheme.primary,
    unfocusedPrefixColor = if (isCustomFocused) KronGold else MaterialTheme.colorScheme.onSurfaceVariant,
)

private fun applyKeypadAction(
    currentField: TextFieldValue,
    key: String,
    evaluated: Long?,
): Pair<TextFieldValue, String> {
    val text = currentField.text
    val start = currentField.selection.min.coerceIn(0, text.length)
    val end = currentField.selection.max.coerceIn(0, text.length)

    return when (key) {
        "C" -> {
            TextFieldValue("", TextRange.Zero) to ""
        }
        "⌫" -> {
            if (start != end) {
                val newText = text.removeRange(start, end)
                if (MoneyExpressionEvaluator.isExpression(newText)) {
                    val sanitized = MoneyExpressionEvaluator.sanitizeExpression(newText)
                    TextFieldValue(sanitized, TextRange(start.coerceAtMost(sanitized.length))) to sanitized
                } else {
                    val raw = sanitizeMoneyDigits(newText)
                    val formatted = formatMoneyInput(raw)
                    val targetDigits = sanitizeMoneyDigits(text.substring(0, start)).length
                    val newCursor = offsetForDigitCount(formatted, targetDigits.coerceAtMost(formatted.count(Char::isDigit)))
                    TextFieldValue(formatted, TextRange(newCursor)) to raw
                }
            } else if (start > 0) {
                val before = text.substring(0, start)
                val after = text.substring(start)
                val charsToDelete = when {
                    before.endsWith(" + ") || before.endsWith(" - ") || before.endsWith(" × ") || before.endsWith(" ÷ ") -> 3
                    else -> 1
                }
                val newBefore = before.dropLast(charsToDelete)
                val newText = newBefore + after
                if (MoneyExpressionEvaluator.isExpression(newText)) {
                    val sanitized = MoneyExpressionEvaluator.sanitizeExpression(newText)
                    val newCursor = (start - charsToDelete).coerceIn(0, sanitized.length)
                    TextFieldValue(sanitized, TextRange(newCursor)) to sanitized
                } else {
                    val raw = sanitizeMoneyDigits(newText)
                    val formatted = formatMoneyInput(raw)
                    val targetDigits = sanitizeMoneyDigits(newBefore).length
                    val newCursor = offsetForDigitCount(formatted, targetDigits.coerceAtMost(formatted.count(Char::isDigit)))
                    TextFieldValue(formatted, TextRange(newCursor)) to raw
                }
            } else {
                currentField to (if (MoneyExpressionEvaluator.isExpression(text)) text else sanitizeMoneyDigits(text))
            }
        }
        "=" -> {
            if (evaluated != null) {
                val resStr = evaluated.toString()
                val formatted = formatMoneyInput(resStr)
                TextFieldValue(formatted, TextRange(formatted.length)) to resStr
            } else {
                currentField to (if (MoneyExpressionEvaluator.isExpression(text)) text else sanitizeMoneyDigits(text))
            }
        }
        "+", "−", "×", "÷" -> {
            val opStr = when (key) {
                "+" -> " + "
                "−" -> " - "
                "×" -> " × "
                "÷" -> " ÷ "
                else -> " "
            }
            val before = text.substring(0, start).trimEnd()
            val after = text.substring(end).trimStart()
            val prefix = if (before.isEmpty()) "0" else before
            val newText = if (after.isEmpty()) "$prefix$opStr" else "$prefix$opStr$after"
            val newCursor = prefix.length + opStr.length
            TextFieldValue(newText, TextRange(newCursor)) to newText
        }
        "(", ")" -> {
            val before = text.substring(0, start)
            val after = text.substring(end)
            val newText = "$before$key$after"
            val newCursor = start + key.length
            TextFieldValue(newText, TextRange(newCursor)) to newText
        }
        else -> { // Digits "0".."9", "000"
            val isCurrentExpr = MoneyExpressionEvaluator.isExpression(text)
            if (isCurrentExpr) {
                val before = text.substring(0, start)
                val after = text.substring(end)
                val newText = "$before$key$after"
                val newCursor = start + key.length
                TextFieldValue(newText, TextRange(newCursor)) to newText
            } else {
                val rawBefore = sanitizeMoneyDigits(text.substring(0, start))
                val rawAfter = sanitizeMoneyDigits(text.substring(end))
                val newRaw = "$rawBefore$key$rawAfter"
                if (newRaw.isEmpty()) {
                    TextFieldValue("", TextRange.Zero) to ""
                } else {
                    val rawSanitized = sanitizeMoneyDigits(newRaw)
                    if (rawSanitized.toLongOrNull() != null) {
                        val formatted = formatMoneyInput(rawSanitized)
                        val targetDigits = rawBefore.length + key.length
                        val newCursor = offsetForDigitCount(formatted, targetDigits.coerceAtMost(formatted.count(Char::isDigit)))
                        TextFieldValue(formatted, TextRange(newCursor)) to rawSanitized
                    } else {
                        currentField to sanitizeMoneyDigits(text)
                    }
                }
            }
        }
    }
}

class CalculatorKeypadHostState {
    var activeFieldId by mutableStateOf<String?>(null)
    var label by mutableStateOf("")
    var onKeyAction by mutableStateOf<((String) -> Unit)?>(null)
    var onSwitchToSystemKeyboard by mutableStateOf<(() -> Unit)?>(null)

    val isVisible: Boolean get() = activeFieldId != null

    fun dismiss() {
        activeFieldId = null
    }
}

val LocalCalculatorKeypadHost = compositionLocalOf<CalculatorKeypadHostState?> { null }

@Composable
fun CalculatorKeypadView(
    host: CalculatorKeypadHostState,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Header bar / accessory strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(1f, fill = false),
            ) {
                Icon(
                    Icons.Outlined.Calculate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.height(18.dp),
                )
                Text(
                    if (host.label.isNotBlank()) "Kalkulator · ${host.label}" else "Keypad Kalkulator KRON",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Surface(
                    onClick = { host.onSwitchToSystemKeyboard?.invoke() },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            Icons.Outlined.Keyboard,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            "Sistem",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                Surface(
                    onClick = { host.dismiss() },
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                ) {
                    Text(
                        "Selesai",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }

        val rows = listOf(
            listOf("7", "8", "9", "÷", "C"),
            listOf("4", "5", "6", "×", "("),
            listOf("1", "2", "3", "−", ")"),
            listOf("0", "000", "=", "+", "⌫"),
        )

        rows.forEach { rowKeys ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rowKeys.forEach { key ->
                    val isOperator = key in listOf("+", "−", "×", "÷", "(", ")")
                    val isAction = key in listOf("C", "⌫", "=")
                    val isEquals = key == "="
                    val isClear = key == "C"
                    val isBackspace = key == "⌫"

                    val bg = when {
                        isClear -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                        isOperator || isEquals -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                        isAction -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                    }

                    val border = when {
                        isClear -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                        isOperator || isEquals -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                        else -> null
                    }

                    val textColor = when {
                        isClear -> MaterialTheme.colorScheme.error
                        isOperator || isEquals -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }

                    Surface(
                        onClick = { host.onKeyAction?.invoke(key) },
                        shape = RoundedCornerShape(10.dp),
                        color = bg,
                        border = border,
                        tonalElevation = 2.dp,
                        shadowElevation = 1.dp,
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            if (isBackspace) {
                                Icon(
                                    Icons.AutoMirrored.Outlined.Backspace,
                                    contentDescription = "Hapus",
                                    tint = textColor,
                                    modifier = Modifier.size(22.dp),
                                )
                            } else {
                                Text(
                                    text = key,
                                    style = if (key == "000") {
                                        MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                    } else if (isOperator || isEquals) {
                                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                    } else {
                                        MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                                    },
                                    color = textColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Keeps the raw Rupiah digits or arithmetic expression in state while presenting grouped Indonesian digits. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun MoneyField(value: String, onValue: (String) -> Unit, label: String) {
    var field by remember { mutableStateOf(TextFieldValue(formatMoneyInput(value))) }
    var touched by rememberSaveable { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    var useSystemKeyboard by rememberSaveable { mutableStateOf(false) }
    val keypadHost = LocalCalculatorKeypadHost.current
    val fieldId = rememberSaveable { java.util.UUID.randomUUID().toString() }
    val isHostActive = keypadHost?.activeFieldId == fieldId
    val bringIntoViewRequester = remember { BringIntoViewRequester() }
    val coroutineScope = rememberCoroutineScope()
    var localShowCalculator by rememberSaveable { mutableStateOf(false) }
    val isCustomActive = isHostActive || localShowCalculator
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current

    LaunchedEffect(value) {
        val formatted = formatMoneyInput(value)
        if (field.text != formatted) field = TextFieldValue(formatted, TextRange(formatted.length))
    }

    val isExpr = MoneyExpressionEvaluator.isExpression(field.text)
    val evaluated = if (isExpr) MoneyExpressionEvaluator.evaluate(field.text) else null

    fun activateCalculator() {
        touched = true
        useSystemKeyboard = false
        keyboardController?.hide()
        focusRequester.requestFocus()
        if (keypadHost != null) {
            keypadHost.activeFieldId = fieldId
            keypadHost.label = label
            keypadHost.onKeyAction = { key ->
                val (newField, newVal) = applyKeypadAction(field, key, evaluated)
                field = newField
                onValue(newVal)
            }
            keypadHost.onSwitchToSystemKeyboard = {
                useSystemKeyboard = true
                keypadHost.dismiss()
            }
            coroutineScope.launch {
                delay(150)
                bringIntoViewRequester.bringIntoView()
            }
        } else {
            localShowCalculator = true
        }
    }

    LaunchedEffect(field, evaluated, isHostActive) {
        if (isHostActive) {
            keypadHost.label = label
            keypadHost.onKeyAction = { key ->
                val (newField, newVal) = applyKeypadAction(field, key, evaluated)
                field = newField
                onValue(newVal)
            }
            keypadHost.onSwitchToSystemKeyboard = {
                useSystemKeyboard = true
                keypadHost.dismiss()
            }
        }
    }

    LaunchedEffect(isHostActive) {
        if (!isHostActive && !useSystemKeyboard) {
            focusManager.clearFocus()
        } else if (isHostActive) {
            focusRequester.requestFocus()
            delay(120)
            bringIntoViewRequester.bringIntoView()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .bringIntoViewRequester(bringIntoViewRequester)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            @OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
            androidx.compose.ui.platform.InterceptPlatformTextInput(
                interceptor = { request, nextHandler ->
                    if (!useSystemKeyboard) {
                        kotlinx.coroutines.awaitCancellation()
                    } else {
                        nextHandler.startInputMethod(request)
                    }
                }
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = field,
                    onValueChange = { candidate ->
                        touched = true
                        if (MoneyExpressionEvaluator.isExpression(candidate.text)) {
                            val sanitized = MoneyExpressionEvaluator.sanitizeExpression(candidate.text)
                            field = TextFieldValue(sanitized, candidate.selection)
                            onValue(sanitized)
                        } else {
                            val raw = sanitizeMoneyDigits(candidate.text)
                            if (raw.isNotEmpty() && raw.toLongOrNull() == null) return@OutlinedTextField
                            val digitsBeforeCursor = digitCountBefore(candidate.text, candidate.selection.start)
                            val formatted = formatMoneyInput(raw)
                            val cursor = offsetForDigitCount(formatted, digitsBeforeCursor.coerceAtMost(formatted.count(Char::isDigit)))
                            field = TextFieldValue(formatted, TextRange(cursor))
                            onValue(raw)
                        }
                    },
                    label = { Text(label) },
                    prefix = { Text("Rp ", style = MaterialTheme.typography.labelLarge, color = if (isCustomActive) KronGold else MaterialTheme.colorScheme.onSurfaceVariant) },
                    readOnly = false,
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isExpr && evaluated != null) {
                                IconButton(onClick = {
                                    val resStr = evaluated.toString()
                                    val formatted = formatMoneyInput(resStr)
                                    field = TextFieldValue(formatted, TextRange(formatted.length))
                                    onValue(resStr)
                                }) {
                                    Icon(Icons.Outlined.Check, contentDescription = "Terapkan hasil hitung", tint = KronGreen)
                                }
                            }
                            if (useSystemKeyboard) {
                                IconButton(onClick = {
                                    activateCalculator()
                                }) {
                                    Icon(
                                        Icons.Outlined.Calculate,
                                        contentDescription = "Gunakan keypad kalkulator",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            } else {
                                IconButton(onClick = {
                                    if (isHostActive || localShowCalculator) {
                                        if (keypadHost != null) keypadHost.dismiss() else localShowCalculator = false
                                    } else {
                                        activateCalculator()
                                    }
                                }) {
                                    Icon(
                                        if (isHostActive || localShowCalculator) Icons.Outlined.KeyboardHide else Icons.Outlined.Calculate,
                                        contentDescription = if (isHostActive || localShowCalculator) "Tutup kalkulator" else "Buka keypad kalkulator",
                                        tint = if (isHostActive || localShowCalculator) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused && !useSystemKeyboard) {
                                keyboardController?.hide()
                            }
                        },
                    singleLine = true,
                    shape = com.morneven.kron.ui.theme.KronFieldShape,
                    colors = kronTextFieldColors(isCustomFocused = isCustomActive),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = touched && !isCustomActive && parseMoneyInput(value) <= 0,
                    supportingText = {
                        if (isExpr) {
                            if (evaluated != null) {
                                Text(
                                    text = "= " + formatIdr(evaluated),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = KronGreen,
                                )
                            } else {
                                Text(
                                    text = "Menghitung...",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (touched && !isCustomActive && parseMoneyInput(value) <= 0) {
                            Text("Nominal harus lebih dari nol")
                        }
                    },
                )
            }

            if (!useSystemKeyboard) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .padding(end = if (isExpr && evaluated != null) 96.dp else 52.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            activateCalculator()
                        }
                )
            }
        }

        if (useSystemKeyboard) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ops = listOf(
                    "+" to "+",
                    "−" to "−",
                    "×" to "×",
                    "÷" to "÷",
                    "(" to "(",
                    ")" to ")",
                    "000" to "000",
                )
                ops.forEach { (symbol, keyToApply) ->
                    Surface(
                        onClick = {
                            touched = true
                            val (newField, newVal) = applyKeypadAction(field, keyToApply, evaluated)
                            field = newField
                            onValue(newVal)
                        },
                        shape = RoundedCornerShape(6.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.weight(1f).height(30.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(symbol, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Surface(
                    onClick = {
                        if (evaluated != null) {
                            val (newField, newVal) = applyKeypadAction(field, "=", evaluated)
                            field = newField
                            onValue(newVal)
                        }
                    },
                    enabled = evaluated != null,
                    shape = RoundedCornerShape(6.dp),
                    color = if (evaluated != null) KronGreen.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.weight(1f).height(30.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("=", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = if (evaluated != null) KronGreen else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
                    }
                }
            }
        } else if (keypadHost == null && localShowCalculator) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Keypad Kalkulator KRON",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            onClick = {
                                useSystemKeyboard = true
                                localShowCalculator = false
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Text(
                                "Keyboard Sistem",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                        Surface(
                            onClick = { localShowCalculator = false },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        ) {
                            Text(
                                "Tutup",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
                val rows = listOf(
                    listOf("7", "8", "9", "÷", "C"),
                    listOf("4", "5", "6", "×", "("),
                    listOf("1", "2", "3", "−", ")"),
                    listOf("0", "000", "=", "+", "⌫"),
                )
                rows.forEach { rowKeys ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowKeys.forEach { key ->
                            val isOperator = key in listOf("+", "−", "×", "÷", "(", ")")
                            val isAction = key in listOf("C", "⌫", "=")
                            val isEquals = key == "="
                            val isClear = key == "C"
                            val isBackspace = key == "⌫"

                            val bg = when {
                                isClear -> MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                                isOperator || isEquals -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                                isAction -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                            }

                            val border = when {
                                isClear -> BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                                isOperator || isEquals -> BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
                                else -> null
                            }

                            val textColor = when {
                                isClear -> MaterialTheme.colorScheme.error
                                isOperator || isEquals -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurface
                            }

                            Surface(
                                onClick = {
                                    touched = true
                                    val (newField, newVal) = applyKeypadAction(field, key, evaluated)
                                    field = newField
                                    onValue(newVal)
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = bg,
                                border = border,
                                tonalElevation = 2.dp,
                                shadowElevation = 1.dp,
                                modifier = Modifier.weight(1f).height(52.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    if (isBackspace) {
                                        Icon(
                                            Icons.AutoMirrored.Outlined.Backspace,
                                            contentDescription = "Hapus",
                                            tint = textColor,
                                            modifier = Modifier.size(22.dp),
                                        )
                                    } else {
                                        Text(
                                            text = key,
                                            style = if (key == "000") {
                                                MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                                            } else if (isOperator || isEquals) {
                                                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                                            } else {
                                                MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold)
                                            },
                                            color = textColor,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val MONEY_MASK = "Rp ••••••"

fun displayMoney(value: Long, visible: Boolean): String = if (visible) formatIdr(value) else MONEY_MASK

fun eventTypeLabel(type: String): String = when (type) {
    "INCOME" -> "Pemasukan"
    "EXPENSE" -> "Pengeluaran"
    "UNEXPECTED_EXPENSE" -> "Pengeluaran tak terduga"
    "TRANSFER" -> "Transfer antar akun"
    "CHANNEL_TRANSFER" -> "Transfer antar kanal"
    "OPENING_BALANCE" -> "Saldo awal"
    "PORTFOLIO_BOOKING" -> "Booking budget"
    "REALLOCATION" -> "Realokasi budget"
    "OVERBUDGET_COVERAGE" -> "Penutupan overbudget"
    "RELEASE" -> "Pelepasan budget"
    "ROLLOVER" -> "Rollover"
    "AUTOMATION" -> "Transaksi otomatis"
    "REVERSAL" -> "Reversal"
    "ARCHIVE" -> "Arsip"
    "RESTORE" -> "Pemulihan"
    "RESTORE_REVERSAL" -> "Pemulihan reversal"
    "CORRECTION" -> "Koreksi jurnal"
    else -> type.replace('_', ' ').lowercase().replaceFirstChar { it.titlecase() }
}

fun shouldCompactPrimaryHomeMoney(value: Long): Boolean = BigDecimal.valueOf(value).abs() >= BigDecimal.valueOf(1_000_000L)

fun shouldCompactSecondaryHomeMoney(value: Long): Boolean = BigDecimal.valueOf(value).abs() >= BigDecimal.valueOf(1_000_000_000L)

fun displayPrimaryHomeMoney(value: Long, visible: Boolean): String = when {
    !visible -> MONEY_MASK
    shouldCompactPrimaryHomeMoney(value) -> wordIdr(value)
    else -> formatIdr(value)
}

fun displaySecondaryHomeMoney(value: Long, visible: Boolean): String = when {
    !visible -> MONEY_MASK
    shouldCompactSecondaryHomeMoney(value) -> wordIdr(value)
    else -> formatIdr(value)
}

fun wordIdr(value: Long): String {
    val absolute = BigDecimal.valueOf(value).abs()
    val (divisor, unit) = when {
        absolute >= BigDecimal("1000000000000000000") -> BigDecimal("1000000000000000000") to "kuintiliun"
        absolute >= BigDecimal("1000000000000000") -> BigDecimal("1000000000000000") to "kuadriliun"
        absolute >= BigDecimal("1000000000000") -> BigDecimal("1000000000000") to "triliun"
        absolute >= BigDecimal("1000000000") -> BigDecimal("1000000000") to "miliar"
        else -> BigDecimal("1000000") to "juta"
    }
    val number = absolute.divide(divisor, 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
    return "Rp ${if (value < 0) "-" else ""}$number $unit"
}

@Composable
fun HudCard(
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f),
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(0.8.dp, accent),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp), content = content)
    }
}

@Composable
fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
        if (action != null && onAction != null) {
            androidx.compose.material3.TextButton(
                onClick = onAction,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp),
            ) { Text(action, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
fun ChannelBadge(channel: String) {
    val cash = channel == FundingChannel.CASH
    val color = if (cash) KronGold else KronBlue
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(0.7.dp, color.copy(alpha = 0.35f)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Icon(
                imageVector = if (cash) Icons.Outlined.Payments else Icons.Outlined.AccountBalanceWallet,
                contentDescription = null,
                tint = color,
                modifier = Modifier.height(11.dp),
            )
            Text(if (cash) "Cash" else "eBudget", style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
fun Metric(label: String, value: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false)
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
        modifier = modifier.fillMaxWidth().height(4.dp),
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
        abs >= 1_000_000_000_000_000 -> "kuad"
        abs >= 1_000_000_000_000 -> "T"
        abs >= 1_000_000_000 -> "M"
        abs >= 1_000_000 -> "jt"
        abs >= 1_000 -> "rb"
        else -> ""
    }
    val divisor = when (suffix) {
        "kuad" -> 1_000_000_000_000_000.0
        "T" -> 1_000_000_000_000.0
        "M" -> 1_000_000_000.0
        "jt" -> 1_000_000.0
        "rb" -> 1_000.0
        else -> 1.0
    }
    val number = if (divisor == 1.0) abs.toString() else String.format(Locale.forLanguageTag("id-ID"), "%.1f", abs / divisor).removeSuffix(",0")
    return (if (value < 0) "-" else "") + "Rp $number$suffix"
}
