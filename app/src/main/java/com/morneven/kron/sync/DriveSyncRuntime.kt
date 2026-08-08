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
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.security.SnapshotOperationLock
import com.morneven.kron.team.TeamSyncScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Activity-facing Drive backend. The Activity owns interactive account and
 * authorization prompts, while background workers receive an Activity-free coordinator.
 */
private const val SYNC_LOCK_ACQUISITION_TIMEOUT_MILLIS = 45_000L

class DriveSyncRuntime internal constructor(
    private val authorization: AuthorizationClientDriveSession,
    private val authorizationBridge: PlayServicesAuthorizationClientBridge,
    private val coordinator: DriveSyncCoordinator,
    private val secretStore: EncryptedSyncSecretStore,
    private val factory: DriveSyncRuntimeFactory,
) {
    private val passphraseOperationMutex = Mutex()
    private var pendingAccountSwitch: PendingAccountSwitch? = null

    private data class PendingAccountSwitch(
        var account: GoogleAccountIdentity?,
        val previousAccount: GoogleAccountIdentity?,
        val previousState: SyncState,
        val previousPassphrase: CharArray?,
        val previousPrivateSyncReady: Boolean,
        val previousPrivateTeamInfo: Boolean,
        var resetDataset: Boolean,
    )

    suspend fun currentAccount(): GoogleAccountIdentity? = authorization.currentAccount()

    fun networkBlockMessage(): String? = factory.networkBlockMessage()

    /** Team sync is user-initiated and may use cellular data regardless of private-sync preference. */
    fun teamNetworkBlockMessage(): String? = factory.networkBlockMessage(allowMetered = true)

    suspend fun connect(passphrase: CharArray): DriveConnectResult {
        if (!BuildConfig.DRIVE_SYNC_CONFIGURED) {
            passphrase.fill('\u0000')
            return DriveConnectResult.Failed("Konfigurasi OAuth Drive belum tersedia", retryable = false)
        }
        return try {
            // Credential Manager selection is another account-switch entry
            // point. Prepare the same pending/reset state as the manual
            // picker before authorization can replace the selected account.
            val previousAccount = authorization.currentAccount()
            if (previousAccount == null) {
                passphraseOperationMutex.withLock { secretStore.stage(passphrase) }
            } else {
                prepareAccountSwitch(previousAccount, passphrase)
            }
            val result = try {
                authorization.connect()
            } catch (cancelled: CancellationException) {
                if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
                throw cancelled
            } catch (error: IllegalStateException) {
                if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
                return DriveConnectResult.Failed(
                    error.message ?: "Akun Google tidak dapat dihubungkan",
                    retryable = false,
                )
            } catch (_: Exception) {
                if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
                return DriveConnectResult.Failed(
                    "Akun Google tidak dapat dihubungkan",
                    retryable = true,
                )
            }
            when (result) {
                is DriveConnectResult.Connected -> {
                    pendingAccountSwitch?.let {
                        it.account = result.account
                        it.resetDataset = accountChanged(previousAccount, result.account)
                        completeAccountSwitch(result.account)
                    } ?: factory.activateAfterConnection()
                }
                is DriveConnectResult.Failed -> {
                    if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
                }
                is DriveConnectResult.UserActionRequired -> {
                    pendingAccountSwitch?.account = result.account
                    pendingAccountSwitch?.resetDataset = result.account?.let {
                        accountChanged(previousAccount, it)
                    } ?: false
                }
            }
            result
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(NonCancellable) {
                if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
            }
            throw cancelled
        } catch (error: IllegalStateException) {
            if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
            DriveConnectResult.Failed(
                error.message ?: "Passphrase Drive tidak dapat disiapkan",
                retryable = false,
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }

    /**
     * Connect using an account email obtained from a manual account picker. This
     * bypasses the Credential Manager selection and proceeds to request the
     * Drive authorization for the chosen account.
     */
    suspend fun connectWithAccountEmail(email: String, passphrase: CharArray): DriveConnectResult {
        if (!BuildConfig.DRIVE_SYNC_CONFIGURED) {
            passphrase.fill('\u0000')
            return DriveConnectResult.Failed("Konfigurasi OAuth Drive belum tersedia", retryable = false)
        }
        val account = GoogleAccountIdentity(email, email, null)
        return try {
            val previousAccount = authorization.currentAccount()
            if (previousAccount != null) {
                val resetDataset = !previousAccount.email.equals(account.email, ignoreCase = true) &&
                    previousAccount.subjectId != account.subjectId
                passphraseOperationMutex.withLock {
                    check(pendingAccountSwitch == null) { "Pergantian akun Google masih berlangsung" }
                    val previousState = factory.readSyncState()
                    val previousPrivateSyncReady = DriveSyncSwitchGate.isPrivateReady(factory.contextForScheduling())
                    val previousPrivateTeamInfo = DriveSyncSwitchGate.hasPrivateTeamInfo(factory.contextForScheduling())
                    val previousPassphrase = secretStore.acquirePassphrase()
                    try {
                        factory.beginAccountSwitch()
                        secretStore.stageReplacement(passphrase)
                        pendingAccountSwitch = PendingAccountSwitch(
                            account = account,
                            previousAccount = previousAccount,
                            previousState = previousState,
                            previousPassphrase = previousPassphrase,
                            previousPrivateSyncReady = previousPrivateSyncReady,
                            previousPrivateTeamInfo = previousPrivateTeamInfo,
                            resetDataset = resetDataset,
                        )
                    } catch (error: Throwable) {
                        previousPassphrase?.fill('\u0000')
                        factory.restoreSyncState(
                            previousState,
                            previousPrivateSyncReady,
                            previousPrivateTeamInfo,
                        )
                        factory.endAccountSwitch()
                        throw error
                    }
                }
            } else {
                passphraseOperationMutex.withLock { secretStore.stage(passphrase) }
            }
            val authResult = try {
                authorization.authorizeAccount(account, interactive = true)
            } catch (cancelled: CancellationException) {
                if (previousAccount != null) abortAccountSwitch()
                else discardUncommittedPassphrase()
                throw cancelled
            } catch (error: IllegalStateException) {
                if (previousAccount != null) abortAccountSwitch()
                else discardUncommittedPassphrase()
                return DriveConnectResult.Failed(
                    error.message ?: "Akun Google tidak dapat dihubungkan",
                    retryable = false,
                )
            } catch (_: Exception) {
                if (previousAccount != null) abortAccountSwitch()
                else discardUncommittedPassphrase()
                return DriveConnectResult.Failed(
                    "Akun Google tidak dapat dihubungkan",
                    retryable = true,
                )
            }
            val result = authorization.acceptConnectionResult(
                account,
                authResult,
                persistAccount = previousAccount == null,
            )
            when (result) {
                is DriveConnectResult.Connected -> {
                    if (previousAccount == null) {
                        factory.updateSyncAccount(account)
                        factory.activateAfterConnection()
                    } else {
                        completeAccountSwitch(result.account)
                    }
                }
                is DriveConnectResult.Failed -> if (previousAccount != null) abortAccountSwitch() else discardUncommittedPassphrase()
                is DriveConnectResult.UserActionRequired -> Unit
            }
            result
        } catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(NonCancellable) {
                if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
            }
            throw cancelled
        } catch (error: IllegalStateException) {
            if (pendingAccountSwitch != null) abortAccountSwitch() else discardUncommittedPassphrase()
            DriveConnectResult.Failed(
                error.message ?: "Passphrase Drive tidak dapat disiapkan",
                retryable = false,
            )
        } finally {
            passphrase.fill('\u0000')
        }
    }

    suspend fun reauthorizeCurrent(): DriveConnectResult {
        return authorization.reauthorizeCurrent().also { result ->
            when (result) {
                is DriveConnectResult.Connected -> factory.activateAfterConnection()
                is DriveConnectResult.Failed -> Unit
                is DriveConnectResult.UserActionRequired -> Unit
            }
        }
    }

    /** Switches the selected Google Drive account using the supplied passphrase. */
    suspend fun switchAccount(newEmail: String): DriveConnectResult {
        val passphrase = secretStore.acquirePassphrase()
            ?: return DriveConnectResult.Failed("Masukkan passphrase Drive untuk akun tujuan", retryable = false)
        return try {
            connectWithAccountEmail(newEmail, passphrase)
        } finally {
            passphrase.fill('\u0000')
        }
    }

    fun authorizationRequest(resolutionId: String): IntentSenderRequest =
        authorizationBridge.intentSenderRequest(resolutionId)

    suspend fun completeAuthorization(
        account: GoogleAccountIdentity?,
        resolutionId: String,
        resultCode: Int,
        data: Intent?,
    ): DriveConnectResult {
        val bridgeResult = authorizationBridge.completeResolution(resolutionId, resultCode, data)
        val switching = pendingAccountSwitch
        val result = authorization.acceptConnectionResult(
            account ?: switching?.account,
            bridgeResult,
            persistAccount = switching == null,
        )
        when (result) {
            is DriveConnectResult.Connected -> {
                val connectedAccount = result.account
                if (switching == null) {
                    factory.updateSyncAccount(connectedAccount)
                    factory.activateAfterConnection()
                } else {
                    completeAccountSwitch(connectedAccount)
                }
            }
            is DriveConnectResult.Failed -> {
                if (switching == null) {
                    discardUncommittedPassphrase()
                    factory.deactivate()
                } else {
                    abortAccountSwitch()
                }
            }
            is DriveConnectResult.UserActionRequired -> Unit
        }
        return result
    }

    suspend fun cancelAuthorization(resolutionId: String) {
        authorizationBridge.discardResolution(resolutionId)
        if (pendingAccountSwitch != null) {
            abortAccountSwitch()
        } else {
            discardUncommittedPassphrase()
            factory.deactivate()
        }
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

    suspend fun syncNow(): SyncRunResult = try {
        // The mutex can be held by a background worker that MIUI froze while
        // the app was in the background. Bound the wait so the UI can never
        // hang on "sedang menyinkronkan" forever.
        withTimeout(SYNC_LOCK_ACQUISITION_TIMEOUT_MILLIS) { passphraseOperationMutex.withLock { syncNowLocked() } }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (timeout: TimeoutCancellationException) {
        SyncRunResult.Error(
            "Sinkronisasi lain masih berjalan di latar belakang. Coba lagi dalam beberapa saat.",
            retryable = true,
        )
    }

    private suspend fun syncNowLocked(): SyncRunResult {
        if (factory.isAccountSwitching()) {
            return SyncRunResult.Error("Pergantian akun Google sedang berlangsung", retryable = true)
        }
        factory.networkBlockedResult()?.let { return it }
        if (!secretStore.isStored() && !secretStore.hasStaged()) return SyncRunResult.PassphraseRequired
        // Surface the sync phase immediately: authorization and snapshot
        // listing happen before the coordinator can set SYNCING itself.
        factory.markSyncInProgress()
        ensureSelectedAccountDataset()
        val pending = DriveSyncSwitchGate.isPending(factory.contextForScheduling())
        val accountSwitch = DriveSyncSwitchGate.isAccountSwitchPending(factory.contextForScheduling())
        // A first connection with no account switch still needs an explicit
        // empty-remote choice. A real account switch is handled separately:
        // an existing remote dataset is selected as the source of truth.
        val uninitialized = factory.readSyncState().lastSyncedAtEpochMillis == null
        val rawResult = if (pending || uninitialized) {
            coordinator.syncPendingAccount(accountSwitch = accountSwitch)
        } else {
            coordinator.syncNow()
        }
        if (rawResult == SyncRunResult.InitialSyncChoiceRequired) return rawResult
        if (pending) {
            when (rawResult) {
                is SyncRunResult.Synchronized,
                is SyncRunResult.Applied,
                SyncRunResult.NoChanges,
                SyncRunResult.NoData,
                -> {
                    factory.confirmPendingInitialUpload()
                    // Re-arm background workers now that the switch gate is open;
                    // endAccountSwitch could not install them while it was closed.
                    factory.installBackgroundIfReady(syncImmediately = false)
                }
                else -> Unit
            }
        }
        val result = finalizeStagedPassphrase(rawResult)
        factory.recordPrivateSyncResult(result)
        return result
    }

    /**
     * A previous APK could persist the old Drive identity before resetting its
     * dataset metadata. Repair that state at the single sync boundary instead
     * of letting the decision engine classify the first sync as a conflict.
     */
    private suspend fun ensureSelectedAccountDataset() {
        val selected = authorization.currentAccount() ?: return
        val state = factory.readSyncState()
        val changed = state.accountSubject != null &&
            (state.accountSubject != selected.subjectId ||
                !state.accountEmail.equals(selected.email, ignoreCase = true))
        if (changed && !factory.isAccountSwitching()) {
            factory.switchSyncAccount(selected)
        }
    }

    fun isWifiOnly(): Boolean = factory.isWifiOnly()

    suspend fun setWifiOnly(value: Boolean) {
        factory.setWifiOnly(value)
    }

    suspend fun resolveConflict(
        conflict: SyncConflict,
        resolution: ConflictResolution,
    ): SyncRunResult = passphraseOperationMutex.withLock {
        factory.networkBlockedResult()?.let { return it }
        if (!secretStore.isStored() && !secretStore.hasStaged()) return SyncRunResult.PassphraseRequired
        if (
            secretStore.hasStaged() &&
            resolution != ConflictResolution.USE_THIS_DEVICE &&
            conflict.remote != null &&
            conflict.reason != SyncConflictReason.ACCOUNT_CHANGED
        ) {
            val validation = finalizeStagedPassphrase(
                result = coordinator.validatePassphrase(conflict.remote),
                noChangesValidated = true,
            )
            if (validation != SyncRunResult.NoChanges) return validation
        }
        val result = finalizeStagedPassphrase(coordinator.resolveConflict(conflict, resolution))
        factory.recordPrivateSyncResult(result)
        result
    }

    suspend fun previewConflict(conflict: SyncConflict): ConflictPreviewResult = passphraseOperationMutex.withLock {
        factory.networkBlockedResult()?.let { return@withLock ConflictPreviewResult.Error(it.message) }
        if (!secretStore.isStored() && !secretStore.hasStaged()) return@withLock ConflictPreviewResult.PassphraseRequired
        coordinator.previewConflict(conflict).also { result ->
            if (result is ConflictPreviewResult.Ready && secretStore.hasStaged()) {
                if (secretStore.commitStaged()) factory.activateAfterConnection()
            }
        }
    }

    suspend fun downloadAndApplyLatest(): SyncRunResult = passphraseOperationMutex.withLock {
        factory.networkBlockedResult()?.let { return it }
        if (!secretStore.isStored() && !secretStore.hasStaged()) {
            return SyncRunResult.PassphraseRequired
        }
        val result = coordinator.downloadLatestSnapshot()
        val applied = finalizeStagedPassphrase(result)
        factory.recordPrivateSyncResult(applied)
        applied
    }

    suspend fun disconnect() = passphraseOperationMutex.withLock {
        try {
            coordinator.disconnect()
        } finally {
            secretStore.clear()
            factory.deactivate()
        }
    }

    suspend fun isPassphraseStored(): Boolean = secretStore.isStored()

    suspend fun updateSyncAccount(account: GoogleAccountIdentity) {
        factory.updateSyncAccount(account)
    }

    suspend fun startAccountMigration(account: GoogleAccountIdentity) {
        passphraseOperationMutex.withLock {
            try {
                secretStore.commitStaged()
                factory.installAccountMigration(account)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Account migration remains recoverable from the settings flow.
            }
        }
    }

    suspend fun clearDriveData(): SyncRunResult = passphraseOperationMutex.withLock {
        val result = coordinator.clearDriveData()
        if (result is SyncRunResult.NoChanges || result is SyncRunResult.Synchronized) {
            secretStore.clear()
            factory.deactivate()
        }
        result
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
            is SyncRunResult.Applied,
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
            -> {
                secretStore.discardStaged()
                verifiedResult
            }
            SyncRunResult.AuthorizationRequired,
            SyncRunResult.InitialSyncChoiceRequired,
            is SyncRunResult.Conflict,
            -> verifiedResult
        }
    }

    private suspend fun discardUncommittedPassphrase() = passphraseOperationMutex.withLock {
        secretStore.discardStaged()
    }

    /** Uses the local graph as the first dataset for a newly selected account. */
    suspend fun confirmPendingInitialUpload(): SyncRunResult {
        return try {
            val result = passphraseOperationMutex.withLock {
                factory.networkBlockedResult()?.let { return@withLock it }
                if (!secretStore.isStored() && !secretStore.hasStaged()) {
                    return@withLock SyncRunResult.PassphraseRequired
                }
                val applied = finalizeStagedPassphrase(coordinator.replaceRemoteWithLocal())
                factory.recordPrivateSyncResult(applied)
                applied
            }
            if (result is SyncRunResult.Synchronized) factory.confirmPendingInitialUpload()
            result
        } finally {
            factory.installBackgroundIfReady(syncImmediately = false)
        }
    }

    /** Keeps the local graph but intentionally starts the remote dataset empty. */
    suspend fun startFreshPendingAccount(): SyncRunResult {
        return try {
            val result = passphraseOperationMutex.withLock {
                factory.networkBlockedResult()?.let { return@withLock it }
                // The first connection can still hold a staged passphrase.
                // Commit it after the explicit empty-remote choice; otherwise
                // the account appears connected but every later sync asks for
                // the passphrase again.
                val fresh = finalizeStagedPassphrase(
                    coordinator.startFreshRemote(),
                    noChangesValidated = true,
                )
                factory.recordPrivateSyncResult(fresh)
                fresh
            }
            if (result == SyncRunResult.NoChanges) factory.confirmPendingInitialUpload()
            result
        } finally {
            factory.installBackgroundIfReady(syncImmediately = false)
        }
    }

    private suspend fun completeAccountSwitch(account: GoogleAccountIdentity) {
        val pending = pendingAccountSwitch ?: return
        try {
            passphraseOperationMutex.withLock {
                factory.withProcessSyncLock {
                    check(secretStore.commitStaged()) { "Passphrase Drive tidak dapat disimpan" }
                    try {
                        authorization.commitSelectedAccount(account)
                        if (pending.resetDataset) {
                            factory.switchSyncAccount(account)
                        } else {
                            factory.updateSyncAccount(account)
                            factory.confirmPendingInitialUpload()
                        }
                    } catch (error: Throwable) {
                        restoreAccountSwitch(pending)
                        throw error
                    }
                }
            }
            pending.previousPassphrase?.fill('\u0000')
            pendingAccountSwitch = null
            factory.endAccountSwitch()
            // Start the first sync for the new account immediately. Background
            // workers are gated by the pending-initial-upload flag and would
            // otherwise leave the UI stuck on the previous account's state.
            syncNowLocked()
        } catch (cancelled: CancellationException) {
            abortAccountSwitch()
            throw cancelled
        } catch (error: Throwable) {
            abortAccountSwitch()
            throw error
        }
    }

    private suspend fun abortAccountSwitch() {
        val pending = pendingAccountSwitch ?: run {
            secretStore.discardStaged()
            return
        }
        try {
            passphraseOperationMutex.withLock {
                factory.withProcessSyncLock {
                    restoreAccountSwitch(pending)
                }
            }
        } finally {
            pending.previousPassphrase?.fill('\u0000')
            pendingAccountSwitch = null
            factory.endAccountSwitch()
        }
    }

    private suspend fun restoreAccountSwitch(pending: PendingAccountSwitch) {
        authorization.restoreSelectedAccount(pending.previousAccount)
        if (pending.previousPassphrase == null) {
            secretStore.clear()
        } else {
            secretStore.stageReplacement(pending.previousPassphrase)
            check(secretStore.commitStaged()) { "Passphrase Drive lama tidak dapat dipulihkan" }
        }
        factory.restoreSyncState(
            pending.previousState,
            pending.previousPrivateSyncReady,
            pending.previousPrivateTeamInfo,
        )
    }

    private suspend fun prepareAccountSwitch(
        previousAccount: GoogleAccountIdentity,
        passphrase: CharArray,
    ) {
        passphraseOperationMutex.withLock {
            check(pendingAccountSwitch == null) { "Pergantian akun Google masih berlangsung" }
            val previousState = factory.readSyncState()
            val previousPrivateSyncReady = DriveSyncSwitchGate.isPrivateReady(factory.contextForScheduling())
            val previousPrivateTeamInfo = DriveSyncSwitchGate.hasPrivateTeamInfo(factory.contextForScheduling())
            val previousPassphrase = secretStore.acquirePassphrase()
            try {
                factory.beginAccountSwitch()
                secretStore.stageReplacement(passphrase)
                pendingAccountSwitch = PendingAccountSwitch(
                    account = null,
                    previousAccount = previousAccount,
                    previousState = previousState,
                    previousPassphrase = previousPassphrase,
                    previousPrivateSyncReady = previousPrivateSyncReady,
                    previousPrivateTeamInfo = previousPrivateTeamInfo,
                    resetDataset = false,
                )
            } catch (error: Throwable) {
                previousPassphrase?.fill('\u0000')
                factory.restoreSyncState(previousState, previousPrivateSyncReady, previousPrivateTeamInfo)
                factory.endAccountSwitch()
                throw error
            }
        }
    }

    private fun accountChanged(
        previous: GoogleAccountIdentity?,
        current: GoogleAccountIdentity,
    ): Boolean = previous != null &&
        (!previous.email.equals(current.email, ignoreCase = true) || previous.subjectId != current.subjectId)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class DriveSyncRuntimeFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val databaseRuntime: DatabaseRuntime,
    private val backupManager: BackupManager,
    private val secretStore: EncryptedSyncSecretStore,
    private val snapshotOperationLock: SnapshotOperationLock,
) {
    private val database get() = databaseRuntime.current()
    private val syncPreferences = context.getSharedPreferences(SYNC_PREFERENCES, Context.MODE_PRIVATE)
    private val accountStore = PreferencesSelectedGoogleAccountStore(context)
    private val stateStore = RoomSyncStateStore(databaseRuntime)
    private val localSnapshotSource = BackupManagerLocalSnapshotSource(
        backupManager,
        databaseRuntime,
        context,
        snapshotOperationLock,
    )
    private val authorizationBridge = PlayServicesAuthorizationClientBridge(context)
    private val driveClient = DriveRestV3AppDataClient()
    private val lifecycleMutex = Mutex()
    private val processSyncMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val accountSwitchInProgress = AtomicBoolean(false)

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
            initializePrivateSyncGate()
            installBackgroundIfReady(syncImmediately = true)
        }
        applicationScope.launch {
            var previousGeneration: Long? = null
            databaseRuntime.epoch.flatMapLatest { database.kronDao().observeSyncState() }
                .filterNotNull()
                .map { SyncWatch(it.localGeneration, it.lastSyncedGeneration, it.disabledDueToBilling, it.status) }
                .distinctUntilChanged()
                .collect { watch ->
                    val localGeneration = watch.localGeneration
                    val lastSyncedGeneration = watch.lastSyncedGeneration
                    val changed = previousGeneration?.let { it != localGeneration }
                        ?: (localGeneration > lastSyncedGeneration)
                    previousGeneration = localGeneration
                    if (watch.billingBlocked) {
                        deactivate()
                    } else if (!isAccountSwitching() && !DriveSyncSwitchGate.isPending(context) &&
                        changed && localGeneration > lastSyncedGeneration && isReady()) {
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

    internal suspend fun refreshAfterDatabaseActivation(): Boolean = installBackgroundIfReady(syncImmediately = false)

    internal suspend fun updateSyncAccount(account: GoogleAccountIdentity) {
        stateStore.update {
            val normalizing = it.status in setOf(SyncStatus.SYNCING, SyncStatus.RESTART_REQUIRED)
            it.copy(
                accountSubject = account.subjectId,
                accountEmail = account.email,
                status = if (normalizing) SyncStatus.IDLE else it.status,
                lastError = if (normalizing) null else it.lastError,
            )
        }
    }

    internal suspend fun recordPrivateSyncResult(result: SyncRunResult) {
        val hasSynced = result is SyncRunResult.Synchronized ||
            result is SyncRunResult.Applied ||
            result == SyncRunResult.NoChanges ||
            result == SyncRunResult.NoData
        if (!hasSynced) return
        val teamInfo = when (result) {
            SyncRunResult.NoData -> false
            is SyncRunResult.Applied -> DriveSyncSwitchGate.hasRemoteTeamInfo(context)
            is SyncRunResult.Synchronized -> if (result.uploaded) {
                database.kronDao().teamAccountCount() > 0
            } else {
                DriveSyncSwitchGate.hasRemoteTeamInfo(context)
            }
            SyncRunResult.NoChanges -> DriveSyncSwitchGate.hasRemoteTeamInfo(context)
        }
        DriveSyncSwitchGate.setPrivateSyncState(context, ready = true, teamInfo = teamInfo)
        if (teamInfo && !DriveSyncSwitchGate.isPending(context)) {
            TeamSyncScheduler.schedulePeriodic(context)
            TeamSyncScheduler.syncNow(context)
        }
    }

    private suspend fun initializePrivateSyncGate() {
        if (DriveSyncSwitchGate.hasPrivateState(context) || DriveSyncSwitchGate.isPending(context)) return
        val state = stateStore.read()
        val ready = secretStore.isStored() && state.lastSyncedGeneration >= 0 &&
            state.lastSyncedAtEpochMillis != null
        val hasTeam = ready && database.kronDao().teamAccountCount() > 0
        DriveSyncSwitchGate.setPrivateSyncState(
            context,
            ready = ready,
            teamInfo = hasTeam,
        )
        if (hasTeam) {
            TeamSyncScheduler.schedulePeriodic(context)
            TeamSyncScheduler.syncNow(context)
        }
    }

    internal suspend fun readSyncState(): SyncState = stateStore.read()

    internal suspend fun markSyncInProgress() {
        stateStore.update { it.copy(status = SyncStatus.SYNCING, lastError = null) }
    }

    internal suspend fun switchSyncAccount(account: GoogleAccountIdentity) {
        DriveSyncSwitchGate.setPending(context, true)
        DriveSyncSwitchGate.setAccountSwitchPending(context, true)
        DriveSyncSwitchGate.setRemoteTeamInfo(context, false)
        stateStore.update {
            it.copy(
                datasetId = UUID.randomUUID().toString(),
                lastSyncedGeneration = -1,
                parentSnapshotId = null,
                lastSnapshotId = null,
                lastSyncedAtEpochMillis = null,
                status = SyncStatus.IDLE,
                lastError = null,
                accountSubject = account.subjectId,
                accountEmail = account.email,
                conflictRemoteFileId = null,
            )
        }
    }

    internal suspend fun restoreSyncState(
        state: SyncState,
        privateReady: Boolean = false,
        teamInfo: Boolean = false,
    ) {
        DriveSyncSwitchGate.setPending(context, false)
        DriveSyncSwitchGate.setAccountSwitchPending(context, false)
        DriveSyncSwitchGate.setRemoteTeamInfo(context, teamInfo)
        DriveSyncSwitchGate.setPrivateSyncState(context, privateReady, teamInfo)
        stateStore.update { state }
    }

    internal suspend fun confirmPendingInitialUpload() {
        DriveSyncSwitchGate.setPending(context, false)
        DriveSyncSwitchGate.setAccountSwitchPending(context, false)
    }

    internal suspend fun beginAccountSwitch() {
        DriveSyncSwitchGate.setPending(context, true)
        DriveSyncSwitchGate.setAccountSwitchPending(context, true)
        DriveSyncSwitchGate.setRemoteTeamInfo(context, false)
        DriveSyncSwitchGate.setPrivateSyncState(context, ready = false, teamInfo = false)
        accountSwitchInProgress.set(true)
        lifecycleMutex.withLock {
            DriveSyncScheduler.cancelScheduledWork(context)
            DriveSyncServiceLocator.clear()
            TeamSyncScheduler.cancelScheduledWork(context)
        }
        processSyncMutex.withLock { Unit }
    }

    internal suspend fun endAccountSwitch() {
        accountSwitchInProgress.set(false)
        installBackgroundIfReady(syncImmediately = false)
        if (DriveSyncSwitchGate.isPrivateReady(context) && DriveSyncSwitchGate.hasPrivateTeamInfo(context)) {
            TeamSyncScheduler.schedulePeriodic(context)
        } else {
            TeamSyncScheduler.cancelScheduledWork(context)
        }
    }

    internal fun isAccountSwitching(): Boolean = accountSwitchInProgress.get()

    internal suspend fun <T> withProcessSyncLock(block: suspend () -> T): T {
        processSyncMutex.lock()
        return try {
            block()
        } finally {
            processSyncMutex.unlock()
        }
    }

    suspend fun installBackgroundIfReady(syncImmediately: Boolean = false): Boolean = lifecycleMutex.withLock {
        if (isAccountSwitching()) return@withLock false
        if (DriveSyncSwitchGate.isPending(context)) {
            DriveSyncScheduler.cancelScheduledWork(context)
            DriveSyncServiceLocator.clear()
            return@withLock false
        }
        if (!isReady()) {
            DriveSyncScheduler.cancel(context)
            return@withLock false
        }
        val coordinator = backgroundCoordinator()
        DriveSyncServiceLocator.install({ coordinator }, observer = ::recordPrivateSyncResult)
        DriveSyncScheduler.schedulePeriodic(context, isWifiOnly())
        if (syncImmediately) DriveSyncScheduler.syncNow(context, isWifiOnly())
        true
    }

    fun isWifiOnly(): Boolean = syncPreferences.getBoolean(KEY_WIFI_ONLY, false)

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

    fun networkBlockMessage(allowMetered: Boolean = false): String? {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            ?: return "Tidak ada koneksi internet"
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return "Tidak ada koneksi internet"
        }
        if (!allowMetered && isWifiOnly() && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
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
        return !state.disabledDueToBilling && state.status != SyncStatus.DISABLED
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

    internal suspend fun installAccountMigration(account: GoogleAccountIdentity): SyncRunResult {
        // After authorization is established, set up background sync for the new account
        return try {
            installBackgroundIfReady(syncImmediately = true)
            SyncRunResult.NoChanges
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SyncRunResult.Error("Migrasi akun tidak dapat disiapkan", retryable = false)
        }
    }

    private data class SyncWatch(
        val localGeneration: Long,
        val lastSyncedGeneration: Long,
        val billingBlocked: Boolean,
        val status: String,
    )

    companion object {
        const val SYNC_PREFERENCES = "kron_drive_sync_settings"
        const val KEY_WIFI_ONLY = "wifi_only"
    }

    internal fun contextForScheduling(): Context = context
}

/** Blocks background workers between a Google-account switch and its first-upload confirmation. */
internal object DriveSyncSwitchGate {
    private const val PREFERENCES = "kron.drive.switch"
    private const val KEY_PENDING = "pending_initial_upload"
    private const val KEY_ACCOUNT_SWITCH = "account_switch_pending"
    private const val KEY_PRIVATE_READY = "private_sync_ready"
    private const val KEY_PRIVATE_TEAM_INFO = "private_team_info"
    private const val KEY_REMOTE_TEAM_INFO = "remote_team_info"

    fun setPending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, pending).commit()
    }

    fun isPending(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_PENDING, false)

    fun setAccountSwitchPending(context: Context, pending: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACCOUNT_SWITCH, pending).commit()
    }

    fun isAccountSwitchPending(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_ACCOUNT_SWITCH, false)

    fun setPrivateSyncState(context: Context, ready: Boolean, teamInfo: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PRIVATE_READY, ready)
            .putBoolean(KEY_PRIVATE_TEAM_INFO, teamInfo)
            .commit()
    }

    fun hasPrivateState(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .contains(KEY_PRIVATE_READY)

    fun isPrivateReady(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_PRIVATE_READY, false)

    fun hasPrivateTeamInfo(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_PRIVATE_TEAM_INFO, false)

    fun setRemoteTeamInfo(context: Context, present: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_REMOTE_TEAM_INFO, present).commit()
    }

    fun hasRemoteTeamInfo(context: Context): Boolean = context
        .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        .getBoolean(KEY_REMOTE_TEAM_INFO, false)
}

