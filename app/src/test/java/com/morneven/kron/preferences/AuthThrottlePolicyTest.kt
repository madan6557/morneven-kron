package com.morneven.kron.preferences

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthThrottlePolicyTest {
    @Test
    fun fifthFailureStartsThirtySecondLockout() {
        assertEquals(0L, AuthThrottlePolicy.lockDurationMillis(4))
        assertEquals(30_000L, AuthThrottlePolicy.lockDurationMillis(5))
    }

    @Test
    fun repeatedFailuresIncreaseDelayUpToFiveMinutes() {
        assertEquals(60_000L, AuthThrottlePolicy.lockDurationMillis(6))
        assertEquals(120_000L, AuthThrottlePolicy.lockDurationMillis(7))
        assertEquals(240_000L, AuthThrottlePolicy.lockDurationMillis(8))
        assertEquals(300_000L, AuthThrottlePolicy.lockDurationMillis(9))
        assertEquals(300_000L, AuthThrottlePolicy.lockDurationMillis(20))
    }
}
