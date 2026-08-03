package com.morneven.kron.sync

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Creates only the KRON namespaces owned by this app and reuses existing ones. */
class KronDriveNamespace(
    private val endpoint: String = "https://www.googleapis.com",
    private val connectionFactory: DriveHttpConnectionFactory = DriveHttpConnectionFactory {
        it.openConnection() as HttpURLConnection
    },
) {
    suspend fun ensureTeamWorkspace(accessToken: String, teamId: String): String {
        requireIdentifier(teamId, "Team ID")
        val root = ensureFolder(accessToken, parentId = "root", name = "KRON", kind = "root")
        val teamRoot = ensureFolder(accessToken, root, "Team", "team-root")
        return ensureFolder(
            accessToken,
            teamRoot,
            "team-$teamId",
            "team-workspace",
            mapOf("teamId" to teamId),
        )
    }

    suspend fun ensureCapsuleRoot(accessToken: String): String =
        ensureFolder(
            accessToken,
            ensureFolder(accessToken, "root", "KRON", "root"),
            "Capsule",
            "capsule-root",
        )

    private suspend fun ensureFolder(
        accessToken: String,
        parentId: String,
        name: String,
        kind: String,
        extraProperties: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val query = encode("'$parentId' in parents and trashed = false")
        val expected = mapOf("product" to "KRON", "kind" to kind) + extraProperties
        var pageToken: String? = null
        repeat(MAX_LIST_PAGES) {
            val fields = encode("nextPageToken,files(id,name,appProperties,createdTime)")
            val url = buildString {
                append(endpoint).append("/drive/v3/files?q=").append(query)
                    .append("&fields=").append(fields).append("&pageSize=1000")
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val page = JSONObject(request(accessToken, URL(url), "GET"))
            val files = page.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val file = files.getJSONObject(index)
                if (file.optString("name") == name && expected.all { (key, value) ->
                        file.optJSONObject("appProperties")?.optString(key) == value
                    }) {
                    return@withContext file.getString("id")
                }
            }
            pageToken = page.optString("nextPageToken").takeIf(String::isNotBlank)
            if (pageToken == null) return@withContext createFolder(
                accessToken,
                parentId,
                name,
                kind,
                expected,
            )
        }
        throw IllegalStateException("Daftar folder KRON melewati batas aman")
    }

    private suspend fun createFolder(
        accessToken: String,
        parentId: String,
        name: String,
        kind: String,
        expected: Map<String, String>,
    ): String {
        val properties = JSONObject().apply { expected.forEach { (key, value) -> put(key, value) } }
        val body = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME_TYPE)
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", properties)
            .apply { if (kind == "team-workspace") put("writersCanShare", false) }
            .toString()
        val created = requestJson(
            accessToken,
            URL("$endpoint/drive/v3/files?fields=id"),
            "POST",
            body,
        )
        require(JSONObject(created).optString("id").isNotBlank()) { "Folder KRON tidak memiliki ID" }
        return JSONObject(created).getString("id")
    }

    private fun requestJson(accessToken: String, url: URL, method: String, body: String): String {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val connection = connectionFactory.open(url).apply {
            requestMethod = method
            configure(accessToken)
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setFixedLengthStreamingMode(bytes.size)
        }
        return try {
            connection.outputStream.use { it.write(bytes) }
            validate(connection)
            connection.inputStream.readLimited(128 * 1024).toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun request(accessToken: String, url: URL, method: String): String {
        val connection = connectionFactory.open(url).apply {
            requestMethod = method
            configure(accessToken)
        }
        return try {
            validate(connection)
            connection.inputStream.readLimited(512 * 1024).toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.configure(accessToken: String) {
        require(accessToken.isNotBlank()) { "Access token kosong" }
        connectTimeout = 20_000
        readTimeout = 30_000
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
    }

    private fun validate(connection: HttpURLConnection) {
        if (connection.responseCode in 200..299) return
        val body = connection.errorStream?.readLimited(64 * 1024)?.toString(Charsets.UTF_8).orEmpty()
        throw DriveErrorClassifier.toException(connection.responseCode, body)
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun requireIdentifier(value: String, label: String) {
        require(value.isNotBlank() && value.length <= 256 && value.none { it.isWhitespace() }) {
            "$label tidak valid"
        }
    }

    companion object {
        private const val MAX_LIST_PAGES = 10
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
    }
}
