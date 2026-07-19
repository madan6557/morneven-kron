package com.morneven.kron.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveSyncPolicyTest {
    @Test
    fun billingRequirementPermanentlyBlocksAutomaticSync() = runBlocking {
        val stateStore = InMemoryStateStore()
        var listCalls = 0
        val drive = object : DriveAppDataClient {
            override suspend fun listSnapshots(accessToken: String): List<RemoteDriveSnapshot> {
                listCalls++
                throw DriveBillingRequiredException("billing", 403)
            }

            override suspend fun uploadSnapshot(
                accessToken: String,
                manifest: DriveSnapshotManifest,
                encryptedEnvelope: ByteArray,
            ) = error("Tidak boleh dipanggil")

            override suspend fun downloadSnapshot(accessToken: String, fileId: String) = error("Tidak boleh dipanggil")
            override suspend fun deleteSnapshot(accessToken: String, fileId: String) = error("Tidak boleh dipanggil")
        }
        val coordinator = coordinator(stateStore, drive)

        assertEquals(SyncRunResult.FreeOnlyBlocked, coordinator.syncNow())
        assertTrue(stateStore.read().disabledDueToBilling)
        assertEquals(SyncStatus.FREE_ONLY_BLOCKED, stateStore.read().status)
        assertEquals(SyncRunResult.FreeOnlyBlocked, coordinator.syncNow())
        assertEquals(1, listCalls)
    }

    @Test
    fun authorizationSessionRequestsOnlyAppDataScope() = runBlocking {
        val account = GoogleAccountIdentity("subject-a", "owner@example.com")
        val store = InMemoryAccountStore()
        var capturedScopes = emptySet<String>()
        val bridge = object : AuthorizationClientBridge {
            override suspend fun authorize(
                account: GoogleAccountIdentity,
                requestedScopes: Set<String>,
                interactive: Boolean,
            ): AuthorizationClientResult {
                capturedScopes = requestedScopes
                return AuthorizationClientResult.Granted("token", 10_000, requestedScopes)
            }

            override suspend fun revokeAccess(account: GoogleAccountIdentity) = Unit
            override suspend fun clearToken(accessToken: String) = Unit
        }
        val session = AuthorizationClientDriveSession(
            accountSelector = CredentialManagerAccountSelector { account },
            authorizationClient = bridge,
            accountStore = store,
            nowEpochMillis = { 0 },
        )

        assertEquals(DriveConnectResult.Connected(account), session.connect())
        assertEquals(setOf(DRIVE_APPDATA_SCOPE), capturedScopes)
        assertEquals(account, store.read())
    }

    @Test
    fun billingClassifierDoesNotConfuseRateLimitWithBilling() {
        assertTrue(DriveErrorClassifier.toException(403, "billingNotEnabled") is DriveBillingRequiredException)
        val rateLimit = DriveErrorClassifier.toException(429, "rateLimitExceeded")
        assertTrue(rateLimit.retryable)
        assertTrue(rateLimit !is DriveBillingRequiredException)
    }

    @Test
    fun downloadedSnapshotRequiresRestartAndIsNeverReportedAsApplied() = runBlocking {
        val passphrase = "passphrase-aman".toCharArray()
        val payload = "portable-backup".toByteArray()
        val manifest = DriveSnapshotManifest(
            datasetId = "dataset-a",
            snapshotId = "snapshot-2",
            parentSnapshotId = "snapshot-1",
            generation = 2,
            sourceDeviceId = "device-b",
            schemaVersion = 6,
            minimumAppVersionCode = 22,
            createdAtEpochMillis = 2,
            payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
        )
        val cryptor = AesGcmDriveSnapshotCryptor()
        val envelope = cryptor.encrypt(manifest, payload, passphrase)
        val remote = RemoteDriveSnapshot(
            fileId = "file-2",
            name = "snapshot.bin",
            manifest = manifest,
            createdAt = java.time.Instant.ofEpochMilli(2),
            sizeBytes = envelope.size.toLong(),
        )
        val stateStore = InMemoryStateStore(
            SyncState(
                datasetId = "dataset-a",
                deviceId = "device-a",
                localGeneration = 1,
                lastSyncedGeneration = 1,
                lastSnapshotId = "snapshot-1",
                accountSubject = "subject-a",
            ),
        )
        var applyCalls = 0
        val coordinator = DriveSyncCoordinator(
            authorization = grantedAuthorization(),
            drive = object : DriveAppDataClient {
                override suspend fun listSnapshots(accessToken: String) = listOf(remote)
                override suspend fun uploadSnapshot(
                    accessToken: String,
                    manifest: DriveSnapshotManifest,
                    encryptedEnvelope: ByteArray,
                ) = error("Tidak boleh upload")
                override suspend fun downloadSnapshot(accessToken: String, fileId: String) = envelope.copyOf()
                override suspend fun deleteSnapshot(accessToken: String, fileId: String) = Unit
            },
            local = object : LocalSnapshotSource {
                override suspend fun describe() = LocalDatasetSnapshot("dataset-a", 1, 6, true)
                override suspend fun exportSnapshotPayload() = payload.copyOf()
                override suspend fun applyRemoteAtomically(
                payload: ByteArray,
                manifest: DriveSnapshotManifest,
                account: GoogleAccountIdentity,
            ): LocalApplyOutcome {
                    applyCalls++
                    return LocalApplyOutcome.RESTART_REQUIRED
                }
            },
            stateStore = stateStore,
            secretProvider = SyncSecretProvider { passphrase.copyOf() },
            cryptor = cryptor,
            currentAppVersionCode = 22,
        )

        assertEquals(SyncRunResult.RestartRequired("snapshot-2"), coordinator.syncNow())
        assertEquals(1, applyCalls)
        assertEquals(SyncStatus.RESTART_REQUIRED, stateStore.read().status)
        assertEquals("snapshot-2", stateStore.read().lastSnapshotId)
    }

    @Test
    fun wrongPassphraseIsRejectedBeforeRemoteSnapshotIsApplied() = runBlocking {
        val correctPassphrase = "passphrase-benar".toCharArray()
        val payload = "portable-backup".toByteArray()
        val manifest = DriveSnapshotManifest(
            datasetId = "dataset-a",
            snapshotId = "snapshot-1",
            parentSnapshotId = null,
            generation = 1,
            sourceDeviceId = "device-b",
            schemaVersion = 6,
            minimumAppVersionCode = 22,
            createdAtEpochMillis = 1,
            payloadSha256 = AesGcmDriveSnapshotCryptor.sha256(payload),
        )
        val cryptor = AesGcmDriveSnapshotCryptor()
        val envelope = cryptor.encrypt(manifest, payload, correctPassphrase)
        val remote = RemoteDriveSnapshot(
            fileId = "file-1",
            name = "snapshot.bin",
            manifest = manifest,
            createdAt = java.time.Instant.ofEpochMilli(1),
            sizeBytes = envelope.size.toLong(),
        )
        val stateStore = InMemoryStateStore()
        var applyCalls = 0
        val coordinator = DriveSyncCoordinator(
            authorization = grantedAuthorization(),
            drive = object : DriveAppDataClient {
                override suspend fun listSnapshots(accessToken: String) = listOf(remote)
                override suspend fun uploadSnapshot(
                    accessToken: String,
                    manifest: DriveSnapshotManifest,
                    encryptedEnvelope: ByteArray,
                ) = error("Tidak boleh upload")
                override suspend fun downloadSnapshot(accessToken: String, fileId: String) = envelope.copyOf()
                override suspend fun deleteSnapshot(accessToken: String, fileId: String) = Unit
            },
            local = object : LocalSnapshotSource {
                override suspend fun describe() = LocalDatasetSnapshot("dataset-a", 0, 6, false)
                override suspend fun exportSnapshotPayload() = error("Tidak boleh ekspor")
                override suspend fun applyRemoteAtomically(
                    payload: ByteArray,
                    manifest: DriveSnapshotManifest,
                    account: GoogleAccountIdentity,
                ): LocalApplyOutcome {
                    applyCalls++
                    return LocalApplyOutcome.APPLIED
                }
            },
            stateStore = stateStore,
            secretProvider = SyncSecretProvider { "passphrase-salah".toCharArray() },
            cryptor = cryptor,
            currentAppVersionCode = 22,
        )

        assertEquals(SyncRunResult.PassphraseRequired, coordinator.syncNow())
        assertEquals(0, applyCalls)
        assertEquals(SyncStatus.PASSPHRASE_REQUIRED, stateStore.read().status)
        assertTrue(stateStore.read().lastError?.contains("Passphrase Drive salah") == true)
    }

    private fun coordinator(stateStore: SyncStateStore, drive: DriveAppDataClient) = DriveSyncCoordinator(
        authorization = grantedAuthorization(),
        drive = drive,
        local = object : LocalSnapshotSource {
            override suspend fun describe() = LocalDatasetSnapshot("dataset-a", 1, 6, true)
            override suspend fun exportSnapshotPayload() = "payload".toByteArray()
            override suspend fun applyRemoteAtomically(
                payload: ByteArray,
                manifest: DriveSnapshotManifest,
                account: GoogleAccountIdentity,
            ) = LocalApplyOutcome.APPLIED
        },
        stateStore = stateStore,
        secretProvider = SyncSecretProvider { "passphrase-aman".toCharArray() },
        currentAppVersionCode = 22,
    )

    private fun grantedAuthorization() = object : DriveAuthorizationSession {
        private val account = GoogleAccountIdentity("subject-a", "owner@example.com")
        override suspend fun currentAccount() = account
        override suspend fun connect() = DriveConnectResult.Connected(account)
        override suspend fun accessToken(interactive: Boolean) = DriveAccessTokenResult.Granted(account, "token")
        override suspend fun disconnect() = Unit
    }

    private class InMemoryStateStore(
        private var value: SyncState = SyncState("dataset-a", "device-a"),
    ) : SyncStateStore {
        override suspend fun read() = value
        override suspend fun update(transform: (SyncState) -> SyncState): SyncState {
            value = transform(value)
            return value
        }
    }

    private class InMemoryAccountStore : SelectedGoogleAccountStore {
        private var value: GoogleAccountIdentity? = null
        override suspend fun read() = value
        override suspend fun write(account: GoogleAccountIdentity?) {
            value = account
        }
    }
}
