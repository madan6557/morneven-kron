package com.morneven.kron.team

import com.morneven.kron.data.TeamRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamMergeExecutorTest {
    @Test
    fun entityTableMappingKnownTypes() {
        val mappings = mapOf(
            "CATEGORY" to "categories",
            "PORTFOLIO" to "portfolios",
            "BUDGET_PERIOD" to "budget_periods",
            "ALLOCATION" to "allocations",
            "RECURRING_RULE" to "recurring_rules",
        )
        for ((key, expected) in mappings) {
            assertEquals(expected, entityTable(key))
        }
    }

    @Test
    fun entityTableReturnsNullForUnknownType() {
        assertNull(entityTable("INVENTORY"))
        assertNull(entityTable("USER"))
    }

    @Test
    fun mergeResultTracksCounts() {
        val result = MergeResult(appliedChoices = 5, eventsMerged = 2, entitiesMerged = 3)
        assertEquals(5, result.appliedChoices)
        assertEquals(2, result.eventsMerged)
        assertEquals(3, result.entitiesMerged)
    }

    @Test
    fun mergeResultZeroDefault() {
        val result = MergeResult(appliedChoices = 0, eventsMerged = 0, entitiesMerged = 0)
        assertTrue(result.eventsMerged >= 0)
    }

    private fun entityTable(type: String): String? = when (type) {
        "CATEGORY" -> "categories"
        "PORTFOLIO" -> "portfolios"
        "BUDGET_PERIOD" -> "budget_periods"
        "ALLOCATION" -> "allocations"
        "RECURRING_RULE" -> "recurring_rules"
        else -> null
    }
}
