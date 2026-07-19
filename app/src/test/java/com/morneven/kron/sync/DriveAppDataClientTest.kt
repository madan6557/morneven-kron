package com.morneven.kron.sync

import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveAppDataClientTest {
    @Test
    fun listSnapshotsFollowsEveryDrivePageToken() = runBlocking {
        val requestedUrls = mutableListOf<URL>()
        val responses = ArrayDeque(
            listOf(
                listResponse(snapshotJson("file-1", manifest("snapshot-1", 1)), nextPageToken = "page 2/+"),
                listResponse(snapshotJson("file-2", manifest("snapshot-2", 2))),
            ),
        )
        val client = DriveRestV3AppDataClient(
            endpoint = "https://drive.test",
            connectionFactory = DriveHttpConnectionFactory { url ->
                requestedUrls += url
                FakeHttpConnection(url, responses.removeFirst())
            },
        )

        val snapshots = client.listSnapshots("access-token")

        assertEquals(listOf("file-1", "file-2"), snapshots.map { it.fileId })
        assertEquals(2, requestedUrls.size)
        assertFalse(requestedUrls.first().query.contains("pageToken="))
        assertTrue(requestedUrls.last().query.contains("pageToken=page+2%2F%2B"))
        assertTrue(requestedUrls.first().query.contains("nextPageToken"))
    }

    private fun manifest(snapshotId: String, generation: Long) = DriveSnapshotManifest(
        datasetId = "dataset-a",
        snapshotId = snapshotId,
        parentSnapshotId = null,
        generation = generation,
        sourceDeviceId = "device-a",
        schemaVersion = 6,
        minimumAppVersionCode = 22,
        createdAtEpochMillis = generation,
        payloadSha256 = "00".repeat(32),
    )

    private fun snapshotJson(fileId: String, manifest: DriveSnapshotManifest): String {
        val properties = manifest.toAppProperties().entries.joinToString(",") { (key, value) ->
            "\"$key\":\"$value\""
        }
        return """{"id":"$fileId","name":"$fileId.bin","createdTime":"${Instant.ofEpochMilli(manifest.createdAtEpochMillis)}","size":"100","appProperties":{$properties}}"""
    }

    private fun listResponse(file: String, nextPageToken: String? = null): ByteArray {
        val token = nextPageToken?.let { "\"nextPageToken\":\"$it\"," }.orEmpty()
        return "{$token\"files\":[$file]}".toByteArray()
    }

    private class FakeHttpConnection(
        url: URL,
        private val response: ByteArray,
    ) : HttpURLConnection(url) {
        override fun connect() = Unit
        override fun disconnect() = Unit
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = HTTP_OK
        override fun getInputStream() = ByteArrayInputStream(response)
    }
}
