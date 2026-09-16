package com.morneven.kron

import com.morneven.kron.ui.components.MoneyExpressionEvaluator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneyExpressionEvaluatorTest {

    @Test
    fun testIsExpression() {
        assertFalse(MoneyExpressionEvaluator.isExpression("10000"))
        assertFalse(MoneyExpressionEvaluator.isExpression("10.000"))
        assertTrue(MoneyExpressionEvaluator.isExpression("10+10"))
        assertTrue(MoneyExpressionEvaluator.isExpression("50-10"))
        assertTrue(MoneyExpressionEvaluator.isExpression("5*2000"))
        assertTrue(MoneyExpressionEvaluator.isExpression("5x2000"))
        assertTrue(MoneyExpressionEvaluator.isExpression("5×2000"))
        assertTrue(MoneyExpressionEvaluator.isExpression("10000/2"))
        assertTrue(MoneyExpressionEvaluator.isExpression("10000÷2"))
    }

    @Test
    fun testBasicAdditionAndSubtraction() {
        assertEquals(20L, MoneyExpressionEvaluator.evaluate("10+10"))
        assertEquals(40L, MoneyExpressionEvaluator.evaluate("50-10"))
        assertEquals(35000L, MoneyExpressionEvaluator.evaluate("10000 + 25000"))
        assertEquals(85000L, MoneyExpressionEvaluator.evaluate("100000 - 15000"))
    }

    @Test
    fun testThousandSeparatorDots() {
        assertEquals(35000L, MoneyExpressionEvaluator.evaluate("10.000 + 25.000"))
        assertEquals(40000L, MoneyExpressionEvaluator.evaluate("50.000 - 10.000"))
    }

    @Test
    fun testMultiplicationAndDivision() {
        assertEquals(100000L, MoneyExpressionEvaluator.evaluate("5 * 20000"))
        assertEquals(100000L, MoneyExpressionEvaluator.evaluate("5x20000"))
        assertEquals(100000L, MoneyExpressionEvaluator.evaluate("5×20000"))
        assertEquals(50000L, MoneyExpressionEvaluator.evaluate("100000 / 2"))
        assertEquals(50000L, MoneyExpressionEvaluator.evaluate("100000 ÷ 2"))
    }

    @Test
    fun testPrecedenceAndParentheses() {
        assertEquals(70L, MoneyExpressionEvaluator.evaluate("10 + 20 * 3"))
        assertEquals(90L, MoneyExpressionEvaluator.evaluate("(10 + 20) * 3"))
    }

    @Test
    fun testDecimals() {
        assertEquals(25000L, MoneyExpressionEvaluator.evaluate("2.5 * 10000"))
        assertEquals(25000L, MoneyExpressionEvaluator.evaluate("2,5 * 10000"))
    }

    @Test
    fun testInvalidOrIncompleteExpressions() {
        assertNull(MoneyExpressionEvaluator.evaluate(""))
        assertNull(MoneyExpressionEvaluator.evaluate("   "))
        assertNull(MoneyExpressionEvaluator.evaluate("50 - "))
        assertNull(MoneyExpressionEvaluator.evaluate("10 + "))
        assertNull(MoneyExpressionEvaluator.evaluate("(10 + 20"))
        assertNull(MoneyExpressionEvaluator.evaluate("100 / 0"))
    }
}
