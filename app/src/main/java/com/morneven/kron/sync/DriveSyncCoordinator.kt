package com.morneven.kron.sync

import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal sealed interface SyncDecision {
    data class Upload(val parentSnapshotId: String?) : SyncDecision
    data class Download(val remote: RemoteDriveSnapshot) : SyncDecision
    data class Conflict(val reason: SyncConflictReason, val remote: RemoteDriveSnapshot?) : SyncDecision
    data object NoChanges : SyncDecision
    data object NoData : SyncDecision
}

internal object DriveSyncDecisionEngine {
    fun decide(
        state: SyncState,
        local: LocalDatasetSnapshot,
        account: GoogleAccountIdentity,
        remoteFiles: List<RemoteDriveSnapshot>,
    ): SyncDecision {
        val active = remoteFiles.filter { it.manifest.kind == SnapshotKind.ACTIVE }
        val latestForLocal = active
            .filter { it.manifest.datasetId == local.datasetId }
            .maxWithOrNull(snapshotComparator)
        val datasets = active.groupBy { it.manifest.datasetId }
        val latestAny = active.maxWithOrNull(snapshotComparator)

        if (state.accountSubject != null && state.accountSubject != account.subjectId) {
            return if (local.hasFinancialData) {
                SyncDecision.Conflict(SyncConflictReason.ACCOUNT_CHANGED, latestAny)
            } else if (datasets.size == 1) {
                SyncDecision.Download(requireNotNull(latestAny))
            } else if (datasets.size > 1) {
                SyncDecision.Conflict(SyncConflictReason.DATASET_MISMATCH, latestAny)
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
        val activeHead = datasetSnapshots
            .filter { it.manifest.kind == SnapshotKind.ACTIVE }
            .maxWithOrNull(
                compareBy<RemoteDriveSnapshot> { it.manifest.generation }
                    .thenBy { it.createdAt }
                    .thenBy { it.manifest.snapshotId },
            )
        val requiredSnapshotIds = buildSet {
            addAll(protectedSnapshotIds)
            activeHead?.let {
                add(it.manifest.snapshotId)
                it.manifest.parentSnapshotId?.let(::add)
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
        val tokenResult = authorization.accessToken(interactive = false)
        if (tokenResult !is DriveAccessTokenResult.Granted) return handleTokenFailure(tokenResult)

        return try {
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
                is SyncDecision.Upload -> uploadActive(
                    tokenResult,
                    descriptor,
                    decision.parentSnapshotId,
                )
                is SyncDecision.Download -> downloadAndApply(tokenResult, decision.remote)
                is SyncDecision.Conflict -> recordConflict(state, descriptor, decision)
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

    suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): SyncRunResult = syncMutex.withLock { resolveConflictLocked(conflict, resolution) }

    private suspend fun resolveConflictLocked(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): SyncRunResult {
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
                val currentLatest = remoteFiles
                    .filter {
                        it.manifest.kind == SnapshotKind.ACTIVE &&
                            it.manifest.datasetId == expectedRemote.manifest.datasetId
                    }
                    .maxWithOrNull(compareBy<RemoteDriveSnapshot>({ it.manifest.generation }, { it.createdAt }))
                if (currentLatest?.fileId != expectedRemote.fileId) return staleConflict(conflict)
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
                    val recovery = uploadRecovery(tokenResult, local.describe(), state.lastSnapshotId)
                    if (recovery !is SyncRunResult.Synchronized) return recovery
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
                    conflictRemoteFileId = null,
                    lastError = null,
                )
            }
        }
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
        runCatching {
            val state = stateStore.read()
            SnapshotRetention.filesToDelete(
                snapshots = drive.listSnapshots(accessToken),
                datasetId = datasetId,
                protectedFileIds = setOfNotNull(state.conflictRemoteFileId),
                protectedSnapshotIds = setOfNotNull(state.lastSnapshotId, state.parentSnapshotId),
            )
                .forEach { drive.deleteSnapshot(accessToken, it.fileId) }
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

    private suspend fun handleFailure(error: Throwable): SyncRunResult = when (error) {
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
        else -> recordError(error.message ?: "Sinkronisasi gagal", retryable = false)
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
