package com.morneven.kron.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.activity.result.IntentSenderRequest
import com.morneven.kron.BuildConfig
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Activity-facing Drive backend. The Activity owns interactive account and
 * authorization prompts, while background workers receive an Activity-free coordinator.
 */
class DriveSyncRuntime internal constructor(
    private val authorization: AuthorizationClientDriveSession,
    private val authorizationBridge: PlayServicesAuthorizationClientBridge,
    private val coordinator: DriveSyncCoordinator,
    private val secretStore: EncryptedSyncSecretStore,
    private val factory: DriveSyncRuntimeFactory,
) {
    private val passphraseOperationMutex = Mutex()

    suspend fun currentAccount(): GoogleAccountIdentity? = authorization.currentAccount()

    suspend fun connect(passphrase: CharArray): DriveConnectResult {
        if (!BuildConfig.DRIVE_SYNC_CONFIGURED) {
            passphrase.fill('\u0000')
            return DriveConnectResult.Failed("Konfigurasi OAuth Drive belum tersedia", retryable = false)
        }
        factory.networkBlockMessage()?.let { message ->
            passphrase.fill('\u0000')
            return DriveConnectResult.Failed(message, retryable = true)
        }
        return try {
            passphraseOperationMutex.withLock { secretStore.stage(passphrase) }
            val result = try {
                authorization.connect()
            } catch (cancelled: CancellationException) {
                discardUncommittedPassphrase()
                throw cancelled
            } catch (_: Exception) {
                discardUncommittedPassphrase()
                return DriveConnectResult.Failed(
                    "Akun Google tidak dapat dihubungkan",
                    retryable = true,
                )
            }
            when (result) {
                is DriveConnectResult.Connected -> factory.activateAfterConnection()
                is DriveConnectResult.Failed -> discardUncommittedPassphrase()
                is DriveConnectResult.UserActionRequired -> Unit
            }
            result
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(NonCancellable) { discardUncommittedPassphrase() }
            throw cancelled
        } catch (error: IllegalStateException) {
            discardUncommittedPassphrase()
            DriveConnectResult.Failed(
                error.message ?: "Passphrase Drive tidak dapat disiapkan",
                retryable = false,
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun reauthorizeCurrent(): DriveConnectResult {
        factory.networkBlockMessage()?.let { return DriveConnectResult.Failed(it, retryable = true) }
        return authorization.reauthorizeCurrent().also { result ->
            when (result) {
                is DriveConnectResult.Connected -> factory.activateAfterConnection()
                is DriveConnectResult.Failed -> Unit
                is DriveConnectResult.UserActionRequired -> Unit
            }
        }
    }

    fun authorizationRequest(resolutionId: String): IntentSenderRequest =
        authorizationBridge.intentSenderRequest(resolutionId)

    suspend fun completeAuthorization(
        account: GoogleAccountIdentity,
        resolutionId: String,
        resultCode: Int,
        data: Intent?,
    ): DriveConnectResult {
        val bridgeResult = authorizationBridge.completeResolution(resolutionId, resultCode, data)
        val result = authorization.acceptConnectionResult(account, bridgeResult)
        when (result) {
            is DriveConnectResult.Connected -> factory.activateAfterConnection()
            is DriveConnectResult.Failed -> {
                discardUncommittedPassphrase()
                factory.deactivate()
            }
            is DriveConnectResult.UserActionRequired -> Unit
        }
        return result
    }

    suspend fun cancelAuthorization(resolutionId: String) {
        authorizationBridge.discardResolution(resolutionId)
        discardUncommittedPassphrase()
        factory.deactivate()
    }

    suspend fun supplyPassphrase(passphrase: CharArray): SyncRunResult = try {
        passphraseOperationMutex.withLock {
            if (authorization.currentAccount() == null) {
                return@withLock SyncRunResult.Error("Hubungkan akun Google terlebih dahulu", retryable = false)
            }
            if (secretStore.isStored()) {
                return@withLock SyncRunResult.Error(
                    "Passphrase yang tersimpan tidak dapat dirotasi. Putuskan akun Drive terlebih dahulu.",
                    retryable = false,
                )
            }
            secretStore.stage(passphrase)
            syncNowLocked()
        }
    } catch (cancelled: CancellationException) {
        kotlinx.coroutines.withContext(NonCancellable) { discardUncommittedPassphrase() }
        throw cancelled
    } catch (error: Exception) {
        secretStore.discardStaged()
        SyncRunResult.Error(error.message ?: "Passphrase Drive tidak dapat digunakan", retryable = false)
    } finally {
        passphrase.fill('\u0000')
    }

    suspend fun syncNow(): SyncRunResult = passphraseOperationMutex.withLock { syncNowLocked() }

    private suspend fun syncNowLocked(): SyncRunResult {
        factory.restartResult()?.let { return it }
        factory.networkBlockedResult()?.let { return it }
        if (!secretStore.isStored() && !secretStore.hasStaged()) return SyncRunResult.PassphraseRequired
        return finalizeStagedPassphrase(coordinator.syncNow())
    }

    fun isWifiOnly(): Boolean = factory.isWifiOnly()

    suspend fun setWifiOnly(value: Boolean) {
        factory.setWifiOnly(value)
    }

    suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): SyncRunResult = passphraseOperationMutex.withLock {
        factory.restartResult()?.let { return it }
        factory.networkBlockedResult()?.let { return it }
        if (!secretStore.isStored() && !secretStore.hasStaged()) return SyncRunResult.PassphraseRequired
        if (
            secretStore.hasStaged() &&
            resolution != ConflictResolution.USE_THIS_DEVICE &&
            conflict.remote != null
        ) {
            val validation = finalizeStagedPassphrase(
                result = coordinator.validatePassphrase(conflict.remote),
                noChangesValidated = true,
            )
            if (validation != SyncRunResult.NoChanges) return validation
        }
        finalizeStagedPassphrase(coordinator.resolveConflict(conflict, resolution))
    }

    suspend fun suspendForRestart() {
        factory.deactivate()
    }

    suspend fun disconnect() = passphraseOperationMutex.withLock {
        try {
            coordinator.disconnect()
        } finally {
            secretStore.clear()
            factory.deactivate()
        }
    }

    private suspend fun finalizeStagedPassphrase(
        result: SyncRunResult,
        noChangesValidated: Boolean = false,
    ): SyncRunResult {
        if (!secretStore.hasStaged()) return result
        val verifiedResult = if (result == SyncRunResult.NoChanges && !noChangesValidated) {
            coordinator.validatePassphrase()
        } else {
            result
        }
        return when (verifiedResult) {
            is SyncRunResult.Synchronized,
            SyncRunResult.NoChanges,
            SyncRunResult.NoData,
            -> {
                if (!secretStore.commitStaged()) {
                    return SyncRunResult.Error("Passphrase Drive tidak dapat disimpan", retryable = false)
                }
                factory.activateAfterConnection()
                result
            }
            SyncRunResult.PassphraseRequired -> {
                secretStore.discardStaged()
                verifiedResult
            }
            is SyncRunResult.Error -> {
                if (!verifiedResult.retryable) secretStore.discardStaged()
                verifiedResult
            }
            SyncRunResult.Disabled,
            SyncRunResult.FreeOnlyBlocked,
            is SyncRunResult.RestartRequired,
            -> {
                secretStore.discardStaged()
                verifiedResult
            }
            SyncRunResult.AuthorizationRequired,
            is SyncRunResult.Conflict,
            -> verifiedResult
        }
    }

    private suspend fun discardUncommittedPassphrase() = passphraseOperationMutex.withLock {
        secretStore.discardStaged()
    }
}

@Singleton
class DriveSyncRuntimeFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: KronDatabase,
    private val backupManager: BackupManager,
    private val secretStore: EncryptedSyncSecretStore,
) {
    private val syncPreferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
    private val accountStore = PreferencesSelectedGoogleAccountStore(context)
    private val stateStore = RoomSyncStateStore(database)
    private val localSnapshotSource = BackupManagerLocalSnapshotSource(backupManager, database)
    private val authorizationBridge = PlayServicesAuthorizationClientBridge(context)
    private val driveClient = DriveRestV3AppDataClient()
    private val lifecycleMutex = Mutex()
    private val processSyncMutex = Mutex()
    private val started = AtomicBoolean(false)

    fun create(activity: Activity): DriveSyncRuntime {
        check(BuildConfig.DRIVE_SYNC_CONFIGURED) {
            "Google Drive belum dikonfigurasi pada build KRON ini"
        }
        val authorization = AuthorizationClientDriveSession(
            accountSelector = AndroidCredentialManagerAccountSelector(
                activity = activity,
                webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID,
            ),
            authorizationClient = authorizationBridge,
            accountStore = accountStore,
        )
        return DriveSyncRuntime(
            authorization = authorization,
            authorizationBridge = authorizationBridge,
            coordinator = coordinator(authorization),
            secretStore = secretStore,
            factory = this,
        )
    }

    /** Starts optional sync without making local-only KRON depend on Google services. */
    fun start(applicationScope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        if (!BuildConfig.DRIVE_SYNC_CONFIGURED) {
            DriveSyncScheduler.cancel(context)
            return
        }
        applicationScope.launch {
            installBackgroundIfReady(syncImmediately = true)
        }
        applicationScope.launch {
            var previousGeneration: Long? = null
            database.kronDao().observeSyncState()
                .filterNotNull()
                .map { SyncWatch(it.localGeneration, it.lastSyncedGeneration, it.disabledDueToBilling, it.status) }
                .distinctUntilChanged()
                .collect { watch ->
                    val localGeneration = watch.localGeneration
                    val lastSyncedGeneration = watch.lastSyncedGeneration
                    val changed = previousGeneration?.let { it != localGeneration } ?: false
                    previousGeneration = localGeneration
                    if (watch.billingBlocked || watch.status == SyncStatus.RESTART_REQUIRED.name) {
                        deactivate()
                    } else if (changed && localGeneration > lastSyncedGeneration && isReady()) {
                        DriveSyncScheduler.scheduleAfterChange(context, isWifiOnly())
                    }
                }
        }
    }

    internal suspend fun activateAfterConnection(): Boolean {
        stateStore.update { state ->
            if (state.disabledDueToBilling) state else state.copy(status = SyncStatus.IDLE, lastError = null)
        }
        return installBackgroundIfReady(syncImmediately = false)
    }

    suspend fun installBackgroundIfReady(syncImmediately: Boolean = false): Boolean = lifecycleMutex.withLock {
        if (!isReady()) {
            DriveSyncScheduler.cancel(context)
            return@withLock false
        }
        val coordinator = backgroundCoordinator()
        DriveSyncServiceLocator.install { coordinator }
        DriveSyncScheduler.schedulePeriodic(context, isWifiOnly())
        if (syncImmediately) DriveSyncScheduler.syncNow(context, isWifiOnly())
        true
    }

    fun isWifiOnly(): Boolean = syncPreferences.getBoolean(KEY_WIFI_ONLY, true)

    suspend fun setWifiOnly(value: Boolean) = lifecycleMutex.withLock {
        check(syncPreferences.edit().putBoolean(KEY_WIFI_ONLY, value).commit()) {
            "Preferensi jaringan sinkronisasi tidak dapat disimpan"
        }
        if (isReady()) {
            DriveSyncScheduler.schedulePeriodic(context, value)
            DriveSyncScheduler.scheduleAfterChange(context, value)
        }
    }

    internal suspend fun deactivate() = lifecycleMutex.withLock {
        DriveSyncScheduler.cancel(context)
    }

    internal suspend fun restartResult(): SyncRunResult.RestartRequired? {
        val state = stateStore.read()
        if (state.status != SyncStatus.RESTART_REQUIRED) return null
        return SyncRunResult.RestartRequired(state.lastSnapshotId ?: "pending-restore")
    }

    internal fun networkBlockMessage(): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return "Tidak ada koneksi internet"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "Tidak ada koneksi internet"
        }
        if (isWifiOnly() && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            return "Menunggu jaringan tanpa meter"
        }
        return null
    }

    internal suspend fun networkBlockedResult(): SyncRunResult.Error? {
        val message = networkBlockMessage() ?: return null
        stateStore.update { it.copy(status = SyncStatus.WAITING_FOR_NETWORK, lastError = message) }
        return SyncRunResult.Error(message, retryable = true)
    }

    private suspend fun isReady(): Boolean {
        if (!BuildConfig.DRIVE_SYNC_CONFIGURED) return false
        if (!secretStore.isStored() || accountStore.read() == null) return false
        val state = stateStore.read()
        return !state.disabledDueToBilling && state.status !in setOf(SyncStatus.DISABLED, SyncStatus.RESTART_REQUIRED)
    }

    private fun backgroundCoordinator(): DriveSyncCoordinator {
        val authorization = AuthorizationClientDriveSession(
            accountSelector = CredentialManagerAccountSelector {
                error("Pemilihan akun memerlukan Activity")
            },
            authorizationClient = authorizationBridge,
            accountStore = accountStore,
        )
        return coordinator(authorization)
    }

    private fun coordinator(authorization: DriveAuthorizationSession) = DriveSyncCoordinator(
        authorization = authorization,
        drive = driveClient,
        local = localSnapshotSource,
        stateStore = stateStore,
        secretProvider = secretStore,
        currentAppVersionCode = BuildConfig.VERSION_CODE,
        syncMutex = processSyncMutex,
    )

    private data class SyncWatch(
        val localGeneration: Long,
        val lastSyncedGeneration: Long,
        val billingBlocked: Boolean,
        val status: String,
    )

    private companion object {
        const val SYNC_PREFERENCES = "kron_drive_sync_settings"
        const val KEY_WIFI_ONLY = "wifi_only"
    }
}
