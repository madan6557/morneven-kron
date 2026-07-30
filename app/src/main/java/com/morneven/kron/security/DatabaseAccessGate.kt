package com.morneven.kron.security

import java.util.concurrent.atomic.AtomicBoolean

object DatabaseAccessGate {
    private val processReady = AtomicBoolean(false)

    fun markReady() {
        processReady.set(true)
    }

    fun markNotReady() {
        processReady.set(false)
    }

    fun isReady(): Boolean = processReady.get()
}
