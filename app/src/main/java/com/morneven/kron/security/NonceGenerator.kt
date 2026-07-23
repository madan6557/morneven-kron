package com.morneven.kron.security

import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

private val nonceCounter = AtomicLong(0)

fun generateNonce(size: Int = 12): ByteArray {
    val counter = nonceCounter.incrementAndGet()
    val nonce = ByteArray(size)
    for (i in 0..7) {
        nonce[i] = (counter shr (56 - i * 8)).toByte()
    }
    if (size > 8) {
        val randomPart = ByteArray(size - 8)
        SecureRandom().nextBytes(randomPart)
        randomPart.copyInto(nonce, 8)
    }
    return nonce
}
