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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.KeyboardHide
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.automirrored.outlined.Backspace
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AddCard
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.ShoppingBag
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.School
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material.icons.outlined.Coffee
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.FitnessCenter
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.CardGiftcard
import androidx.compose.material.icons.outlined.FamilyRestroom
import androidx.compose.material.icons.outlined.Build
import com.morneven.kron.data.ActivityRow
import com.morneven.kron.data.CategoryEntity
import com.morneven.kron.ui.theme.KronCardShape
import com.morneven.kron.ui.theme.KronSubCardShape
import com.morneven.kron.ui.theme.KronPillShape
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.data.FundingChannel
import com.morneven.kron.ui.theme.KronBlue
import com.morneven.kron.ui.theme.KronGold
import com.morneven.kron.ui.theme.KronGreen
import com.morneven.kron.ui.theme.KronNegative
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

internal fun applyKeypadAction(
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
                    val targetDigits = text.substring(0, start).count(Char::isDigit)
                    val newCursor = offsetForDigitCount(formatted, targetDigits.coerceAtMost(formatted.count(Char::isDigit)))
                    TextFieldValue(formatted, TextRange(newCursor)) to raw
                }
            } else if (start > 0) {
                val before = text.substring(0, start)
                val after = text.substring(start)
                val charsToDelete = when {
                    before.endsWith(" + ") || before.endsWith(" - ") || before.endsWith(" × ") || before.endsWith(" ÷ ") -> 3
                    before.endsWith(".") && before.length > 1 -> 2
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
                    val targetDigits = newBefore.count(Char::isDigit)
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
                val rawBefore = text.substring(0, start).filter(Char::isDigit)
                val rawAfter = text.substring(end).filter(Char::isDigit)
                val newRaw = "$rawBefore$key$rawAfter"
                if (newRaw.isEmpty()) {
                    TextFieldValue("", TextRange.Zero) to ""
                } else {
                    val rawSanitized = sanitizeMoneyDigits(newRaw)
                    if (rawSanitized.toLongOrNull() != null) {
                        val formatted = formatMoneyInput(rawSanitized)
                        val targetDigits = rawBefore.length + key.count(Char::isDigit)
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
            // A real IME never takes focus from the field it is editing. Without this the keys are
            // focusable Surfaces, so pressing one blurs the text field, which makes it impossible to
            // tell "user pressed a key" apart from "user moved to another field".
            .focusProperties { canFocus = false }
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
    var hasFocus by remember { mutableStateOf(false) }
    // Deliberately not saved: it represents a user action in flight, not restorable state.
    var requestSystemKeyboard by remember { mutableStateOf(false) }

    LaunchedEffect(value) {
        val formatted = formatMoneyInput(value)
        if (field.text != formatted) field = TextFieldValue(formatted, TextRange(formatted.length))
    }

    val isExpr = MoneyExpressionEvaluator.isExpression(field.text)
    val evaluated = if (isExpr) MoneyExpressionEvaluator.evaluate(field.text) else null

    val view = androidx.compose.ui.platform.LocalView.current
    val imm = remember(view) {
        view.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
    }

    fun switchToSystemKeyboard() {
        useSystemKeyboard = true
        if (keypadHost != null) keypadHost.dismiss() else localShowCalculator = false
        requestSystemKeyboard = true
    }

    fun activateCalculator() {
        touched = true
        useSystemKeyboard = false
        requestSystemKeyboard = false
        focusRequester.requestFocus()
        keyboardController?.hide()
        if (keypadHost != null) {
            keypadHost.activeFieldId = fieldId
            keypadHost.label = label
            keypadHost.onKeyAction = { key ->
                val (newField, newVal) = applyKeypadAction(field, key, evaluated)
                field = newField
                onValue(newVal)
            }
            keypadHost.onSwitchToSystemKeyboard = {
                switchToSystemKeyboard()
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
                switchToSystemKeyboard()
            }
        }
    }

    // The interceptor keeps swallowing the IME until recomposition applies useSystemKeyboard, so the
    // request is made on the next frame rather than inline. Previously nothing asked for the
    // keyboard at all and it only appeared when Compose happened to restart the input session.
    LaunchedEffect(requestSystemKeyboard) {
        if (requestSystemKeyboard) {
            requestSystemKeyboard = false
            focusRequester.requestFocus()
            listOf(50L, 120L, 250L).forEach { delayMs ->
                delay(delayMs)
                keyboardController?.show()
                view.post {
                    imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }

    LaunchedEffect(isHostActive) {
        // The hasFocus guard matters once the keypad is released on blur: without it, moving to
        // another field dismisses the keypad and this effect then clears focus from the field the
        // user just tapped, closing the system keyboard they were trying to open.
        if (!isHostActive && !useSystemKeyboard && hasFocus) {
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
                    snapshotFlow { useSystemKeyboard }.collectLatest { systemActive ->
                        if (systemActive) {
                            coroutineScope.launch {
                                delay(60)
                                keyboardController?.show()
                                view.post {
                                    imm?.showSoftInput(view, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                                }
                            }
                            nextHandler.startInputMethod(request)
                        } else {
                            kotlinx.coroutines.awaitCancellation()
                        }
                    }
                    kotlinx.coroutines.awaitCancellation()
                }
            ) {
                androidx.compose.material3.OutlinedTextField(
                    value = field,
                    onValueChange = { candidate ->
                        touched = true
                        if (candidate.text == field.text) {
                            field = candidate
                            return@OutlinedTextField
                        }
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
                                    switchToSystemKeyboard()
                                }) {
                                    Icon(
                                        Icons.Outlined.Keyboard,
                                        contentDescription = "Gunakan keyboard sistem",
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onFocusChanged { focusState ->
                            hasFocus = focusState.isFocused
                            if (focusState.isFocused) {
                                if (!useSystemKeyboard && !isCustomActive) {
                                    keyboardController?.hide()
                                    activateCalculator()
                                } else if (useSystemKeyboard) {
                                    keyboardController?.show()
                                }
                            }
                            // Moving to any other input, including a plain text field with no
                            // keypad of its own, has to hand the keypad back. Nothing did this
                            // before, so the calculator stayed docked over the system keyboard.
                            if (!focusState.isFocused && keypadHost?.activeFieldId == fieldId) {
                                keypadHost.dismiss()
                            }
                        }
                        .pointerInput(isCustomActive, useSystemKeyboard) {
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Initial)
                                    if (event.type == PointerEventType.Press) {
                                        if (!useSystemKeyboard && !isCustomActive) {
                                            activateCalculator()
                                        } else if (useSystemKeyboard) {
                                            keyboardController?.show()
                                        }
                                    }
                                }
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
            val fallbackHost = remember { CalculatorKeypadHostState() }
            fallbackHost.activeFieldId = fieldId
            fallbackHost.label = label
            fallbackHost.onKeyAction = { key ->
                touched = true
                val (newField, newVal) = applyKeypadAction(field, key, evaluated)
                field = newField
                onValue(newVal)
            }
            fallbackHost.onSwitchToSystemKeyboard = {
                useSystemKeyboard = true
                localShowCalculator = false
                requestSystemKeyboard = true
            }

            LaunchedEffect(fallbackHost.activeFieldId) {
                if (fallbackHost.activeFieldId == null) {
                    localShowCalculator = false
                }
            }

            androidx.compose.ui.window.Popup(
                alignment = Alignment.BottomCenter,
                onDismissRequest = { localShowCalculator = false },
                properties = androidx.compose.ui.window.PopupProperties(
                    focusable = true,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = true,
                ),
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 16.dp,
                ) {
                    Column {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
                        CalculatorKeypadView(
                            host = fallbackHost,
                            modifier = Modifier.fillMaxWidth(),
                        )
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
    "DEBT_OPEN" -> "Hutang dibuat"
    "DEBT_PAYMENT" -> "Pembayaran hutang"
    "DEBT_ARCHIVE" -> "Hutang diarsipkan"
    "ATTACH_EVIDENCE" -> "Bukti dilampirkan"
    "EVIDENCE_KEY_ROTATION" -> "Rotasi kunci bukti"
    "IMPORT" -> "Impor data"
    "SYSTEM" -> "Aktivitas sistem"
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
    accent: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = KronCardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(0.8.dp, accent),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
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
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp)),
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
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        // Without this the block is centred but its wrapped lines are not, which reads as ragged.
        Text(
            description,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** Composable so the accent follows the active theme instead of the dark-tuned tone. */
@Composable
fun signedColor(value: Long): Color = when {
    value > 0 -> KronGreen
    value < 0 -> KronNegative
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

fun ActivityRow.primaryImpact(): Long = when {
    cashImpact != 0L -> cashImpact
    vaultImpact != 0L -> vaultImpact
    else -> budgetImpact
}

fun categoryVisualIcon(iconKey: String?, type: String? = null): ImageVector = when (iconKey?.lowercase()) {
    "payments", "salary" -> Icons.Outlined.Payments
    "add_card", "card" -> Icons.Outlined.AddCard
    "shopping_bag", "shopping" -> Icons.Outlined.ShoppingBag
    "restaurant", "food" -> Icons.Outlined.Restaurant
    "coffee", "cafe" -> Icons.Outlined.Coffee
    "directions_car", "transport" -> Icons.Outlined.DirectionsCar
    "receipt", "bill", "bills" -> Icons.AutoMirrored.Outlined.ReceiptLong
    "home", "house" -> Icons.Outlined.Home
    "health", "medical" -> Icons.Outlined.LocalHospital
    "movie", "entertainment" -> Icons.Outlined.Movie
    "school", "education" -> Icons.Outlined.School
    "work", "office" -> Icons.Outlined.WorkOutline
    "savings", "investment" -> Icons.Outlined.Savings
    "fitness_center", "sports" -> Icons.Outlined.FitnessCenter
    "volunteer_activism", "donation" -> Icons.Outlined.VolunteerActivism
    "pets", "pet" -> Icons.Outlined.Pets
    "flight", "travel" -> Icons.Outlined.Flight
    "card_giftcard", "gift" -> Icons.Outlined.CardGiftcard
    "family_restroom", "family" -> Icons.Outlined.FamilyRestroom
    "build", "maintenance" -> Icons.Outlined.Build
    "category" -> Icons.Outlined.Category
    else -> when (type) {
        "INCOME" -> Icons.Outlined.ArrowDownward
        "EXPENSE", "UNEXPECTED_EXPENSE" -> Icons.Outlined.ArrowUpward
        "TRANSFER", "CHANNEL_TRANSFER" -> Icons.Outlined.SwapHoriz
        "DEBT_OPEN", "DEBT_PAYMENT" -> Icons.Outlined.RequestQuote
        "AUTOMATION" -> Icons.Outlined.CalendarMonth
        "SYSTEM", "SECURITY" -> Icons.Outlined.Shield
        else -> Icons.Outlined.Category
    }
}

data class CategoryIconOption(
    val key: String,
    val label: String,
    val icon: ImageVector,
)

val availableCategoryIcons: List<CategoryIconOption> = listOf(
    CategoryIconOption("restaurant", "Makanan", Icons.Outlined.Restaurant),
    CategoryIconOption("coffee", "Kopi", Icons.Outlined.Coffee),
    CategoryIconOption("shopping_bag", "Belanja", Icons.Outlined.ShoppingBag),
    CategoryIconOption("directions_car", "Transport", Icons.Outlined.DirectionsCar),
    CategoryIconOption("receipt", "Tagihan", Icons.AutoMirrored.Outlined.ReceiptLong),
    CategoryIconOption("home", "Rumah", Icons.Outlined.Home),
    CategoryIconOption("health", "Kesehatan", Icons.Outlined.LocalHospital),
    CategoryIconOption("movie", "Hiburan", Icons.Outlined.Movie),
    CategoryIconOption("school", "Edukasi", Icons.Outlined.School),
    CategoryIconOption("work", "Kerja", Icons.Outlined.WorkOutline),
    CategoryIconOption("payments", "Gaji", Icons.Outlined.Payments),
    CategoryIconOption("savings", "Tabungan", Icons.Outlined.Savings),
    CategoryIconOption("add_card", "Kartu", Icons.Outlined.AddCard),
    CategoryIconOption("fitness_center", "Olahraga", Icons.Outlined.FitnessCenter),
    CategoryIconOption("volunteer_activism", "Donasi", Icons.Outlined.VolunteerActivism),
    CategoryIconOption("pets", "Hewan", Icons.Outlined.Pets),
    CategoryIconOption("flight", "Travel", Icons.Outlined.Flight),
    CategoryIconOption("card_giftcard", "Hadiah", Icons.Outlined.CardGiftcard),
    CategoryIconOption("family_restroom", "Keluarga", Icons.Outlined.FamilyRestroom),
    CategoryIconOption("build", "Servis", Icons.Outlined.Build),
    CategoryIconOption("category", "Lainnya", Icons.Outlined.Category),
)

@Composable
fun CategoryIconPicker(
    selectedKey: String,
    onSelectKey: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Pilih icon kategori",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val chunked = remember { availableCategoryIcons.chunked(7) }
        chunked.forEach { rowIcons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                rowIcons.forEach { opt ->
                    val isSelected = opt.key.equals(selectedKey, ignoreCase = true)
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clickable { onSelectKey(opt.key) },
                        shape = KronSubCardShape,
                        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        border = BorderStroke(
                            if (isSelected) 1.5.dp else 0.6.dp,
                            if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f),
                        ),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = opt.icon,
                                contentDescription = opt.label,
                                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                val emptySlots = 7 - rowIcons.size
                repeat(emptySlots) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

fun categoryVisualColor(colorLong: Long?, type: String? = null): Color {
    if (colorLong != null && colorLong != 0L) {
        return Color(colorLong)
    }
    return when (type) {
        "INCOME" -> Color(0xFF4CAF7D)
        "EXPENSE", "UNEXPECTED_EXPENSE" -> Color(0xFFB94D59)
        "TRANSFER", "CHANNEL_TRANSFER" -> Color(0xFF65A9EF)
        "DEBT_OPEN", "DEBT_PAYMENT" -> Color(0xFFE2BD4D)
        "AUTOMATION" -> Color(0xFF8F6CC1)
        else -> Color(0xFF8A8082)
    }
}

@Composable
fun CategoryAvatar(
    categoryName: String,
    iconKey: String? = null,
    colorLong: Long? = null,
    type: String? = null,
    size: androidx.compose.ui.unit.Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    val color = categoryVisualColor(colorLong, type)
    val icon = categoryVisualIcon(iconKey, type)
    Surface(
        modifier = modifier.size(size),
        shape = KronSubCardShape,
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(0.8.dp, color.copy(alpha = 0.35f)),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = categoryName,
                tint = color,
                modifier = Modifier.size(size * 0.52f),
            )
        }
    }
}

@Composable
fun ActivityStatusTag(activity: ActivityRow) {
    val (label, tint) = when {
        activity.reversedByEventId != null -> "BATAL" to MaterialTheme.colorScheme.error
        activity.type == "ARCHIVE" -> "ARSIP" to MaterialTheme.colorScheme.onSurfaceVariant
        activity.type == "RESTORE" || activity.type == "RESTORE_REVERSAL" -> "PULIH" to MaterialTheme.colorScheme.tertiary
        activity.source == "SYSTEM" -> "SISTEM" to MaterialTheme.colorScheme.onSurfaceVariant
        activity.source == "AUTOMATION" || activity.type == "AUTOMATION" -> "AUTO" to MaterialTheme.colorScheme.tertiary
        else -> return
    }
    Surface(
        color = tint.copy(alpha = 0.14f),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.6.dp, tint.copy(alpha = 0.35f)),
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = tint,
        )
    }
}

@Composable
fun TransactionCardItem(
    activity: ActivityRow,
    category: CategoryEntity? = null,
    valuesVisible: Boolean = true,
    channels: Set<String> = emptySet(),
    timestampText: String? = null,
    readOnly: Boolean = false,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val impact = activity.primaryImpact()
    val money = displayMoney(impact, valuesVisible)
    val reversed = activity.reversedByEventId != null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null && !readOnly) Modifier.clickable(onClick = onClick) else Modifier),
        shape = KronSubCardShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CategoryAvatar(
                    categoryName = category?.name ?: activity.title,
                    iconKey = category?.icon,
                    colorLong = category?.color,
                    type = activity.type,
                    size = 42.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = activity.title,
                        style = MaterialTheme.typography.titleMedium.copy(
                            textDecoration = if (reversed) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                        ),
                        color = if (reversed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (category != null && category.name != activity.title) "${category.name} · ${eventTypeLabel(activity.type)}" else eventTypeLabel(activity.type),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = money,
                    color = if (reversed) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else signedColor(impact),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                )
            }

            if (activity.note.isNotBlank()) {
                Text(
                    text = activity.note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 54.dp),
                )
            }

            if (channels.isNotEmpty() || reversed || timestampText != null) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 54.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false),
                    ) {
                        channels.sorted().forEach { ChannelBadge(it) }
                        ActivityStatusTag(activity)
                    }
                    if (timestampText != null) {
                        Text(
                            text = timestampText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun HeroBalanceCard(
    totalAssets: Long,
    cashAssets: Long,
    eBudgetAssets: Long,
    vaultCash: Long,
    vaultEBudget: Long,
    rollover: Long = 0L,
    visible: Boolean = true,
    accountName: String = "",
    onToggleValues: () -> Unit = {},
    onExactMoney: ((String, Long) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val totalAbs = kotlin.math.abs(totalAssets).toFloat().coerceAtLeast(1f)
    val cashRatio = (kotlin.math.abs(cashAssets).toFloat() / totalAbs * 100f).roundToInt().coerceIn(0, 100)
    val eBudgetRatio = (kotlin.math.abs(eBudgetAssets).toFloat() / totalAbs * 100f).roundToInt().coerceIn(0, 100)

    HudCard(
        modifier = modifier.fillMaxWidth(),
        accent = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "TOTAL ASET AKTIF",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                if (accountName.isNotBlank()) {
                    Surface(
                        shape = KronPillShape,
                        color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.12f),
                        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
                    ) {
                        Text(
                            accountName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = displayPrimaryHomeMoney(totalAssets, visible),
            modifier = Modifier.clickable(
                enabled = visible && shouldCompactPrimaryHomeMoney(totalAssets),
                onClick = { onExactMoney?.invoke("Total aset nyata", totalAssets) },
            ),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )

        Spacer(Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        enabled = visible && shouldCompactSecondaryHomeMoney(cashAssets),
                        onClick = { onExactMoney?.invoke("Total Cash", cashAssets) },
                    ),
                shape = KronSubCardShape,
                color = KronGold.copy(alpha = 0.08f),
                border = BorderStroke(0.8.dp, KronGold.copy(alpha = 0.35f)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(Icons.Outlined.Payments, contentDescription = null, tint = KronGold, modifier = Modifier.size(13.dp))
                            Text("CASH", style = MaterialTheme.typography.labelSmall, color = KronGold, fontWeight = FontWeight.Bold)
                        }
                        Text("$cashRatio%", style = MaterialTheme.typography.labelSmall, color = KronGold.copy(alpha = 0.75f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = displaySecondaryHomeMoney(cashAssets, visible),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = KronGold,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }

            Surface(
                modifier = Modifier
                    .weight(1f)
                    .clickable(
                        enabled = visible && shouldCompactSecondaryHomeMoney(eBudgetAssets),
                        onClick = { onExactMoney?.invoke("Total eBudget", eBudgetAssets) },
                    ),
                shape = KronSubCardShape,
                color = KronBlue.copy(alpha = 0.08f),
                border = BorderStroke(0.8.dp, KronBlue.copy(alpha = 0.35f)),
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(Icons.Outlined.AccountBalanceWallet, contentDescription = null, tint = KronBlue, modifier = Modifier.size(13.dp))
                            Text("EBUDGET", style = MaterialTheme.typography.labelSmall, color = KronBlue, fontWeight = FontWeight.Bold)
                        }
                        Text("$eBudgetRatio%", style = MaterialTheme.typography.labelSmall, color = KronBlue.copy(alpha = 0.75f))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = displaySecondaryHomeMoney(eBudgetAssets, visible),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = KronBlue,
                        maxLines = 1,
                        softWrap = false,
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = KronSubCardShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.5f),
            border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                        Text(
                            "Main Vault",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                    Text(
                        text = displaySecondaryHomeMoney(vaultCash + vaultEBudget, visible),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        softWrap = false,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "Cash:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            displaySecondaryHomeMoney(vaultCash, visible),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "eBudget:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            displaySecondaryHomeMoney(vaultEBudget, visible),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                if (rollover > 0 || !visible) {
                    HorizontalDivider(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f),
                        thickness = 0.6.dp,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Rollover",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            softWrap = false,
                        )
                        Text(
                            text = displaySecondaryHomeMoney(rollover, visible),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.tertiary,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionDock(
    onIncome: () -> Unit,
    onExpense: () -> Unit,
    onTransfer: () -> Unit,
    onDebt: () -> Unit,
    readOnly: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (readOnly) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        QuickActionButton(
            icon = Icons.Outlined.AddCard,
            label = "Pemasukan",
            color = KronGreen,
            onClick = onIncome,
            modifier = Modifier.weight(1f),
        )
        QuickActionButton(
            icon = Icons.Outlined.Payments,
            label = "Pengeluaran",
            color = MaterialTheme.colorScheme.primary,
            onClick = onExpense,
            modifier = Modifier.weight(1f),
        )
        QuickActionButton(
            icon = Icons.Outlined.SwapHoriz,
            label = "Transfer",
            color = KronBlue,
            onClick = onTransfer,
            modifier = Modifier.weight(1f),
        )
        QuickActionButton(
            icon = Icons.Outlined.RequestQuote,
            label = "Hutang",
            color = KronGold,
            onClick = onDebt,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
fun QuickActionButton(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clickable(onClick = onClick),
            shape = KronSubCardShape,
            color = color.copy(alpha = 0.12f),
            border = BorderStroke(0.8.dp, color.copy(alpha = 0.35f)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label, tint = color, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false,
        )
    }
}

@Composable
fun CashFlowCard(
    income: Long,
    expense: Long,
    visible: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val net = income - expense
    val totalFlow = (income + expense).toFloat().coerceAtLeast(1f)
    val incomeRatio = if (income + expense > 0) (income.toFloat() / totalFlow).coerceIn(0f, 1f) else 0.5f

    HudCard(
        modifier = modifier.fillMaxWidth(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "ARUS KAS BULAN INI",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                letterSpacing = 1.2.sp,
            )
            Surface(
                shape = KronPillShape,
                color = (if (net >= 0) KronGreen else MaterialTheme.colorScheme.error).copy(alpha = 0.12f),
                border = BorderStroke(0.8.dp, (if (net >= 0) KronGreen else MaterialTheme.colorScheme.error).copy(alpha = 0.35f)),
            ) {
                Text(
                    text = "Net: ${displayMoney(net, visible)}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = signedColor(net),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Inflow
            Surface(
                modifier = Modifier.weight(1f),
                shape = KronSubCardShape,
                color = KronGreen.copy(alpha = 0.08f),
                border = BorderStroke(0.8.dp, KronGreen.copy(alpha = 0.25f)),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = KronGreen.copy(alpha = 0.2f),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.ArrowDownward,
                                contentDescription = null,
                                tint = KronGreen,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Column {
                        Text("Masuk", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            displayMoney(income, visible),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = KronGreen,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }

            // Outflow
            Surface(
                modifier = Modifier.weight(1f),
                shape = KronSubCardShape,
                color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
                border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.25f)),
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.2f),
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Outlined.ArrowUpward,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                    Column {
                        Text("Keluar", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            displayMoney(expense, visible),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
        }

        if (income + expense > 0L) {
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(incomeRatio)
                        .height(6.dp)
                        .background(KronGreen),
                )
            }
        }
    }
}

