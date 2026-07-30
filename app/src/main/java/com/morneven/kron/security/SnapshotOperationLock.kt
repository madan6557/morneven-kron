package com.morneven.kron.security

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex

@Singleton
class SnapshotOperationLock @Inject constructor() {
    suspend fun <T> withLock(operation: suspend () -> T): T {
        mutex.lock()
        return try {
            operation()
        } finally {
            mutex.unlock()
        }
    }

    private companion object {
        val mutex = Mutex()
    }
}
