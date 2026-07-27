package com.morneven.kron.sharing.onetime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class EphemeralViewSession {
    private val _state = MutableStateFlow(CapsuleState.OFFER_RECEIVED)
    val state: StateFlow<CapsuleState> = _state.asStateFlow()

    private var capsule: ViewCapsule? = null
    private var plaintext: ByteArray? = null
    private var keyManager: DeviceBindingKeyManager? = null

    fun bindDevice(keyManager: DeviceBindingKeyManager, capsuleId: String) {
        this.keyManager = keyManager
        _state.value = CapsuleState.DEVICE_BOUND
    }

    fun capsuleDownloaded(capsule: ViewCapsule) {
        this.capsule = capsule
        _state.value = CapsuleState.CAPSULE_DOWNLOADED
    }

    fun arm() {
        _state.value = CapsuleState.ARMED
    }

    fun consume(capsuleId: String): ByteArray? {
        if (_state.value != CapsuleState.ARMED) return null
        _state.value = CapsuleState.CONSUMING

        val cap = capsule ?: return null
        val km = keyManager ?: return null

        val result = km.decryptWithContentKey(capsuleId, cap.encryptedProjection, cap.nonce)
        if (result != null) {
            plaintext = result
            _state.value = CapsuleState.CONSUMED
        }
        return result
    }

    fun close() {
        plaintext?.fill(0)
        plaintext = null
        capsule = null
        keyManager = null
        _state.value = CapsuleState.SESSION_CLOSED
    }

    fun clearSensitiveState() {
        plaintext?.fill(0)
        plaintext = null
    }
}
