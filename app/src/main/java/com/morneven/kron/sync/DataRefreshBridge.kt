package com.morneven.kron.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object DataRefreshBridge {
    private val _refresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val refresh: SharedFlow<Unit> = _refresh.asSharedFlow()

    fun emit() {
        _refresh.tryEmit(Unit)
    }
}
