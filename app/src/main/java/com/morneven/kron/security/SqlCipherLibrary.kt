package com.morneven.kron.security

object SqlCipherLibrary {
    @Volatile
    private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            System.loadLibrary(LIBRARY_NAME)
            loaded = true
        }
    }

    internal fun isLoaded(): Boolean = loaded

    private const val LIBRARY_NAME = "sqlcipher"
}
