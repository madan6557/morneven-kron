package com.morneven.kron.sync

enum class ConflictItemStatus {
    DEVICE_ONLY,
    DRIVE_ONLY,
    IDENTICAL,
    DIFFERENT,
    INTEGRITY_PROBLEM,
}

enum class ConflictChoice {
    DEVICE,
    DRIVE,
}

data class ConflictEventRecord(
    val eventId: String,
    val type: String,
    val title: String,
    val amount: Long,
    val effectiveEpochDay: Long,
    val accountName: String,
    val reversed: Boolean,
    val receiptCount: Int,
    val actor: String,
    val deviceId: String,
    val changedAtEpochMillis: Long,
    val canonicalHash: String,
    val signatureHash: String,
)

data class ConflictMutableRecord(
    val entityType: String,
    val syncId: String,
    val label: String,
    val revision: Long,
    val canonicalHash: String,
    val updatedAtEpochMillis: Long = 0,
    val lastWriterId: String = "",
)

data class ConflictDataset(
    val snapshotId: String?,
    val events: List<ConflictEventRecord>,
    val mutableEntities: List<ConflictMutableRecord>,
)

data class ConflictItem(
    val key: String,
    val entityType: String,
    val status: ConflictItemStatus,
    val label: String,
    val amount: Long? = null,
    val device: ConflictEventRecord? = null,
    val drive: ConflictEventRecord? = null,
    val deviceMutable: ConflictMutableRecord? = null,
    val driveMutable: ConflictMutableRecord? = null,
    val automaticChoice: ConflictChoice? = null,
)

data class ConflictPreview(
    val localSnapshotId: String?,
    val remoteSnapshotId: String?,
    val items: List<ConflictItem>,
) {
    val deviceOnlyCount: Int get() = items.count { it.status == ConflictItemStatus.DEVICE_ONLY }
    val driveOnlyCount: Int get() = items.count { it.status == ConflictItemStatus.DRIVE_ONLY }
    val identicalCount: Int get() = items.count { it.status == ConflictItemStatus.IDENTICAL }
    val differentCount: Int get() = items.count { it.status == ConflictItemStatus.DIFFERENT }
    val integrityProblemCount: Int get() = items.count { it.status == ConflictItemStatus.INTEGRITY_PROBLEM }
    val requiresChoices: Boolean get() = items.any {
        it.status == ConflictItemStatus.DIFFERENT && it.automaticChoice == null
    }
}

data class MergePlan(
    val expectedRemoteSnapshotId: String,
    val parentSnapshotIds: List<String>,
    val choices: Map<String, ConflictChoice>,
)

object ConflictPreviewBuilder {
    fun build(
        device: ConflictDataset,
        drive: ConflictDataset,
        base: ConflictDataset? = null,
    ): ConflictPreview {
        val items = mutableListOf<ConflictItem>()
        val deviceEvents = uniqueEvents(device.events)
        val driveEvents = uniqueEvents(drive.events)
        (deviceEvents.keys + driveEvents.keys).toSortedSet().forEach { eventId ->
            val local = deviceEvents[eventId]
            val remote = driveEvents[eventId]
            items += when {
                local == null -> eventItem(requireNotNull(remote), ConflictItemStatus.DRIVE_ONLY, drive = remote)
                remote == null -> eventItem(local, ConflictItemStatus.DEVICE_ONLY, device = local)
                local.canonicalHash == remote.canonicalHash && local.signatureHash == remote.signatureHash ->
                    eventItem(local, ConflictItemStatus.IDENTICAL, device = local, drive = remote)
                else -> eventItem(
                    local,
                    ConflictItemStatus.INTEGRITY_PROBLEM,
                    device = local,
                    drive = remote,
                )
            }
        }

        val baseMutable = base?.mutableEntities.orEmpty().associateBy(::mutableKey)
        val localMutable = uniqueMutable(device.mutableEntities)
        val remoteMutable = uniqueMutable(drive.mutableEntities)
        (localMutable.keys + remoteMutable.keys).toSortedSet().forEach { key ->
            val local = localMutable[key]
            val remote = remoteMutable[key]
            val baseValue = baseMutable[key]
            val status: ConflictItemStatus
            val automatic: ConflictChoice?
            when {
                local == null -> {
                    status = ConflictItemStatus.DRIVE_ONLY
                    automatic = ConflictChoice.DRIVE
                }
                remote == null -> {
                    status = ConflictItemStatus.DEVICE_ONLY
                    automatic = ConflictChoice.DEVICE
                }
                local.canonicalHash == remote.canonicalHash -> {
                    status = ConflictItemStatus.IDENTICAL
                    automatic = null
                }
                baseValue?.canonicalHash == local.canonicalHash -> {
                    status = ConflictItemStatus.DIFFERENT
                    automatic = ConflictChoice.DRIVE
                }
                baseValue?.canonicalHash == remote.canonicalHash -> {
                    status = ConflictItemStatus.DIFFERENT
                    automatic = ConflictChoice.DEVICE
                }
                else -> {
                    status = ConflictItemStatus.DIFFERENT
                    automatic = null
                }
            }
            val representative = local ?: requireNotNull(remote)
            items += ConflictItem(
                key = key,
                entityType = representative.entityType,
                status = status,
                label = representative.label,
                deviceMutable = local,
                driveMutable = remote,
                automaticChoice = automatic,
            )
        }
        return ConflictPreview(device.snapshotId, drive.snapshotId, items)
    }

    fun mergePlan(preview: ConflictPreview, choices: Map<String, ConflictChoice>): MergePlan {
        require(preview.integrityProblemCount == 0) { "Masalah integritas harus dikarantina, bukan digabung" }
        val remoteId = requireNotNull(preview.remoteSnapshotId) { "Snapshot Drive tidak tersedia" }
        val resolved = buildMap {
            preview.items.forEach { item ->
                val choice = item.automaticChoice ?: choices[item.key]
                if (item.status == ConflictItemStatus.DIFFERENT) {
                    requireNotNull(choice) { "Pilihan konflik belum lengkap" }
                    put(item.key, choice)
                }
            }
        }
        return MergePlan(
            expectedRemoteSnapshotId = remoteId,
            parentSnapshotIds = listOfNotNull(preview.localSnapshotId, preview.remoteSnapshotId).distinct(),
            choices = resolved,
        )
    }

    private fun uniqueEvents(values: List<ConflictEventRecord>): Map<String, ConflictEventRecord> = values
        .associateBy(ConflictEventRecord::eventId)
        .also { require(it.size == values.size) { "Dataset memiliki UUID event duplikat" } }

    private fun uniqueMutable(values: List<ConflictMutableRecord>): Map<String, ConflictMutableRecord> = values
        .associateBy(::mutableKey)
        .also { require(it.size == values.size) { "Dataset memiliki sync ID duplikat" } }

    private fun mutableKey(value: ConflictMutableRecord): String = "${value.entityType}:${value.syncId}"

    private fun eventItem(
        representative: ConflictEventRecord,
        status: ConflictItemStatus,
        device: ConflictEventRecord? = null,
        drive: ConflictEventRecord? = null,
    ): ConflictItem = ConflictItem(
        key = "event:${representative.eventId}",
        entityType = "TRANSACTION",
        status = status,
        label = representative.title,
        amount = representative.amount,
        device = device,
        drive = drive,
        automaticChoice = when (status) {
            ConflictItemStatus.DEVICE_ONLY -> ConflictChoice.DEVICE
            ConflictItemStatus.DRIVE_ONLY -> ConflictChoice.DRIVE
            else -> null
        },
    )
}
