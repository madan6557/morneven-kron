package com.morneven.kron.sync

internal object SnapshotManifestCodec {
    fun encode(value: DriveSnapshotManifest): String = buildString {
        append('{')
        field("protocolVersion", value.protocolVersion)
        field("datasetId", value.datasetId)
        field("snapshotId", value.snapshotId)
        nullableField("parentSnapshotId", value.parentSnapshotId)
        field("parentSnapshotIds", value.parentSnapshotIds.joinToString(","))
        field("generation", value.generation)
        field("sourceDeviceId", value.sourceDeviceId)
        field("schemaVersion", value.schemaVersion)
        field("minimumAppVersionCode", value.minimumAppVersionCode)
        field("createdAtEpochMillis", value.createdAtEpochMillis)
        field("payloadSha256", value.payloadSha256)
        field("kdfIterations", value.kdfIterations)
        field("kind", value.kind.name, last = true)
        append('}')
    }

    fun decode(json: String): DriveSnapshotManifest {
        val protocol = number(json, "protocolVersion").toInt()
        val parent = nullableString(json, "parentSnapshotId")
        val parents = if (protocol >= 2) {
            optionalString(json, "parentSnapshotIds").orEmpty().split(',').filter(String::isNotBlank)
        } else {
            listOfNotNull(parent)
        }
        return DriveSnapshotManifest(
            protocolVersion = protocol,
            datasetId = string(json, "datasetId"),
            snapshotId = string(json, "snapshotId"),
            parentSnapshotId = parents.firstOrNull(),
            parentSnapshotIds = parents,
            generation = number(json, "generation"),
            sourceDeviceId = string(json, "sourceDeviceId"),
            schemaVersion = number(json, "schemaVersion").toInt(),
            minimumAppVersionCode = number(json, "minimumAppVersionCode").toInt(),
            createdAtEpochMillis = number(json, "createdAtEpochMillis"),
            payloadSha256 = string(json, "payloadSha256"),
            kdfIterations = number(json, "kdfIterations").toInt(),
            kind = SnapshotKind.valueOf(string(json, "kind")),
        )
    }

    private fun StringBuilder.field(name: String, value: String, last: Boolean = false) {
        append('"').append(name).append("\":\"").append(escape(value)).append('"')
        if (!last) append(',')
    }

    private fun StringBuilder.nullableField(name: String, value: String?) {
        append('"').append(name).append("\":")
        if (value == null) append("null") else append('"').append(escape(value)).append('"')
        append(',')
    }

    private fun StringBuilder.field(name: String, value: Number) {
        append('"').append(name).append("\":").append(value).append(',')
    }

    private fun string(json: String, key: String): String = nullableString(json, key)
        ?: throw IllegalArgumentException("Field $key tidak ditemukan")

    private fun nullableString(json: String, key: String): String? {
        val start = valueStart(json, key)
        if (json.startsWith("null", start)) return null
        require(json.getOrNull(start) == '"') { "Field $key bukan string" }
        val result = StringBuilder()
        var index = start + 1
        while (index < json.length) {
            val char = json[index++]
            if (char == '"') return result.toString()
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
                        val hex = json.substring(index, index + 4)
                        index += 4
                        hex.toInt(16).toChar()
                    }
                    else -> error("Escape JSON tidak valid")
                },
            )
        }
        error("String JSON tidak lengkap")
    }

    private fun optionalString(json: String, key: String): String? = runCatching { nullableString(json, key) }.getOrNull()

    private fun number(json: String, key: String): Long {
        val start = valueStart(json, key)
        val end = generateSequence(start) { it + 1 }
            .takeWhile { it < json.length && (json[it].isDigit() || json[it] == '-') }
            .lastOrNull()?.plus(1) ?: start
        return json.substring(start, end).toLong()
    }

    private fun valueStart(json: String, key: String): Int {
        val marker = "\"${escape(key)}\""
        val keyIndex = json.indexOf(marker)
        require(keyIndex >= 0) { "Field $key tidak ditemukan" }
        val colon = json.indexOf(':', keyIndex + marker.length)
        require(colon >= 0) { "Field $key tidak valid" }
        var result = colon + 1
        while (result < json.length && json[result].isWhitespace()) result++
        return result
    }

    internal fun escape(value: String): String = buildString {
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
    }
}
