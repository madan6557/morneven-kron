package com.morneven.kron.sync

import com.morneven.kron.data.SyncStateEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object SyncStateBridge {
    private val _syncState = MutableStateFlow<SyncStateEntity?>(null)
    val syncState: StateFlow<SyncStateEntity?> = _syncState

    fun emit(entity: SyncStateEntity) {
        _syncState.value = entity
    }
}