package com.morneven.kron.sync

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface DriveAppDataClient {
    suspend fun listSnapshots(accessToken: String): List<RemoteDriveSnapshot>
    suspend fun uploadSnapshot(
        accessToken: String,
        manifest: DriveSnapshotManifest,
        encryptedEnvelope: ByteArray,
    ): RemoteDriveSnapshot

    suspend fun downloadSnapshot(accessToken: String, fileId: String): ByteArray
    suspend fun deleteSnapshot(accessToken: String, fileId: String)
}

open class DriveApiException(
    message: String,
    val statusCode: Int,
    val retryable: Boolean,
) : Exception(message)

class DriveAuthorizationException(message: String, statusCode: Int) :
    DriveApiException(message, statusCode, retryable = false)

class DriveBillingRequiredException(message: String, statusCode: Int) :
    DriveApiException(message, statusCode, retryable = false)

fun interface DriveHttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

class DriveRestV3AppDataClient(
    private val endpoint: String = "https://www.googleapis.com",
    private val connectionFactory: DriveHttpConnectionFactory = DriveHttpConnectionFactory {
        it.openConnection() as HttpURLConnection
    },
) : DriveAppDataClient {
    override suspend fun listSnapshots(accessToken: String): List<RemoteDriveSnapshot> = withContext(Dispatchers.IO) {
        val query = encode("trashed = false and appProperties has { key='product' and value='KRON' }")
        val fields = encode("nextPageToken,files(id,name,createdTime,size,appProperties)")
        val snapshots = mutableListOf<RemoteDriveSnapshot>()
        val seenPageTokens = mutableSetOf<String>()
        var pageToken: String? = null
        var pageCount = 0
        do {
            currentCoroutineContext().ensureActive()
            require(pageCount++ < MAX_LIST_PAGES) { "Daftar snapshot Drive melewati batas aman" }
            val url = URL(buildString {
                append(endpoint)
                append("/drive/v3/files?spaces=appDataFolder&q=")
                append(query)
                append("&fields=")
                append(fields)
                append("&pageSize=")
                append(LIST_PAGE_SIZE)
                pageToken?.let {
                    append("&pageToken=")
                    append(encode(it))
                }
            })
            val nextPageToken = execute(url, "GET", accessToken).use { response ->
                val body = response.readBody(MAX_LIST_RESPONSE_BYTES).toString(Charsets.UTF_8)
                snapshots += parseSnapshots(body)
                DriveJson.optionalString(body, "nextPageToken")?.takeIf(String::isNotBlank)
            }
            if (nextPageToken != null) {
                require(seenPageTokens.add(nextPageToken)) { "Token halaman Drive berulang" }
            }
            pageToken = nextPageToken
        } while (pageToken != null)
        snapshots
    }

    override suspend fun uploadSnapshot(
        accessToken: String,
        manifest: DriveSnapshotManifest,
        encryptedEnvelope: ByteArray,
    ): RemoteDriveSnapshot = withContext(Dispatchers.IO) {
        require(encryptedEnvelope.isNotEmpty()) { "Snapshot terenkripsi kosong" }
        val boundary = "kron-${UUID.randomUUID()}"
        val name = "kron-sync-${manifest.snapshotId}.bin"
        val metadata = buildMetadataJson(name, manifest.toAppProperties()).toByteArray(Charsets.UTF_8)
        val prefix = ("--$boundary\r\n" +
            "Content-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray() +
            metadata +
            ("\r\n--$boundary\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val url = URL("$endpoint/upload/drive/v3/files?uploadType=multipart&fields=id,createdTime,size")
        val connection = connectionFactory.open(url).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(prefix.size.toLong() + encryptedEnvelope.size + suffix.size)
        }
        try {
            connection.outputStream.use { output ->
                output.write(prefix)
                output.write(encryptedEnvelope)
                output.write(suffix)
            }
            validateResponse(connection)
            val body = connection.inputStream.readLimited(MAX_SMALL_RESPONSE_BYTES).toString(Charsets.UTF_8)
            val fileId = DriveJson.string(body, "id")
            val created = DriveJson.optionalString(body, "createdTime")
                ?.let(Instant::parse)
                ?: Instant.ofEpochMilli(manifest.createdAtEpochMillis)
            val size = DriveJson.optionalStringOrNumber(body, "size")?.toLongOrNull()
                ?: encryptedEnvelope.size.toLong()
            RemoteDriveSnapshot(fileId, name, manifest, created, size)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun downloadSnapshot(accessToken: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        val url = URL("$endpoint/drive/v3/files/${path(fileId)}?alt=media")
        execute(url, "GET", accessToken).use { it.readBody(MAX_SNAPSHOT_BYTES) }
    }

    override suspend fun deleteSnapshot(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        val url = URL("$endpoint/drive/v3/files/${path(fileId)}")
        execute(url, "DELETE", accessToken).close()
    }

    private fun execute(url: URL, method: String, accessToken: String): DriveResponse {
        require(accessToken.isNotBlank()) { "Access token kosong" }
        val connection = connectionFactory.open(url).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            setRequestProperty("Authorization", "Bearer $accessToken")
            setRequestProperty("Accept", "application/json")
        }
        return try {
            validateResponse(connection)
            DriveResponse(connection)
        } catch (error: Exception) {
            connection.disconnect()
            throw error
        }
    }

    private fun validateResponse(connection: HttpURLConnection) {
        val status = connection.responseCode
        if (status in 200..299) return
        val body = connection.errorStream?.readLimited(MAX_ERROR_RESPONSE_BYTES)?.toString(Charsets.UTF_8).orEmpty()
        throw DriveErrorClassifier.toException(status, body)
    }

    private class DriveResponse(private val connection: HttpURLConnection) : AutoCloseable {
        fun readBody(limit: Int): ByteArray = if (connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) {
            ByteArray(0)
        } else {
            connection.inputStream.readLimited(limit)
        }

        override fun close() = connection.disconnect()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 90_000
        private const val MAX_ERROR_RESPONSE_BYTES = 64 * 1024
        private const val MAX_SMALL_RESPONSE_BYTES = 128 * 1024
        private const val MAX_LIST_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_SNAPSHOT_BYTES = 128 * 1024 * 1024
        private const val LIST_PAGE_SIZE = 1000
        private const val MAX_LIST_PAGES = 100

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
        private fun path(value: String): String = encode(value).replace("+", "%20")

        private fun buildMetadataJson(name: String, properties: Map<String, String>): String = buildString {
            append("{\"name\":\"").append(SnapshotManifestCodec.escape(name)).append("\",")
            append("\"parents\":[\"appDataFolder\"],\"appProperties\":{")
            properties.toSortedMap().entries.forEachIndexed { index, entry ->
                if (index > 0) append(',')
                append('"').append(SnapshotManifestCodec.escape(entry.key)).append("\":\"")
                    .append(SnapshotManifestCodec.escape(entry.value)).append('"')
            }
            append("}}")
        }

        private fun parseSnapshots(body: String): List<RemoteDriveSnapshot> =
            DriveJson.arrayObjects(body, "files").mapNotNull { json ->
                val properties = DriveJson.stringObject(json, "appProperties")
                val manifest = DriveSnapshotManifest.fromAppProperties(properties) ?: return@mapNotNull null
                runCatching {
                    RemoteDriveSnapshot(
                        fileId = DriveJson.string(json, "id"),
                        name = DriveJson.string(json, "name"),
                        manifest = manifest,
                        createdAt = Instant.parse(DriveJson.string(json, "createdTime")),
                        sizeBytes = DriveJson.stringOrNumber(json, "size").toLong(),
                    )
                }.getOrNull()
            }
    }
}

internal object DriveErrorClassifier {
    fun toException(statusCode: Int, responseBody: String): DriveApiException {
        val normalized = responseBody.lowercase()
        if (
            statusCode == 402 ||
            "billingnotenabled" in normalized ||
            "projectbillinginfo" in normalized ||
            "billing account" in normalized ||
            "requires billing" in normalized
        ) {
            return DriveBillingRequiredException(
                "Sinkronisasi Drive dihentikan karena layanan meminta billing",
                statusCode,
            )
        }
        if (
            statusCode == 401 ||
            "insufficientpermissions" in normalized ||
            "insufficient_scope" in normalized ||
            "invalid credentials" in normalized ||
            "autherror" in normalized
        ) {
            return DriveAuthorizationException("Otorisasi Google Drive perlu diperbarui", statusCode)
        }
        if (
            "accessnotconfigured" in normalized ||
            "servicedisabled" in normalized ||
            "api has not been used" in normalized
        ) {
            return DriveAuthorizationException(
                "Google Drive API belum aktif pada project OAuth KRON",
                statusCode,
            )
        }
        if ("domainpolicy" in normalized || "domain policy" in normalized) {
            return DriveAuthorizationException(
                "Kebijakan Google Workspace menolak akses Drive KRON",
                statusCode,
            )
        }
        if ("storagequotaexceeded" in normalized || "storage quota" in normalized) {
            return DriveApiException("Penyimpanan Google Drive tidak mencukupi", statusCode, retryable = false)
        }
        val retryable = statusCode == 408 || statusCode == 429 || statusCode >= 500 ||
            "ratelimitexceeded" in normalized || "userratelimitexceeded" in normalized
        val message = if (statusCode == 403) {
            "Google Drive menolak akses. Periksa Drive API, test user, dan kebijakan akun"
        } else {
            "Google Drive menolak permintaan (HTTP $statusCode)"
        }
        return DriveApiException(message, statusCode, retryable)
    }
}

private fun InputStream.readLimited(maxBytes: Int): ByteArray {
    val output = ByteArrayOutputStream(minOf(maxBytes, DEFAULT_BUFFER_SIZE))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) break
        total += read
        require(total <= maxBytes) { "Respons Google Drive melebihi batas aman" }
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}

private object DriveJson {
    fun arrayObjects(json: String, key: String): List<String> {
        val start = valueStart(json, key)
        require(json.getOrNull(start) == '[') { "Field $key bukan array" }
        val result = mutableListOf<String>()
        var index = start + 1
        while (index < json.length) {
            index = skipWhitespaceAndComma(json, index)
            if (json.getOrNull(index) == ']') return result
            require(json.getOrNull(index) == '{') { "Isi array $key tidak valid" }
            val end = matchingEnd(json, index, '{', '}')
            result += json.substring(index, end + 1)
            index = end + 1
        }
        error("Array JSON tidak lengkap")
    }

    fun stringObject(json: String, key: String): Map<String, String> {
        val start = valueStart(json, key)
        require(json.getOrNull(start) == '{') { "Field $key bukan object" }
        val end = matchingEnd(json, start, '{', '}')
        val objectJson = json.substring(start + 1, end)
        val result = linkedMapOf<String, String>()
        var index = 0
        while (index < objectJson.length) {
            index = skipWhitespaceAndComma(objectJson, index)
            if (index >= objectJson.length) break
            val parsedKey = parseString(objectJson, index)
            index = skipWhitespace(objectJson, parsedKey.second)
            require(objectJson.getOrNull(index++) == ':')
            index = skipWhitespace(objectJson, index)
            val parsedValue = parseString(objectJson, index)
            result[parsedKey.first] = parsedValue.first
            index = parsedValue.second
        }
        return result
    }

    fun string(json: String, key: String): String = optionalString(json, key)
        ?: throw IllegalArgumentException("Field $key tidak ditemukan")

    fun optionalString(json: String, key: String): String? {
        val start = valueStartOrNull(json, key) ?: return null
        if (json.startsWith("null", start)) return null
        return parseString(json, start).first
    }

    fun stringOrNumber(json: String, key: String): String = optionalStringOrNumber(json, key)
        ?: throw IllegalArgumentException("Field $key tidak ditemukan")

    fun optionalStringOrNumber(json: String, key: String): String? {
        val start = valueStartOrNull(json, key) ?: return null
        if (json.getOrNull(start) == '"') return parseString(json, start).first
        val end = generateSequence(start) { it + 1 }
            .takeWhile { it < json.length && (json[it].isDigit() || json[it] == '-') }
            .lastOrNull()?.plus(1) ?: start
        return json.substring(start, end).takeIf(String::isNotBlank)
    }

    private fun valueStart(json: String, key: String): Int = valueStartOrNull(json, key)
        ?: throw IllegalArgumentException("Field $key tidak ditemukan")

    private fun valueStartOrNull(json: String, key: String): Int? {
        val keyMarker = "\"${SnapshotManifestCodec.escape(key)}\""
        val keyIndex = json.indexOf(keyMarker)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + keyMarker.length)
        if (colon < 0) return null
        return skipWhitespace(json, colon + 1)
    }

    private fun matchingEnd(json: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until json.length) {
            val char = json[index]
            if (inString) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') inString = false
                continue
            }
            if (char == '"') inString = true
            else if (char == open) depth++
            else if (char == close && --depth == 0) return index
        }
        error("Object JSON tidak lengkap")
    }

    private fun parseString(json: String, start: Int): Pair<String, Int> {
        require(json.getOrNull(start) == '"') { "Nilai JSON bukan string" }
        val result = StringBuilder()
        var index = start + 1
        while (index < json.length) {
            val char = json[index++]
            if (char == '"') return result.toString() to index
            if (char != '\\') {
                result.append(char)
                continue
            }
            val escaped = json.getOrNull(index++) ?: error("Escape JSON tidak lengkap")
            result.append(
                when (escaped) {
                    '"', '\\', '/' -> escaped
                    'b' -> '\b'
                    'f' -> '\u000c'
                    'n' -> '\n'
                    'r' -> '\r'
                    't' -> '\t'
                    'u' -> {
                        val value = json.substring(index, index + 4).toInt(16).toChar()
                        index += 4
                        value
                    }
                    else -> error("Escape JSON tidak valid")
                },
            )
        }
        error("String JSON tidak lengkap")
    }

    private fun skipWhitespaceAndComma(json: String, start: Int): Int {
        var index = start
        while (index < json.length && (json[index].isWhitespace() || json[index] == ',')) index++
        return index
    }

    private fun skipWhitespace(json: String, start: Int): Int {
        var index = start
        while (index < json.length && json[index].isWhitespace()) index++
        return index
    }
}
