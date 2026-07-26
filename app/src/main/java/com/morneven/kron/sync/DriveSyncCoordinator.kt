package com.morneven.kron.sync

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

internal sealed interface SyncDecision {
    data class Upload(val parentSnapshotId: String?) : SyncDecision
    data class Download(val remote: RemoteDriveSnapshot) : SyncDecision
    data class Conflict(
        val reason: SyncConflictReason,
        val remote: RemoteDriveSnapshot?,
        val remoteHeads: List<RemoteDriveSnapshot> = listOfNotNull(remote),
    ) : SyncDecision
    data object NoChanges : SyncDecision
    data object NoData : SyncDecision
}

internal object SnapshotDag {
    data class Inspection(
        val heads: List<RemoteDriveSnapshot>,
        val valid: Boolean,
    )

    fun inspect(snapshots: List<RemoteDriveSnapshot>, datasetId: String): Inspection {
        val active = snapshots.filter {
            it.manifest.kind == SnapshotKind.ACTIVE && it.manifest.datasetId == datasetId
        }
        if (active.isEmpty()) return Inspection(emptyList(), valid = true)
        val duplicateIds = active.groupBy { it.manifest.snapshotId }.filterValues { it.size > 1 }.values.flatten()
        val referenced = active.flatMapTo(mutableSetOf()) { it.manifest.parentSnapshotIds }
        val heads = active.filter { it.manifest.snapshotId !in referenced }
        val ids = active.mapTo(mutableSetOf()) { it.manifest.snapshotId }
        val indegree = active.associate { snapshot ->
            snapshot.manifest.snapshotId to snapshot.manifest.parentSnapshotIds.count(ids::contains)
        }.toMutableMap()
        val children = buildMap<String, MutableList<String>> {
            active.forEach { snapshot ->
                snapshot.manifest.parentSnapshotIds.filter(ids::contains).forEach { parent ->
                    getOrPut(parent) { mutableListOf() }.add(snapshot.manifest.snapshotId)
                }
            }
        }
        val queue = ArrayDeque(indegree.filterValues { it == 0 }.keys)
        var visited = 0
        while (queue.isNotEmpty()) {
            val parent = queue.removeFirst()
            visited++
            children[parent].orEmpty().forEach { child ->
                val remaining = requireNotNull(indegree[child]) - 1
                indegree[child] = remaining
                if (remaining == 0) queue.addLast(child)
            }
        }
        val valid = duplicateIds.isEmpty() && visited == ids.size
        val visibleHeads = (if (valid) heads else active)
            .distinctBy(RemoteDriveSnapshot::fileId)
            .sortedWith(compareBy({ it.manifest.generation }, { it.createdAt }, { it.manifest.snapshotId }))
        return Inspection(
            heads = visibleHeads,
            valid = valid,
        )
    }

    fun heads(snapshots: List<RemoteDriveSnapshot>, datasetId: String): List<RemoteDriveSnapshot> =
        inspect(snapshots, datasetId).heads
}

