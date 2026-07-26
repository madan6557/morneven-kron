package com.morneven.kron.sync

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class DriveSnapshotCryptoTest {
    private val payload = "jurnal KRON rahasia".toByteArray()
    private val manifest = DriveSnapshotManifest(
        datasetId = "dataset-a",
        snapshotId = "snapshot-a",
        parentSnapshotId = null,
        generation = 4,
        sourceDeviceId = "device-a",
        schemaVersion = 6,
        minimumAppVersionCode = 22,
        createdAtEpochMillis = 1234,
        payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
    )

    @Test
    fun encryptedSnapshotRoundTripsAndDoesNotContainPlaintext() {
        val cryptor = AesGcmDriveSnapshotCryptor(SecureRandom(byteArrayOf(1, 2, 3, 4)))
        val envelope = cryptor.encrypt(manifest, payload, "passphrase-aman".toCharArray())

        assertFalse(envelope.toString(Charsets.ISO_8859_1).contains("jurnal KRON rahasia"))
        val decrypted = cryptor.decrypt(envelope, "passphrase-aman".toCharArray())
        assertEquals(manifest, decrypted.manifest)
        assertArrayEquals(payload, decrypted.payload)
    }

    @Test
    fun wrongPassphraseAndTamperingAreRejected() {
        val cryptor = AesGcmDriveSnapshotCryptor()
        val envelope = cryptor.encrypt(manifest, payload, "passphrase-aman".toCharArray())

        assertThrows(IllegalArgumentException::class.java) {
            cryptor.decrypt(envelope, "passphrase-salah".toCharArray())
        }
        envelope[envelope.lastIndex] = (envelope.last().toInt() xor 1).toByte()
        assertThrows(IllegalArgumentException::class.java) {
            cryptor.decrypt(envelope, "passphrase-aman".toCharArray())
        }
    }

    @Test
    fun protocolTwoPreservesMergeParentsAndProtocolOneRemainsReadable() {
        val merged = manifest.copy(
            protocolVersion = 2,
            parentSnapshotId = "snapshot-left",
            parentSnapshotIds = listOf("snapshot-left", "snapshot-right"),
        )

        assertEquals(merged, SnapshotManifestCodec.decode(SnapshotManifestCodec.encode(merged)))
        assertEquals(merged, DriveSnapshotManifest.fromAppProperties(merged.toAppProperties()))

        val legacyJson = SnapshotManifestCodec.encode(manifest)
            .replace("\"parentSnapshotIds\":\"\",", "")
        assertEquals(emptyList<String>(), SnapshotManifestCodec.decode(legacyJson).parentSnapshotIds)
    }
}
