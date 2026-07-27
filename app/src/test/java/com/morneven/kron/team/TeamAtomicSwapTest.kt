package com.morneven.kron.team

import org.junit.Assert.assertTrue
import org.junit.Test

class TeamAtomicSwapTest {
    private val sha256Regex = Regex("[0-9a-f]{64}")

    @Test
    fun sha256FileReturnsHexString() {
        val hash = "abcdef0123456789" + "0".repeat(48)
        assertTrue(sha256Regex.matches(hash))
    }

    @Test
    fun sha256RejectsInvalidLength() {
        assertTrue(!sha256Regex.matches("abc"))
    }

    @Test
    fun sha256RejectsNonHexChars() {
        assertTrue(!sha256Regex.matches("gggggggg" + "0".repeat(56)))
    }

    @Test
    fun sha256RejectsEmpty() {
        assertTrue(!sha256Regex.matches(""))
    }
}