internal object DriveSyncDecisionEngine {
    fun decide(
        state: SyncState,
        local: LocalDatasetSnapshot,
        account: GoogleAccountIdentity,
        remoteFiles: List<RemoteDriveSnapshot>,
    ): SyncDecision {
        val active = remoteFiles.filter { it.manifest.kind == SnapshotKind.ACTIVE }
        val datasets = active.groupBy { it.manifest.datasetId }
        val onlyDatasetDag = datasets.keys.singleOrNull()?.let { SnapshotDag.inspect(active, it) }
        if (onlyDatasetDag != null && (!onlyDatasetDag.valid || onlyDatasetDag.heads.size > 1)) {
            return SyncDecision.Conflict(
                SyncConflictReason.REMOTE_FORK_DETECTED,
                onlyDatasetDag.heads.last(),
                onlyDatasetDag.heads,
            )
        }
        val latestAny = onlyDatasetDag?.heads?.singleOrNull() ?: active.maxWithOrNull(snapshotComparator)
        val remoteHeads = datasets.keys.flatMap { SnapshotDag.inspect(active, it).heads }
        val localDag = SnapshotDag.inspect(active, local.datasetId)
        val localHeads = localDag.heads
        if (!localDag.valid || localHeads.size > 1) {
            return SyncDecision.Conflict(
                SyncConflictReason.REMOTE_FORK_DETECTED,
                localHeads.last(),
                localHeads,
            )
        }
        val latestForLocal = localHeads.singleOrNull()

        if (state.accountSubject != null && state.accountSubject != account.subjectId) {
            return if (local.hasFinancialData) {
                SyncDecision.Conflict(SyncConflictReason.ACCOUNT_CHANGED, latestAny, remoteHeads)
            } else if (datasets.size == 1) {
                SyncDecision.Download(requireNotNull(latestAny))
            } else if (datasets.size > 1) {
                SyncDecision.Conflict(SyncConflictReason.DATASET_MISMATCH, latestAny, remoteHeads)
            } else {
                SyncDecision.NoData
            }
        }

        if (latestForLocal == null) {
            if (active.isEmpty()) {
                return if (local.hasFinancialData) SyncDecision.Upload(null) else SyncDecision.NoData
            }
            return if (!local.hasFinancialData && datasets.size == 1) {
                SyncDecision.Download(requireNotNull(latestAny))
            } else {
                SyncDecision.Conflict(
                    if (state.lastSnapshotId == null) {
                        SyncConflictReason.FIRST_CONNECTION_WITH_TWO_DATASETS
                    } else {
                        SyncConflictReason.DATASET_MISMATCH
                    },
                    latestAny,
                    remoteHeads,
                )
            }
        }

        if (state.lastSnapshotId == null) {
            return if (local.hasFinancialData) {
                SyncDecision.Conflict(SyncConflictReason.FIRST_CONNECTION_WITH_TWO_DATASETS, latestForLocal)
            } else {
                SyncDecision.Download(latestForLocal)
            }
        }

        val localChanged = local.generation != state.lastSyncedGeneration
        val remoteChanged = latestForLocal.manifest.snapshotId != state.lastSnapshotId
        return when {
            localChanged && remoteChanged -> SyncDecision.Conflict(
                SyncConflictReason.BOTH_SIDES_CHANGED,
                latestForLocal,
            )
            localChanged -> SyncDecision.Upload(latestForLocal.manifest.snapshotId)
            remoteChanged -> SyncDecision.Download(latestForLocal)
            else -> SyncDecision.NoChanges
        }
    }

    private val snapshotComparator = compareBy<RemoteDriveSnapshot>(
        { it.manifest.generation },
        { it.createdAt },
        { it.manifest.snapshotId },
    )
}

internal object SnapshotRetention {
    fun filesToDelete(
        snapshots: List<RemoteDriveSnapshot>,
        datasetId: String,
        keep: Int = 10,
        protectedFileIds: Set<String> = emptySet(),
        protectedSnapshotIds: Set<String> = emptySet(),
    ): List<RemoteDriveSnapshot> {
        require(keep >= 1)
        val datasetSnapshots = snapshots.filter { it.manifest.datasetId == datasetId }
        val activeHeads = SnapshotDag.heads(datasetSnapshots, datasetId)
        val requiredSnapshotIds = buildSet {
            addAll(protectedSnapshotIds)
            activeHeads.forEach { head ->
                add(head.manifest.snapshotId)
                addAll(head.manifest.parentSnapshotIds)
            }
        }
        val ordered = datasetSnapshots.sortedWith(
            compareByDescending<RemoteDriveSnapshot> { it.createdAt }
                .thenByDescending { it.manifest.generation }
                .thenByDescending { it.manifest.snapshotId },
        )
        val protected = ordered.filter {
            it.fileId in protectedFileIds || it.manifest.snapshotId in requiredSnapshotIds
        }
        val keptFileIds = buildSet {
            protected.forEach { add(it.fileId) }
            ordered.asSequence()
                .filterNot { it.fileId in this }
                .take((keep - size).coerceAtLeast(0))
                .forEach { add(it.fileId) }
        }
        return ordered.filterNot { it.fileId in keptFileIds }
    }
}

