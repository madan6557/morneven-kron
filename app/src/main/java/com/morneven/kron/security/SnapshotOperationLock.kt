package com.morneven.kron.security

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SnapshotOperationLock @Inject constructor() {
    private val mutex = Mutex()

    suspend fun <T> withReadLock(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        // Use a coroutine-friendly Mutex instead of a thread-bound ReadWriteLock.
        // This prevents IllegalMonitorStateException when suspending across threads.
        mutex.withLock { operation() }
    }

    suspend fun <T> withWriteLock(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { operation() }
    }

    suspend fun <T> withLock(operation: suspend () -> T): T = withWriteLock(operation)
}
