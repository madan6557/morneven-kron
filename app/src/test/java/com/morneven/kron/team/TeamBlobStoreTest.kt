package com.morneven.kron.team

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TeamBlobStoreTest {
    @Test
    fun encryptDecryptRoundtrip() {
        val plaintext = "Hello KRON Team Blob".toByteArray(Charsets.UTF_8)
        val teamKey = ByteArray(32).also { it[0] = 0x01; it[31] = 0x02 }
        val contentKey = "test-content-01"

        val encrypted = TeamBlobStore.encryptBlob(plaintext, teamKey, contentKey)
        val decrypted = TeamBlobStore.decryptBlob(encrypted, teamKey, contentKey)

        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun sameContentSameKeyProducesSameEncryptedBlob() {
        val plaintext = "Deterministic content".toByteArray(Charsets.UTF_8)
        val teamKey = ByteArray(32).also { it[0] = 0x42 }

        val a = TeamBlobStore.encryptBlob(plaintext, teamKey, "key1")
        val b = TeamBlobStore.encryptBlob(plaintext, teamKey, "key1")

        assertArrayEquals(a, b)
    }

    @Test
    fun rejectTruncatedBlob() {
        val raw = byteArrayOf(0x00)
        assertThrows(IllegalArgumentException::class.java) {
            TeamBlobStore.decryptBlob(raw, ByteArray(32), "x")
        }
    }

    @Test
    fun rejectWrongMagic() {
        val fake = ByteArray(128) { 0xFF.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            TeamBlobStore.decryptBlob(fake, ByteArray(32), "x")
        }
    }
}
