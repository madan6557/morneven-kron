package com.morneven.kron.team

import com.morneven.kron.sync.AesGcmDriveSnapshotCryptor
import com.morneven.kron.sync.DriveHttpConnectionFactory
import com.morneven.kron.sync.DriveSnapshotManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamDriveRestClientTest {
    @Test
    fun listSnapshotsRestoresParentDagAndRejectsOtherFiles() = runBlocking {
        val valid = fileJson("file-1", "snapshot", snapshotProperties())
        val blob = fileJson("file-2", "blob", mapOf("product" to "KRON", "teamId" to "team-1"))
        val response = "{\"files\":[$valid,$blob]}".toByteArray()
        val client = TeamDriveRestClient(
            endpoint = "https://drive.test",
            connectionFactory = DriveHttpConnectionFactory { url -> FakeHttpConnection(url, response) },
        )

        val snapshots = client.listSnapshots("token", "folder-1", "team-1")

        assertEquals(1, snapshots.size)
        assertEquals("snapshot-2", snapshots.single().manifest.snapshotId)
        assertEquals(listOf("snapshot-1", "snapshot-fork"), snapshots.single().manifest.parentSnapshotIds)
    }

    @Test
    fun listSnapshotsFailsClosedOnMalformedTeamMetadata() = runBlocking {
        val malformed = fileJson(
            "file-1",
            "snapshot",
            mapOf("product" to "KRON", "teamId" to "team-1"),
        )
        val client = TeamDriveRestClient(
            endpoint = "https://drive.test",
            connectionFactory = DriveHttpConnectionFactory { url ->
                FakeHttpConnection(url, "{\"files\":[$malformed]}".toByteArray())
            },
        )

        assertTrue(runCatching { client.listSnapshots("token", "folder-1", "team-1") }.isFailure)
    }

    @Test
    fun uploadSnapshotUsesImmutableFileAndChunkedParents() = runBlocking {
        lateinit var connection: FakeHttpConnection
        val response = fileJson(
            "file-1",
            "snapshot",
            snapshotProperties(manifest().payloadSha256),
            size = 3,
        ).toByteArray()
        val client = TeamDriveRestClient(
            endpoint = "https://drive.test",
            connectionFactory = DriveHttpConnectionFactory { url -> FakeHttpConnection(url, response).also { connection = it } },
        )
        val manifest = manifest()

        val uploaded = client.uploadSnapshot("token", "folder-1", "team-1", manifest, byteArrayOf(1, 2, 3))
        val request = connection.requestBytes.toString(Charsets.ISO_8859_1)

        assertEquals("file-1", uploaded.fileId)
        assertEquals("POST", connection.requestMethod)
        assertTrue(connection.url.toString().contains("uploadType=multipart"))
        assertTrue(request.contains("\"kind\":\"snapshot\""))
        assertTrue(request.contains("\"parentCount\":\"2\""))
        assertTrue(request.contains("\"parent0\":\"snapshot-1\""))
        assertTrue(request.contains("\"parent1\":\"snapshot-fork\""))
        assertFalse(request.contains("\"parents\":\""))
    }

    @Test
    fun uploadInvitationStoresOnlyHashesAndVerifiesMetadata() = runBlocking {
        val invitation = TeamInvitationCodec.create(
            teamId = "team-1",
            folderId = "folder-1",
            targetEmail = "member@example.com",
            role = com.morneven.kron.data.TeamRole.EDITOR,
            ownerKeyFingerprint = "ab".repeat(32),
            nowEpochMillis = 1,
            inviteId = "invite-1",
        )
        val inviteHash = TeamInvitationCodec.sha256("invite-1".toByteArray())
        val properties = mapOf(
            "product" to "KRON",
            "teamId" to "team-1",
            "kind" to "invitation",
            "invite" to inviteHash,
            "target" to invitation.targetEmailHash,
            "role" to invitation.role,
            "expires" to invitation.expiresAtEpochMillis.toString(),
            "owner" to invitation.ownerKeyFingerprint,
        )
        lateinit var connection: FakeHttpConnection
        val client = TeamDriveRestClient(
            endpoint = "https://drive.test",
            connectionFactory = DriveHttpConnectionFactory { url ->
                FakeHttpConnection(url, fileJson("file-1", "invitation", properties, 3).toByteArray()).also { connection = it }
            },
        )

        val uploaded = client.uploadInvitation("token", "folder-1", invitation, byteArrayOf(1, 2, 3))
        val request = connection.requestBytes.toString(Charsets.ISO_8859_1)

        assertEquals("file-1", uploaded.fileId)
        assertTrue(request.contains(inviteHash))
        assertTrue(request.contains(invitation.targetEmailHash))
        assertFalse(request.contains("invite-1"))
        assertFalse(request.contains("member@example.com"))
        invitation.clear()
    }

    private fun manifest() = DriveSnapshotManifest(
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
        payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(byteArrayOf(1, 2, 3)),
    )

    private fun snapshotProperties(payloadSha256: String = "00".repeat(32)): Map<String, String> = mapOf(
        "product" to "KRON",
        "teamId" to "team-1",
        "kind" to "snapshot",
        "protocol" to "2",
        "dataset" to "team-1",
        "snapshot" to "snapshot-2",
        "generation" to "2",
        "device" to "device-1",
        "schema" to "15",
        "minApp" to "1",
        "created" to "2",
        "sha256" to payloadSha256,
        "kdf" to "310000",
        "snapshotKind" to "ACTIVE",
        "parentCount" to "2",
        "parent0" to "snapshot-1",
        "parent1" to "snapshot-fork",
    )

    private fun fileJson(id: String, kind: String, properties: Map<String, String>, size: Long = 100): String {
        val values = (properties + ("kind" to kind)).entries.joinToString(",") { (key, value) ->
            "\"$key\":\"$value\""
        }
        return "{\"id\":\"$id\",\"name\":\"$id.kronteam\",\"size\":\"$size\",\"appProperties\":{$values}}"
    }

    private class FakeHttpConnection(
        url: URL,
        private val response: ByteArray,
    ) : HttpURLConnection(url) {
        val requestBytes = ByteArrayOutputStream()

        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = HTTP_OK
        override fun getInputStream() = ByteArrayInputStream(response)
        override fun getOutputStream() = requestBytes
    }
}
