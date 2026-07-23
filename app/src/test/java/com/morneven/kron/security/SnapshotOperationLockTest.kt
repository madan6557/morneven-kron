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
    fun writesAreSerialized() = runBlocking {
        val lock = SnapshotOperationLock()
        val firstEntered = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val order = mutableListOf<String>()

        val first = async(Dispatchers.Default) {
            lock.withWriteLock {
                order += "first-start"
                firstEntered.countDown()
                check(releaseFirst.await(5, TimeUnit.SECONDS))
                order += "first-end"
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val secondEntered = CountDownLatch(1)
        val second = async(Dispatchers.Default) {
            lock.withWriteLock {
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
    fun readsCanRunConcurrently() = runBlocking {
        val lock = SnapshotOperationLock()
        val firstEntered = CountDownLatch(1)
        val order = mutableListOf<String>()

        val first = async(Dispatchers.Default) {
            lock.withReadLock {
                order += "first-start"
                firstEntered.countDown()
                Thread.sleep(200)
                order += "first-end"
            }
        }
        assertTrue(firstEntered.await(5, TimeUnit.SECONDS))

        val second = async(Dispatchers.Default) {
            lock.withReadLock {
                order += "second"
            }
        }
        second.await()
        first.await()
        assertTrue(order.contains("second"))
    }

    @Test
    fun writeWaitsForRead() = runBlocking {
        val lock = SnapshotOperationLock()
        val readEntered = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)

        val reader = async(Dispatchers.Default) {
            lock.withReadLock {
                readEntered.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(readEntered.await(5, TimeUnit.SECONDS))

        val writeEntered = CountDownLatch(1)
        val writer = async(Dispatchers.Default) {
            lock.withWriteLock {
                writeEntered.countDown()
            }
        }
        assertFalse(writeEntered.await(100, TimeUnit.MILLISECONDS))
        releaseRead.countDown()

        reader.await()
        assertTrue(writeEntered.await(5, TimeUnit.SECONDS))
        writer.await()
    }

    @Test
    fun failureAlwaysReleasesLock() = runBlocking {
        val lock = SnapshotOperationLock()
        runCatching {
            lock.withWriteLock<Unit> { error("expected") }
        }

        val result = lock.withWriteLock { "available" }
        assertEquals("available", result)
    }
}
