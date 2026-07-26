package com.morneven.kron.team

import com.morneven.kron.sync.DriveErrorClassifier
import com.morneven.kron.sync.DriveHttpConnectionFactory
import com.morneven.kron.sync.readLimited
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class TeamDriveCapabilities(
    val canRead: Boolean,
    val canWrite: Boolean,
    val canShare: Boolean,
)

data class TeamDriveWorkspace(
    val folderId: String,
    val capabilities: TeamDriveCapabilities,
    val writersCanShare: Boolean,
)

data class TeamDriveMember(
    val permissionId: String,
    val email: String,
    val displayName: String?,
    val role: String,
)

data class TeamDriveFile(
    val fileId: String,
    val name: String,
    val sizeBytes: Long,
    val appProperties: Map<String, String>,
)

class TeamDriveRestClient(
    private val endpoint: String = "https://www.googleapis.com",
    private val connectionFactory: DriveHttpConnectionFactory = DriveHttpConnectionFactory {
        it.openConnection() as HttpURLConnection
    },
) {
    suspend fun createWorkspace(accessToken: String, teamId: String): TeamDriveWorkspace = withContext(Dispatchers.IO) {
        requireIdentifier(teamId, "Team ID")
        val body = JSONObject()
            .put("name", "KRON Team")
            .put("mimeType", FOLDER_MIME_TYPE)
            .put("writersCanShare", false)
            .put("appProperties", JSONObject().put("product", "KRON").put("teamId", teamId))
            .toString()
        val response = requestJson(
            accessToken,
            URL("$endpoint/drive/v3/files?fields=id,writersCanShare,capabilities(canEdit,canShare,canAddChildren)"),
            "POST",
            body,
        )
        parseWorkspace(response)
    }

    suspend fun workspace(accessToken: String, folderId: String): TeamDriveWorkspace = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        val response = request(
            accessToken,
            URL("$endpoint/drive/v3/files/${path(folderId)}?fields=id,writersCanShare,capabilities(canEdit,canShare,canAddChildren)"),
            "GET",
        )
        parseWorkspace(response)
    }

    suspend fun addMember(
        accessToken: String,
        folderId: String,
        email: String,
        role: String,
    ): TeamDriveMember = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        val driveRole = driveRole(role)
        val normalizedEmail = email.trim().lowercase()
        TeamInvitationCodec.emailHash(normalizedEmail)
        val body = JSONObject()
            .put("type", "user")
            .put("role", driveRole)
            .put("emailAddress", normalizedEmail)
            .toString()
        val response = requestJson(
            accessToken,
            URL("$endpoint/drive/v3/files/${path(folderId)}/permissions?sendNotificationEmail=true&fields=id,emailAddress,displayName,role,type"),
            "POST",
            body,
        )
        parseMember(JSONObject(response))
    }

    suspend fun listMembers(accessToken: String, folderId: String): List<TeamDriveMember> = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        val result = mutableListOf<TeamDriveMember>()
        var pageToken: String? = null
        var pages = 0
        do {
            require(pages++ < MAX_LIST_PAGES) { "Daftar collaborator melewati batas aman" }
            val url = buildString {
                append(endpoint).append("/drive/v3/files/").append(path(folderId))
                append("/permissions?fields=nextPageToken,permissions(id,emailAddress,displayName,role,type)&pageSize=100")
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val json = JSONObject(request(accessToken, URL(url), "GET"))
            val permissions = json.optJSONArray("permissions") ?: JSONArray()
            for (index in 0 until permissions.length()) {
                val permission = permissions.getJSONObject(index)
                if (permission.optString("type") == "user") result += parseMember(permission)
            }
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        result
    }

    suspend fun updateMemberRole(
        accessToken: String,
        folderId: String,
        permissionId: String,
        role: String,
    ): TeamDriveMember = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        requireIdentifier(permissionId, "Permission ID")
        val response = requestJson(
            accessToken,
            URL("$endpoint/drive/v3/files/${path(folderId)}/permissions/${path(permissionId)}?fields=id,emailAddress,displayName,role,type"),
            "PATCH",
            JSONObject().put("role", driveRole(role)).toString(),
        )
        parseMember(JSONObject(response))
    }

    suspend fun removeMember(accessToken: String, folderId: String, permissionId: String) = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        requireIdentifier(permissionId, "Permission ID")
        request(
            accessToken,
            URL("$endpoint/drive/v3/files/${path(folderId)}/permissions/${path(permissionId)}"),
            "DELETE",
        )
        Unit
    }

    suspend fun uploadImmutable(
        accessToken: String,
        folderId: String,
        name: String,
        kind: String,
        teamId: String,
        bytes: ByteArray,
        extraProperties: Map<String, String> = emptyMap(),
    ): TeamDriveFile = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        requireIdentifier(teamId, "Team ID")
        require(name.length in 1..160 && name.all { it.isLetterOrDigit() || it in "-_." }) { "Nama file Team tidak valid" }
        require(kind in setOf("snapshot", "blob", "invitation", "recovery", "tombstone")) { "Jenis file Team tidak valid" }
        require(bytes.isNotEmpty() && bytes.size <= MAX_FILE_BYTES) { "File Team tidak valid atau terlalu besar" }
        require(extraProperties.keys.all { it.matches(Regex("[A-Za-z0-9_.-]{1,64}")) }) { "Metadata file Team tidak valid" }
        val properties = JSONObject().put("product", "KRON").put("teamId", teamId).put("kind", kind)
        extraProperties.toSortedMap().forEach(properties::put)
        val metadata = JSONObject()
            .put("name", name)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", properties)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val boundary = "kron-${UUID.randomUUID()}"
        val prefix = ("--$boundary\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n").toByteArray() +
            metadata + ("\r\n--$boundary\r\nContent-Type: application/octet-stream\r\n\r\n").toByteArray()
        val suffix = "\r\n--$boundary--\r\n".toByteArray()
        val connection = connectionFactory.open(URL("$endpoint/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,appProperties")).apply {
            requestMethod = "POST"
            configure(accessToken)
            doOutput = true
            setRequestProperty("Content-Type", "multipart/related; boundary=$boundary")
            setFixedLengthStreamingMode(prefix.size.toLong() + bytes.size + suffix.size)
        }
        try {
            connection.outputStream.use { output ->
                output.write(prefix)
                output.write(bytes)
                output.write(suffix)
            }
            validate(connection)
            parseFile(JSONObject(connection.inputStream.readLimited(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun listFiles(accessToken: String, folderId: String, teamId: String): List<TeamDriveFile> = withContext(Dispatchers.IO) {
        requireIdentifier(folderId, "Folder ID")
        requireIdentifier(teamId, "Team ID")
        val query = encode("'$folderId' in parents and trashed = false and appProperties has { key='teamId' and value='$teamId' }")
        val fields = encode("nextPageToken,files(id,name,size,appProperties)")
        val result = mutableListOf<TeamDriveFile>()
        var pageToken: String? = null
        var pages = 0
        do {
            require(pages++ < MAX_LIST_PAGES) { "Daftar file Team melewati batas aman" }
            val url = buildString {
                append(endpoint).append("/drive/v3/files?q=").append(query)
                append("&fields=").append(fields).append("&pageSize=1000")
                pageToken?.let { append("&pageToken=").append(encode(it)) }
            }
            val json = JSONObject(request(accessToken, URL(url), "GET"))
            val files = json.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) result += parseFile(files.getJSONObject(index))
            pageToken = json.optString("nextPageToken").takeIf(String::isNotBlank)
        } while (pageToken != null)
        result
    }

    suspend fun download(accessToken: String, fileId: String): ByteArray = withContext(Dispatchers.IO) {
        requireIdentifier(fileId, "File ID")
        val connection = connectionFactory.open(URL("$endpoint/drive/v3/files/${path(fileId)}?alt=media")).apply {
            requestMethod = "GET"
            configure(accessToken)
        }
        try {
            validate(connection)
            connection.inputStream.readLimited(MAX_FILE_BYTES)
        } finally {
            connection.disconnect()
        }
    }

    suspend fun delete(accessToken: String, fileId: String) = withContext(Dispatchers.IO) {
        requireIdentifier(fileId, "File ID")
        request(accessToken, URL("$endpoint/drive/v3/files/${path(fileId)}"), "DELETE")
        Unit
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
            if (connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) "{}"
            else connection.inputStream.readLimited(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
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
            if (connection.responseCode == HttpURLConnection.HTTP_NO_CONTENT) "{}"
            else connection.inputStream.readLimited(MAX_RESPONSE_BYTES).toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun HttpURLConnection.configure(accessToken: String) {
        require(accessToken.isNotBlank()) { "Access token kosong" }
        connectTimeout = CONNECT_TIMEOUT_MILLIS
        readTimeout = READ_TIMEOUT_MILLIS
        setRequestProperty("Authorization", "Bearer $accessToken")
        setRequestProperty("Accept", "application/json")
    }

    private fun validate(connection: HttpURLConnection) {
        if (connection.responseCode in 200..299) return
        val body = connection.errorStream?.readLimited(MAX_ERROR_BYTES)?.toString(Charsets.UTF_8).orEmpty()
        throw DriveErrorClassifier.toException(connection.responseCode, body)
    }

    private fun parseWorkspace(json: String): TeamDriveWorkspace {
        val value = JSONObject(json)
        val capabilities = value.optJSONObject("capabilities") ?: JSONObject()
        return TeamDriveWorkspace(
            folderId = value.getString("id"),
            capabilities = TeamDriveCapabilities(
                canRead = true,
                canWrite = capabilities.optBoolean("canEdit") || capabilities.optBoolean("canAddChildren"),
                canShare = capabilities.optBoolean("canShare"),
            ),
            writersCanShare = value.optBoolean("writersCanShare", true),
        )
    }

    private fun parseMember(value: JSONObject): TeamDriveMember {
        require(value.optString("type", "user") == "user") { "Permission Drive bukan user" }
        return TeamDriveMember(
            permissionId = value.getString("id"),
            email = value.optString("emailAddress"),
            displayName = value.optString("displayName").takeIf(String::isNotBlank),
            role = when (value.getString("role")) {
                "owner" -> com.morneven.kron.data.TeamRole.OWNER
                "writer" -> com.morneven.kron.data.TeamRole.EDITOR
                "reader" -> com.morneven.kron.data.TeamRole.VIEWER
                else -> error("Role permission Drive tidak didukung")
            },
        )
    }

    private fun parseFile(value: JSONObject): TeamDriveFile {
        val properties = value.optJSONObject("appProperties") ?: JSONObject()
        return TeamDriveFile(
            fileId = value.getString("id"),
            name = value.getString("name"),
            sizeBytes = value.optString("size", "0").toLong(),
            appProperties = properties.keys().asSequence().associateWith { properties.getString(it) },
        )
    }

    private fun driveRole(role: String): String = when (role) {
        com.morneven.kron.data.TeamRole.EDITOR -> "writer"
        com.morneven.kron.data.TeamRole.VIEWER -> "reader"
        else -> throw IllegalArgumentException("Role collaborator tidak valid")
    }

    private fun requireIdentifier(value: String, label: String) {
        require(value.length in 1..512 && value.all { it.isLetterOrDigit() || it in "-_." }) { "$label tidak valid" }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
    private fun path(value: String): String = encode(value).replace("+", "%20")

    companion object {
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val CONNECT_TIMEOUT_MILLIS = 20_000
        private const val READ_TIMEOUT_MILLIS = 90_000
        private const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        private const val MAX_ERROR_BYTES = 64 * 1024
        private const val MAX_FILE_BYTES = 128 * 1024 * 1024
        private const val MAX_LIST_PAGES = 100
    }
}
