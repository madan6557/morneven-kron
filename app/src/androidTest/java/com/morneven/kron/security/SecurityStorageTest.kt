package com.morneven.kron.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecurityStorageTest {
    @Test
    fun databasePassphraseIsStableAndNotStoredAsPlaintext() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = DatabaseKeyManager(context)
        val first = manager.getOrCreateDatabasePassphrase()
        val second = manager.getOrCreateDatabasePassphrase()
        try {
            assertArrayEquals(first, second)
            assertTrue(manager.isProvisioned())
            val envelope = context.noBackupFilesDir.resolve("security/database-key-v1.bin").readBytes()
            assertFalse(envelope.asList().windowed(first.size).any { it.toByteArray().contentEquals(first) })
        } finally {
            first.fill(0)
            second.fill(0)
        }
    }

    @Test
    fun attachmentRoundTripUsesEncryptedStorage() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = EncryptedAttachmentStore(context, DatabaseKeyManager(context))
        val plain = "bukti finansial rahasia".toByteArray()
        val stored = plain.inputStream().use { store.encrypt(it, "test_attachment_123") }
        try {
            assertFalse(stored.file.readBytes().containsSubsequence(plain))
            val restored = ByteArrayOutputStream()
            store.decrypt(stored.file, restored)
            assertArrayEquals(plain, restored.toByteArray())
            assertTrue(stored.sha256.matches(Regex("[0-9a-f]{64}")))
        } finally {
            stored.file.delete()
        }
    }

    private fun ByteArray.containsSubsequence(needle: ByteArray): Boolean {
        if (needle.isEmpty()) return true
        for (start in 0..size - needle.size) {
            var matches = true
            for (offset in needle.indices) {
                if (this[start + offset] != needle[offset]) {
                    matches = false
                    break
                }
            }
            if (matches) return true
        }
        return false
    }
}

private fun List<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }
