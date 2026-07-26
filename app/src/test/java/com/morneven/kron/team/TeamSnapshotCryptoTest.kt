package com.morneven.kron.team

import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveSnapshotManifest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamSnapshotCryptoTest {
    @Test
    fun roundTripAuthenticatesManifestPayloadAndTeamKey() {
        val payload = "snapshot Team rahasia".toByteArray()
        val key = ByteArray(32) { it.toByte() }
        val manifest = DriveSnapshotManifest(
            protocolVersion = 2,
            datasetId = "team-1",
            snapshotId = "snapshot-2",
            parentSnapshotId = "snapshot-1",
            parentSnapshotIds = listOf("snapshot-1", "snapshot-fork"),
            generation = 2,
            sourceDeviceId = "device-1",
            schemaVersion = 15,
            minimumAppVersionCode = 1,
            createdAtEpochMillis = 2,
            payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
        )
        val cryptor = TeamSnapshotCryptor()

        val encrypted = cryptor.encrypt(manifest, payload, key)
        val opened = cryptor.decrypt(encrypted, key)

        assertEquals(manifest, opened.manifest)
        assertArrayEquals(payload, opened.payload)
        assertFalse(encrypted.toString(Charsets.ISO_8859_1).contains("snapshot Team rahasia"))
        assertTrue(runCatching { cryptor.decrypt(encrypted, ByteArray(32) { 7 }) }.exceptionOrNull() is InvalidTeamSnapshotException)
        encrypted[encrypted.lastIndex] = (encrypted.last() + 1).toByte()
        assertTrue(runCatching { cryptor.decrypt(encrypted, key) }.exceptionOrNull() is InvalidTeamSnapshotException)
    }
}
