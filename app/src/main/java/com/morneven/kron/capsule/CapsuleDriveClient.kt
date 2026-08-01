package com.morneven.kron.capsule

import com.morneven.kron.sync.DriveApiException
import com.morneven.kron.sync.DriveAuthorizationException
import com.morneven.kron.sync.DriveErrorClassifier
import com.morneven.kron.sync.DriveHttpConnectionFactory
import com.morneven.kron.sync.readLimited
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class CapsuleDriveClient(
    private val endpoint: String = "https://www.googleapis.com",
    private val connectionFactory: DriveHttpConnectionFactory = DriveHttpConnectionFactory {
        it.openConnection() as HttpURLConnection
    },
) {
    data class CapsuleDriveFile(val fileId: String, val name: String, val sizeBytes: Long)

    suspend fun upload(
        accessToken: String,
        targetEmail: String,
        envelopeBytes: ByteArray,
    ): CapsuleDriveFile = withContext(Dispatchers.IO) {
        require(envelopeBytes.isNotEmpty() && envelopeBytes.size <= MAX_FILE_BYTES) { "Envelope Kapsul terlalu besar" }
        val name = "kron-capsule-${UUID.randomUUID()}.kroncapsule"
        val mimeType = "application/octet-stream"
        val boundary = "kron-${UUID.randomUUID()}"
        val metadata = """{"name":"${escape(name)}","mimeType":"$mimeType"}""".toByteArray(Charsets.UTF_8)
        val prefix = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray() +
            metadata + ("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val connection = connectionFactory.open(URL("$endpoint/upload/drive/v3/files?uploadType=multipart&fields=id,name,size")).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(prefix.size.toLong() + envelopeBytes.size + suffix.size)
        }
        try {
            connection.outputStream.use { out -> out.write(prefix); out.write(envelopeBytes); out.write(suffix) }
            validate(connection)
            val body = connection.inputStream.readLimited(128 * 1024).toString(Charsets.UTF_8)
            val fileId = extractField(body, "id") ?: error("Drive tidak mengembalikan fileId")
            val size = extractField(body, "size")?.toLongOrNull() ?: envelopeBytes.size.toLong()
            CapsuleDriveFile(fileId, name, size)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun grantReader(accessToken: String, fileId: String, email: String) = withContext(Dispatchers.IO) {
        val normalized = email.trim().lowercase()
        val body = """{"type":"user","role":"reader","emailAddress":"${escape(normalized)}"}"""
        val connection = connectionFactory.open(URL("$endpoint/drive/v3/files/${path(fileId)}/permissions?fields=id")).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(body.toByteArray(Charsets.UTF_8).size.toLong())
        }
        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            validate(connection)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(accessToken: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val url = URL("$endpoint/drive/v3/files/${path(fileId)}?alt=media")
        val connection = connectionFactory.open(url).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            validate(connection)
            connection.inputStream.readLimited(MAX_FILE_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun revoke(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        try {
            val url = URL("$endpoint/drive/v3/files/${path(fileId)}/permissions")
            val connection = connectionFactory.open(url).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Authorization", "Bearer $accessToken")
            }
            try {
                validate(connection)
                val body = connection.inputStream.readLimited(64 * 1024).toString(Charsets.UTF_8)
                val permissionId = extractPermissionId(body)
                if (permissionId != null) {
                    deletePermission(accessToken, fileId, permissionId)
                }
            } finally {
                connection.disconnect()
            }
            deleteFile(accessToken, fileId)
        } catch (_: DriveApiException) { }
    }

    suspend fun deleteFile(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        val connection = connectionFactory.open(URL("$endpoint/drive/v3/files/${path(fileId)}")).apply {
            requestMethod = "DELETE"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            validate(connection)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun deletePermission(accessToken: String, fileId: String, permissionId: String) = withContext(Dispatchers.IO) {
        val connection = connectionFactory.open(URL("$endpoint/drive/v3/files/${path(fileId)}/permissions/${path(permissionId)}")).apply {
            requestMethod = "DELETE"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $accessToken")
        }
        try {
            validate(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun validate(connection: HttpURLConnection) {
        if (connection.responseCode in 200..299) return
        val body = connection.errorStream?.readLimited(64 * 1024)?.toString(Charsets.UTF_8).orEmpty()
        throw DriveErrorClassifier.toException(connection.responseCode, body)
    }

    private fun extractField(json: String, key: String): String? {
        val marker = "\"$key\":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = json.indexOf('"', start + marker.length) + 1
        if (valueStart <= 0) return null
        val end = json.indexOf('"', valueStart)
        return if (end < 0) null else json.substring(valueStart, end)
    }

    private fun extractPermissionId(json: String): String? {
        val marker = "\"id\":"
        val start = json.indexOf(marker)
        if (start < 0) return null
        val valueStart = json.indexOf('"', start + marker.length) + 1
        if (valueStart <= 0) return null
        val end = json.indexOf('"', valueStart)
        return if (end < 0) null else json.substring(valueStart, end)
    }

    private fun escape(s: String) = s.replace("\\", "\\\\").replace("\"", "\\\"")
    private fun path(value: String) = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    companion object {
        private const val MAX_FILE_BYTES = 32 * 1024 * 1024
    }
}
