package com.morneven.kron.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotOperationLockTest {
    @Test
    fun operationsAreSerialized() = runBlocking {
        val lock = SnapshotOperationLock()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = mutableListOf<String>()

        val first = async(Dispatchers.Default) {
            lock.withLock {
                order += "first-start"
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                order += "first-end"
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val secondEntered = CountDownLatch(1)
        val second = async(Dispatchers.Default) {
            lock.withLock {
                order += "second"
                secondEntered.countDown()
            }
        }
        assertFalse(secondEntered.await(100, TimeUnit.MILLISECONDS))
        releaseFirst.countDown()

        first.await()
        second.await()
        assertTrue(secondEntered.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("first-start", "first-end", "second"), order)
    }

    @Test
    fun failureAlwaysReleasesLock() = runBlocking {
        val lock = SnapshotOperationLock()
        runCatching {
            lock.withLock<Unit> { error("expected") }
        }

        val result = lock.withLock { "available" }
        assertEquals("available", result)
    }
}