class DriveSyncCoordinator(
    private val authorization: DriveAuthorizationSession,
    private val drive: DriveAppDataClient,
    private val local: LocalSnapshotSource,
    private val stateStore: SyncStateStore,
    private val secretProvider: SyncSecretProvider,
    private val cryptor: DriveSnapshotCryptor = AesGcmDriveSnapshotCryptor(),
    private val currentAppVersionCode: Int,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val snapshotIdFactory: () -> String = { UUID.randomUUID().toString() },
    private val syncMutex: Mutex = Mutex(),
) {
    companion object {
        private const val SYNC_TIMEOUT_MILLIS = 180_000L
    }
    suspend fun syncNow(): SyncRunResult = syncMutex.withLock { syncNowLocked() }

    suspend fun validatePassphrase(remoteHint: RemoteDriveSnapshot? = null): SyncRunResult = syncMutex.withLock {
        val state = stateStore.read()
        if (state.status == SyncStatus.DISABLED) return@withLock SyncRunResult.Disabled
        if (state.status == SyncStatus.RESTART_REQUIRED) {
            return@withLock SyncRunResult.RestartRequired(state.lastSnapshotId ?: "pending-restore")
        }
        if (state.disabledDueToBilling) return@withLock SyncRunResult.FreeOnlyBlocked
        val tokenResult = authorization.accessToken(interactive = false)
        if (tokenResult !is DriveAccessTokenResult.Granted) return@withLock handleTokenFailure(tokenResult)

        try {
            val remote = remoteHint ?: drive.listSnapshots(tokenResult.accessToken)
                .filter { it.manifest.kind == SnapshotKind.ACTIVE }
                .let { active ->
                    active.firstOrNull { it.manifest.snapshotId == state.lastSnapshotId }
                        ?: active.filter { it.manifest.datasetId == state.datasetId }
                            .maxWithOrNull(compareBy<RemoteDriveSnapshot>({ it.manifest.generation }, { it.createdAt }))
                }
                ?: return@withLock SyncRunResult.NoData
            val passphrase = secretProvider.acquirePassphrase() ?: return@withLock passphraseRequired()
            val envelope = drive.downloadSnapshot(tokenResult.accessToken, remote.fileId)
            try {
                val decrypted = cryptor.decrypt(envelope, passphrase)
                try {
                    require(decrypted.manifest == remote.manifest) { "Metadata snapshot Drive tidak cocok" }
                } finally {
                    decrypted.payload.fill(0)
                }
            } finally {
                envelope.fill(0)
                passphrase.fill('\u0000')
            }
            stateStore.update {
                it.copy(
                    status = SyncStatus.SYNCED,
                    lastError = null,
                    accountSubject = tokenResult.account.subjectId,
                    accountEmail = tokenResult.account.email,
                )
            }
            SyncRunResult.NoChanges
        } catch (error: Throwable) {
            handleFailure(error)
        }
    }

    private suspend fun syncNowLocked(): SyncRunResult {
        val initialState = stateStore.read()
        if (initialState.status == SyncStatus.DISABLED) return SyncRunResult.Disabled
        if (initialState.status == SyncStatus.RESTART_REQUIRED) {
            return SyncRunResult.RestartRequired(initialState.lastSnapshotId ?: "pending-restore")
        }
        if (initialState.disabledDueToBilling) return SyncRunResult.FreeOnlyBlocked
        if (initialState.status == SyncStatus.SYNCING) {
            stateStore.update { it.copy(status = SyncStatus.ERROR, lastError = "Sinkron sebelumnya terputus") }
        }
        return withTimeout(SYNC_TIMEOUT_MILLIS) {
            val tokenResult = authorization.accessToken(interactive = false)
            if (tokenResult !is DriveAccessTokenResult.Granted) return@withTimeout handleTokenFailure(tokenResult)

            try {
            stateStore.update {
                it.copy(
                    status = SyncStatus.SYNCING,
                    lastError = null,
                )
            }
            val descriptor = local.describe()
            val state = stateStore.update {
                it.copy(
                    datasetId = descriptor.datasetId,
                    localGeneration = if (it.datasetId == descriptor.datasetId) {
                        maxOf(it.localGeneration, descriptor.generation)
                    } else {
                        descriptor.generation
                    },
                )
            }
            val remote = drive.listSnapshots(tokenResult.accessToken)
            when (val decision = DriveSyncDecisionEngine.decide(state, descriptor, tokenResult.account, remote)) {
                is SyncDecision.Upload -> {
                    uploadActive(tokenResult, descriptor, decision.parentSnapshotId)
                }
                is SyncDecision.Download -> {
                    downloadAndApply(tokenResult, decision.remote)
                }
                is SyncDecision.Conflict -> {
                    recordConflict(state, descriptor, decision)
                }
                SyncDecision.NoChanges -> {
                    stateStore.update {
                        it.copy(
                            status = SyncStatus.SYNCED,
                            lastError = null,
                            accountSubject = tokenResult.account.subjectId,
                            accountEmail = tokenResult.account.email,
                        )
                    }
                    SyncRunResult.NoChanges
                }
                SyncDecision.NoData -> {
                    stateStore.update {
                        it.copy(
                            status = SyncStatus.SYNCED,
                            lastError = null,
                            accountSubject = tokenResult.account.subjectId,
                            accountEmail = tokenResult.account.email,
                        )
                    }
                    SyncRunResult.NoData
                }
            }
        } catch (error: Throwable) {
            handleFailure(error)
        }
        }
    }

    suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): SyncRunResult = syncMutex.withLock { resolveConflictLocked(conflict, resolution) }

    suspend fun previewConflict(conflict: SyncConflict): ConflictPreviewResult = syncMutex.withLock {
        if (conflict.remoteHeads.size > 1 || conflict.reason == SyncConflictReason.REMOTE_FORK_DETECTED) {
            return@withLock ConflictPreviewResult.Error(
                "Drive memiliki ${conflict.remoteHeads.size} kandidat snapshot aktif. Semua tindakan dikunci sampai diff multi-head tersedia.",
            )
        }
        val token = authorization.accessToken(interactive = false)
        if (token !is DriveAccessTokenResult.Granted) {
            return@withLock when (handleTokenFailure(token)) {
                SyncRunResult.AuthorizationRequired -> ConflictPreviewResult.AuthorizationRequired
                else -> ConflictPreviewResult.Error("Otorisasi Drive tidak tersedia")
            }
        }
        val expected = conflict.remote
            ?: return@withLock ConflictPreviewResult.Error("Snapshot Drive untuk konflik tidak tersedia")
        try {
            val before = drive.listSnapshots(token.accessToken)
            val remote = before.firstOrNull { it.fileId == expected.fileId }
                ?: return@withLock stalePreview(conflict)
            val dagBefore = SnapshotDag.inspect(before, remote.manifest.datasetId)
            if (!dagBefore.valid || dagBefore.heads.singleOrNull()?.fileId != remote.fileId) {
                return@withLock stalePreview(conflict)
            }

            val passphrase = secretProvider.acquirePassphrase()
                ?: return@withLock ConflictPreviewResult.PassphraseRequired
            val envelope = drive.downloadSnapshot(token.accessToken, remote.fileId)
            try {
                val decrypted = cryptor.decrypt(envelope, passphrase)
                require(decrypted.manifest == remote.manifest) { "Metadata snapshot Drive tidak cocok" }
                val preview = try {
                    local.previewRemotePayload(decrypted.payload, decrypted.manifest)
                } finally {
                    decrypted.payload.fill(0)
                }
                val dagAfter = SnapshotDag.inspect(drive.listSnapshots(token.accessToken), remote.manifest.datasetId)
                if (!dagAfter.valid || dagAfter.heads.singleOrNull()?.fileId != remote.fileId) {
                    return@withLock stalePreview(conflict)
                }
                ConflictPreviewResult.Ready(preview)
            } finally {
                envelope.fill(0)
                passphrase.fill('\u0000')
            }
        } catch (error: Throwable) {
            when (val failure = handleFailure(error)) {
                SyncRunResult.AuthorizationRequired -> ConflictPreviewResult.AuthorizationRequired
                SyncRunResult.PassphraseRequired -> ConflictPreviewResult.PassphraseRequired
                is SyncRunResult.Error -> ConflictPreviewResult.Error(failure.message)
                else -> ConflictPreviewResult.Error("Preview konflik tidak dapat dibuat")
            }
        }
    }

    private suspend fun resolveConflictLocked(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): SyncRunResult {
        if (conflict.remoteHeads.size > 1 || conflict.reason == SyncConflictReason.REMOTE_FORK_DETECTED) {
            return SyncRunResult.Conflict(conflict)
        }
        val state = stateStore.read()
        if (state.status == SyncStatus.RESTART_REQUIRED) {
            return SyncRunResult.RestartRequired(state.lastSnapshotId ?: "pending-restore")
        }
        if (state.disabledDueToBilling) return SyncRunResult.FreeOnlyBlocked
        val tokenResult = authorization.accessToken(interactive = false)
        if (tokenResult !is DriveAccessTokenResult.Granted) return handleTokenFailure(tokenResult)

        return try {
            val remoteFiles = drive.listSnapshots(tokenResult.accessToken)
            val expectedRemote = conflict.remote?.let { expected ->
                remoteFiles.firstOrNull { it.fileId == expected.fileId }
                    ?: return staleConflict(conflict)
            }
            if (expectedRemote != null) {
                val currentDag = SnapshotDag.inspect(remoteFiles, expectedRemote.manifest.datasetId)
                if (!currentDag.valid || currentDag.heads.singleOrNull()?.fileId != expectedRemote.fileId) {
                    return staleConflict(conflict.copy(remoteHeads = currentDag.heads))
                }
            }

            when (resolution) {
                ConflictResolution.KEEP_BOTH -> {
                    requireNotNull(expectedRemote) { "Tidak ada snapshot Drive untuk dipertahankan" }
                    val recovery = uploadRecovery(tokenResult, local.describe(), state.lastSnapshotId)
                    if (recovery !is SyncRunResult.Synchronized) return recovery
                    downloadAndApply(tokenResult, expectedRemote)
                }
                ConflictResolution.USE_THIS_DEVICE -> uploadActive(
                    tokenResult,
                    local.describe(),
                    expectedRemote?.manifest?.snapshotId,
                )
                ConflictResolution.USE_DRIVE -> {
                    requireNotNull(expectedRemote) { "Tidak ada snapshot Drive yang dapat dipulihkan" }
                    if (conflict.reason != SyncConflictReason.ACCOUNT_CHANGED) {
                        val recovery = uploadRecovery(tokenResult, local.describe(), state.lastSnapshotId)
                        if (recovery !is SyncRunResult.Synchronized) return recovery
                    }
                    downloadAndApply(tokenResult, expectedRemote)
                }
            }
        } catch (error: Throwable) {
            handleFailure(error)
        }
    }

    suspend fun disable() = syncMutex.withLock {
        stateStore.update {
            it.copy(status = SyncStatus.DISABLED, conflictRemoteFileId = null, lastError = null)
        }
    }

    suspend fun disconnect() = syncMutex.withLock {
        try {
            authorization.disconnect()
        } finally {
            stateStore.update {
                it.copy(
                    status = SyncStatus.DISCONNECTED,
                    accountSubject = null,
                    accountEmail = null,
                    conflictRemoteFileId = null,
                    lastError = null,
                )
            }
        }
    }

    suspend fun clearSyncAccountState() = syncMutex.withLock {
        stateStore.update {
            it.copy(
                accountSubject = null,
                accountEmail = null,
                conflictRemoteFileId = null,
            )
        }
    }

    suspend fun downloadLatestSnapshot(): SyncRunResult = syncMutex.withLock {
        val state = stateStore.read()
        if (state.status == SyncStatus.RESTART_REQUIRED) {
            return SyncRunResult.RestartRequired(state.lastSnapshotId ?: "pending-restore")
        }
        if (state.disabledDueToBilling) return SyncRunResult.FreeOnlyBlocked
        stateStore.update { it.copy(status = SyncStatus.SYNCING, lastError = null) }
        val tokenResult = authorization.accessToken(interactive = false)
        if (tokenResult !is DriveAccessTokenResult.Granted) return handleTokenFailure(tokenResult)
        try {
            val remoteFiles = drive.listSnapshots(tokenResult.accessToken)
            val latest = remoteFiles
                .filter { it.manifest.kind == SnapshotKind.ACTIVE }
                .maxWithOrNull(compareBy<RemoteDriveSnapshot>({ it.manifest.generation }, { it.createdAt }))
            if (latest == null) return SyncRunResult.NoData
            downloadAndApply(tokenResult, latest)
        } catch (error: Throwable) {
            handleFailure(error)
        }
    }

    suspend fun clearDriveData(): SyncRunResult = syncMutex.withLock {
        val state = stateStore.read()
        if (state.status == SyncStatus.DISCONNECTED || state.status == SyncStatus.DISABLED) {
            return SyncRunResult.NoChanges
        }
        val tokenResult = authorization.accessToken(interactive = false)
        if (tokenResult !is DriveAccessTokenResult.Granted) return handleTokenFailure(tokenResult)
        try {
            drive.listSnapshots(tokenResult.accessToken).forEach {
                drive.deleteSnapshot(tokenResult.accessToken, it.fileId)
            }
        } catch (error: Throwable) {
            return handleFailure(error)
        }
        try {
            authorization.disconnect()
        } finally {
            stateStore.update {
                it.copy(
                    status = SyncStatus.DISCONNECTED,
                    lastSnapshotId = null,
                    parentSnapshotId = null,
                    lastSyncedGeneration = -1,
                    conflictRemoteFileId = null,
                    lastError = null,
                    accountSubject = null,
                    accountEmail = null,
                )
            }
        }
        SyncRunResult.NoChanges
    }

    private suspend fun uploadActive(
        token: DriveAccessTokenResult.Granted,
        descriptor: LocalDatasetSnapshot,
        parentSnapshotId: String?,
    ): SyncRunResult = upload(token, descriptor, parentSnapshotId, SnapshotKind.ACTIVE)

    private suspend fun uploadRecovery(
        token: DriveAccessTokenResult.Granted,
        descriptor: LocalDatasetSnapshot,
        parentSnapshotId: String?,
    ): SyncRunResult = upload(token, descriptor, parentSnapshotId, SnapshotKind.RECOVERY)

    private suspend fun upload(
        token: DriveAccessTokenResult.Granted,
        descriptor: LocalDatasetSnapshot,
        parentSnapshotId: String?,
        kind: SnapshotKind,
    ): SyncRunResult {
        val passphrase = secretProvider.acquirePassphrase() ?: return passphraseRequired()
        val payload = local.exportSnapshotPayload()
        try {
            val manifest = DriveSnapshotManifest(
                datasetId = descriptor.datasetId,
                snapshotId = snapshotIdFactory(),
                parentSnapshotId = parentSnapshotId,
                generation = descriptor.generation,
                sourceDeviceId = stateStore.read().deviceId,
                schemaVersion = descriptor.schemaVersion,
                minimumAppVersionCode = currentAppVersionCode,
                createdAtEpochMillis = nowEpochMillis(),
                payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
                kind = kind,
            )
            val envelope = cryptor.encrypt(manifest, payload, passphrase)
            try {
                val uploaded = drive.uploadSnapshot(token.accessToken, manifest, envelope)
                if (kind == SnapshotKind.ACTIVE) {
                    stateStore.update {
                        it.copy(
                            datasetId = descriptor.datasetId,
                            localGeneration = if (it.datasetId == descriptor.datasetId) {
                                maxOf(it.localGeneration, descriptor.generation)
                            } else {
                                descriptor.generation
                            },
                            lastSyncedGeneration = descriptor.generation,
                            parentSnapshotId = parentSnapshotId,
                            lastSnapshotId = manifest.snapshotId,
                            lastSyncedAtEpochMillis = nowEpochMillis(),
                            status = SyncStatus.SYNCED,
                            lastError = null,
                            conflictRemoteFileId = null,
                            accountSubject = token.account.subjectId,
                            accountEmail = token.account.email,
                        )
                    }
                }
                enforceRetention(token.accessToken, descriptor.datasetId)
                return SyncRunResult.Synchronized(uploaded.manifest.snapshotId, uploaded = true)
            } finally {
                envelope.fill(0)
            }
        } finally {
            payload.fill(0)
            passphrase.fill('\u0000')
        }
    }

    private suspend fun downloadAndApply(
        token: DriveAccessTokenResult.Granted,
        remote: RemoteDriveSnapshot,
    ): SyncRunResult {
        if (remote.manifest.minimumAppVersionCode > currentAppVersionCode) {
            return recordError("Perbarui KRON sebelum memulihkan snapshot ini", retryable = false)
        }
        val passphrase = secretProvider.acquirePassphrase() ?: return passphraseRequired()
        val envelope = drive.downloadSnapshot(token.accessToken, remote.fileId)
        try {
            val decrypted = cryptor.decrypt(envelope, passphrase)
            require(decrypted.manifest == remote.manifest) { "Metadata snapshot Drive tidak cocok" }
            val outcome = try {
                local.applyRemoteAtomically(decrypted.payload, decrypted.manifest, token.account)
            } finally {
                decrypted.payload.fill(0)
            }
            stateStore.update {
                it.copy(
                    datasetId = remote.manifest.datasetId,
                    localGeneration = remote.manifest.generation,
                    lastSyncedGeneration = remote.manifest.generation,
                    parentSnapshotId = remote.manifest.parentSnapshotId,
                    lastSnapshotId = remote.manifest.snapshotId,
                    lastSyncedAtEpochMillis = nowEpochMillis(),
                    status = if (outcome == LocalApplyOutcome.RESTART_REQUIRED) {
                        SyncStatus.RESTART_REQUIRED
                    } else {
                        SyncStatus.SYNCED
                    },
                    lastError = null,
                    conflictRemoteFileId = null,
                    accountSubject = token.account.subjectId,
                    accountEmail = token.account.email,
                )
            }
            DataRefreshBridge.emit()
            return if (outcome == LocalApplyOutcome.RESTART_REQUIRED) {
                SyncRunResult.RestartRequired(remote.manifest.snapshotId)
            } else {
                SyncRunResult.Synchronized(remote.manifest.snapshotId, uploaded = false)
            }
        } finally {
            envelope.fill(0)
            passphrase.fill('\u0000')
        }
    }

    private suspend fun enforceRetention(accessToken: String, datasetId: String) {
        try {
            val state = stateStore.read()
            SnapshotRetention.filesToDelete(
                snapshots = drive.listSnapshots(accessToken),
                datasetId = datasetId,
                protectedFileIds = setOfNotNull(state.conflictRemoteFileId),
                protectedSnapshotIds = setOfNotNull(state.lastSnapshotId, state.parentSnapshotId),
            )
                .forEach { drive.deleteSnapshot(accessToken, it.fileId) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Retention is best effort and must not turn a successful upload into a failure.
        }
    }

    private suspend fun recordConflict(
        state: SyncState,
        localDescriptor: LocalDatasetSnapshot,
        decision: SyncDecision.Conflict,
    ): SyncRunResult {
        val conflict = SyncConflict(
            reason = decision.reason,
            local = localDescriptor,
            remote = decision.remote,
            expectedLastSnapshotId = state.lastSnapshotId,
            remoteHeads = decision.remoteHeads,
        )
        stateStore.update {
            it.copy(
                status = SyncStatus.CONFLICT,
                lastError = null,
                conflictRemoteFileId = decision.remote?.fileId,
            )
        }
        return SyncRunResult.Conflict(conflict)
    }

    private suspend fun staleConflict(previous: SyncConflict): SyncRunResult {
        val updated = previous.copy(reason = SyncConflictReason.REMOTE_CHANGED_DURING_RESOLUTION)
        stateStore.update {
            it.copy(
                status = SyncStatus.CONFLICT,
                lastError = "Snapshot Drive berubah. Tinjau konflik terbaru.",
                conflictRemoteFileId = null,
            )
        }
        return SyncRunResult.Conflict(updated)
    }

    private suspend fun stalePreview(previous: SyncConflict): ConflictPreviewResult.Stale {
        val updated = previous.copy(reason = SyncConflictReason.REMOTE_CHANGED_DURING_RESOLUTION)
        stateStore.update {
            it.copy(
                status = SyncStatus.CONFLICT,
                lastError = "Snapshot Drive berubah. Tinjau konflik terbaru.",
                conflictRemoteFileId = null,
            )
        }
        return ConflictPreviewResult.Stale(updated)
    }

    private suspend fun handleTokenFailure(result: DriveAccessTokenResult): SyncRunResult = when (result) {
        DriveAccessTokenResult.Disconnected -> {
            stateStore.update { it.copy(status = SyncStatus.DISCONNECTED, lastError = null) }
            SyncRunResult.AuthorizationRequired
        }
        is DriveAccessTokenResult.UserActionRequired -> {
            stateStore.update { it.copy(status = SyncStatus.AUTHORIZATION_REQUIRED, lastError = null) }
            SyncRunResult.AuthorizationRequired
        }
        is DriveAccessTokenResult.Failed -> recordError(result.message, result.retryable)
        is DriveAccessTokenResult.Granted -> error("Token sudah diberikan")
    }

    private suspend fun handleFailure(error: Throwable): SyncRunResult {
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        return when (error) {
            is InvalidDrivePassphraseException -> passphraseRequired(error.message)
            is DriveBillingRequiredException -> {
                stateStore.update {
                    it.copy(
                        disabledDueToBilling = true,
                        status = SyncStatus.FREE_ONLY_BLOCKED,
                        lastError = "Sinkronisasi Drive tidak tersedia tanpa biaya",
                        conflictRemoteFileId = null,
                    )
                }
                SyncRunResult.FreeOnlyBlocked
            }
            is DriveAuthorizationException -> {
                stateStore.update { it.copy(status = SyncStatus.AUTHORIZATION_REQUIRED, lastError = error.message) }
                SyncRunResult.AuthorizationRequired
            }
            is DriveApiException -> recordError(error.message.orEmpty(), error.retryable)
            is IOException -> recordError("Jaringan tidak tersedia", retryable = true)
            is TimeoutCancellationException -> recordError("Sinkronisasi terputus (terlalu lama)", retryable = true)
            else -> recordError(error.message ?: "Sinkronisasi gagal", retryable = false)
        }
    }

    private suspend fun recordError(message: String, retryable: Boolean): SyncRunResult {
        stateStore.update {
            it.copy(
                status = if (retryable) SyncStatus.WAITING_FOR_NETWORK else SyncStatus.ERROR,
                lastError = message,
            )
        }
        return SyncRunResult.Error(message, retryable)
    }

    private suspend fun passphraseRequired(message: String? = null): SyncRunResult {
        stateStore.update { it.copy(status = SyncStatus.PASSPHRASE_REQUIRED, lastError = message) }
        return SyncRunResult.PassphraseRequired
    }
}
