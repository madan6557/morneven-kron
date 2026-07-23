package com.morneven.kron.security

import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class SnapshotOperationLock @Inject constructor() {
    private val rwLock = ReentrantReadWriteLock()

    suspend fun <T> withReadLock(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        rwLock.readLock().lock()
        try {
            operation()
        } finally {
            rwLock.readLock().unlock()
        }
    }

    suspend fun <T> withWriteLock(operation: suspend () -> T): T = withContext(Dispatchers.IO) {
        rwLock.writeLock().lock()
        try {
            operation()
        } finally {
            rwLock.writeLock().unlock()
        }
    }

    suspend fun <T> withLock(operation: suspend () -> T): T = withWriteLock(operation)
}
