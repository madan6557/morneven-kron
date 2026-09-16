package com.morneven.kron.ui.components

import java.util.Locale
import kotlin.math.roundToLong

/**
 * Evaluator for arithmetic expressions entered in money/nominal fields.
 * Supports +, -, *, /, x, X, ×, ÷, parentheses (), decimals (both . and ,),
 * and Indonesian thousand dots (e.g. 10.000 + 25.000).
 */
object MoneyExpressionEvaluator {
    private val OPERATORS = setOf('+', '-', '*', '/', 'x', 'X', '×', '÷')

    fun isExpression(input: String): Boolean = input.any { it in OPERATORS }

    fun sanitizeExpression(input: String): String {
        return input.filter { it.isDigit() || it in OPERATORS || it in setOf('(', ')', '.', ',', ' ') }
    }

    fun evaluate(expression: String): Long? {
        val trimmed = expression.trim()
        if (trimmed.isEmpty()) return null

        val normalized = normalize(trimmed)
        if (normalized.isEmpty()) return null

        return runCatching {
            val tokens = tokenize(normalized) ?: return null
            if (tokens.isEmpty()) return null
            val parser = ExpressionParser(tokens)
            val result = parser.parse() ?: return null
            if (parser.hasRemainingTokens()) return null
            if (result.isNaN() || result.isInfinite()) return null
            result.roundToLong()
        }.getOrNull()
    }

    private fun normalize(input: String): String {
        var str = input
            .replace('×', '*')
            .replace('x', '*')
            .replace('X', '*')
            .replace('÷', '/')
            .replace(':', '/')

        // Remove thousand separator dots: e.g. 10.000 -> 10000
        val thousandRegex = Regex("""(\d+)\.(\d{3})(?!\d)""")
        while (thousandRegex.containsMatchIn(str)) {
            str = thousandRegex.replace(str, "$1$2")
        }

        // Replace comma decimal with dot: e.g. 10,5 -> 10.5
        str = str.replace(',', '.')

        return str
    }

    private sealed interface Token {
        data class Number(val value: Double) : Token
        data class Op(val char: Char) : Token
    }

    private fun tokenize(expr: String): List<Token>? {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c.isWhitespace()) {
                i++
                continue
            }
            if (c.isDigit() || c == '.') {
                val start = i
                var hasDot = c == '.'
                i++
                while (i < expr.length && (expr[i].isDigit() || (!hasDot && expr[i] == '.'))) {
                    if (expr[i] == '.') hasDot = true
                    i++
                }
                val numStr = expr.substring(start, i)
                val num = numStr.toDoubleOrNull() ?: return null
                tokens.add(Token.Number(num))
            } else if (c in setOf('+', '-', '*', '/', '(', ')')) {
                tokens.add(Token.Op(c))
                i++
            } else {
                return null
            }
        }
        return tokens
    }

    private class ExpressionParser(private val tokens: List<Token>) {
        private var pos = 0

        fun hasRemainingTokens(): Boolean = pos < tokens.size

        fun parse(): Double? {
            val res = parseExpression()
            return res
        }

        private fun parseExpression(): Double? {
            var left = parseTerm() ?: return null
            while (pos < tokens.size) {
                val token = tokens[pos]
                if (token is Token.Op && (token.char == '+' || token.char == '-')) {
                    pos++
                    val right = parseTerm() ?: return null
                    left = if (token.char == '+') left + right else left - right
                } else {
                    break
                }
            }
            return left
        }

        private fun parseTerm(): Double? {
            var left = parseFactor() ?: return null
            while (pos < tokens.size) {
                val token = tokens[pos]
                if (token is Token.Op && (token.char == '*' || token.char == '/')) {
                    pos++
                    val right = parseFactor() ?: return null
                    if (token.char == '*') {
                        left *= right
                    } else {
                        if (right == 0.0) return null
                        left /= right
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parseFactor(): Double? {
            if (pos >= tokens.size) return null
            val token = tokens[pos]

            if (token is Token.Op && token.char == '+') {
                pos++
                return parseFactor()
            }
            if (token is Token.Op && token.char == '-') {
                pos++
                val next = parseFactor() ?: return null
                return -next
            }
            if (token is Token.Op && token.char == '(') {
                pos++
                val inner = parseExpression() ?: return null
                if (pos >= tokens.size || tokens[pos] != Token.Op(')')) return null
                pos++
                return inner
            }
            if (token is Token.Number) {
                pos++
                return token.value
            }
            return null
        }
    }
}
