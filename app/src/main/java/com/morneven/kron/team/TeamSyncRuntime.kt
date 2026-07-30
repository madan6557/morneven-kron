package com.morneven.kron.team

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.morneven.kron.BuildConfig
import com.morneven.kron.data.TeamWorkspaceStatus
import com.morneven.kron.security.DatabaseAccessGate
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.sync.AuthorizationClientDriveSession
import com.morneven.kron.sync.CredentialManagerAccountSelector
import com.morneven.kron.sync.DRIVE_FILE_SCOPE
import com.morneven.kron.sync.DriveAccessTokenResult
import com.morneven.kron.sync.DriveApiException
import com.morneven.kron.sync.PlayServicesAuthorizationClientBridge
import com.morneven.kron.sync.PreferencesSelectedGoogleAccountStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Worker lookup is installed only after the encrypted database is ready. */
object TeamSyncServiceLocator {
    @Volatile private var runner: (suspend () -> Result)? = null

    fun run(): (suspend () -> Result)? = runner

    fun install(value: suspend () -> Result) {
        runner = value
    }

    fun clear() {
        runner = null
    }
}

class TeamSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!DatabaseAccessGate.isReady()) return Result.retry()
        return TeamSyncServiceLocator.run()?.invoke() ?: Result.success()
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "kron_team_sync_periodic"
        const val UNIQUE_IMMEDIATE_WORK = "kron_team_sync_immediate"
        const val UNIQUE_DEBOUNCED_WORK = "kron_team_sync_debounced"
    }
}

object TeamSyncScheduler {
    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<TeamSyncWorker>(12, TimeUnit.HOURS, 1, TimeUnit.HOURS)
            .setConstraints(constraints(requireBatteryNotLow = true))
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            TeamSyncWorker.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun syncNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<TeamSyncWorker>()
            .setConstraints(constraints(requireBatteryNotLow = false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TeamSyncWorker.UNIQUE_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleAfterChange(context: Context) {
        val request = OneTimeWorkRequestBuilder<TeamSyncWorker>()
            .setConstraints(constraints(requireBatteryNotLow = true))
            .setInitialDelay(7, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TeamSyncWorker.UNIQUE_DEBOUNCED_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        cancelScheduledWork(context)
        TeamSyncServiceLocator.clear()
    }

    fun cancelScheduledWork(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(TeamSyncWorker.UNIQUE_PERIODIC_WORK)
            cancelUniqueWork(TeamSyncWorker.UNIQUE_IMMEDIATE_WORK)
            cancelUniqueWork(TeamSyncWorker.UNIQUE_DEBOUNCED_WORK)
        }
    }

    private fun constraints(requireBatteryNotLow: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(requireBatteryNotLow)
        .build()
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Singleton
class TeamSyncRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val databaseRuntime: DatabaseRuntime,
    private val coordinator: TeamSnapshotCoordinator,
) {
    private val database get() = databaseRuntime.current()
    private val authorization = AuthorizationClientDriveSession(
        accountSelector = CredentialManagerAccountSelector { error("Pemilihan akun Team memerlukan Activity") },
        authorizationClient = PlayServicesAuthorizationClientBridge(context),
        accountStore = PreferencesSelectedGoogleAccountStore(context, TeamDriveScopeProbeFactory.TEAM_ACCOUNT_PREFERENCES),
        requestedScopes = setOf(DRIVE_FILE_SCOPE),
    )
    private val syncMutex = Mutex()
    private val started = AtomicBoolean(false)

    fun start(applicationScope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        if (!enabled()) {
            TeamSyncScheduler.cancel(context)
            return
        }
        TeamSyncServiceLocator.install { syncInBackground() }
        TeamSyncScheduler.schedulePeriodic(context)
        TeamSyncScheduler.syncNow(context)
        applicationScope.launch {
            var previousGenerations: Map<Long, Long>? = null
            databaseRuntime.epoch
                .flatMapLatest { database.kronDao().observeAutoSyncTeamWorkspaces() }
                .map { workspaces -> workspaces.associate { it.accountId to it.generation } }
                .distinctUntilChanged()
                .collect { generations ->
                    val changed = previousGenerations?.let { it != generations } ?: false
                    previousGenerations = generations
                    if (changed && generations.isNotEmpty()) TeamSyncScheduler.scheduleAfterChange(context)
                }
        }
    }

    /** Reinstalls worker access after Room has been atomically replaced in this process. */
    fun refreshAfterDatabaseActivation() {
        if (!enabled()) return
        TeamSyncServiceLocator.install { syncInBackground() }
        TeamSyncScheduler.schedulePeriodic(context)
        TeamSyncScheduler.syncNow(context)
    }

    private suspend fun syncInBackground(): Result = syncMutex.withLock {
        if (!DatabaseAccessGate.isReady() || databaseRuntime.hasPendingSyncActivation()) return@withLock Result.success()
        val dao = database.kronDao()
        val workspaces = dao.autoSyncTeamWorkspaces()
        if (workspaces.isEmpty()) return@withLock Result.success()
        val granted = when (val token = authorization.accessToken(interactive = false)) {
            is DriveAccessTokenResult.Granted -> token.accessToken
            is DriveAccessTokenResult.Failed -> {
                if (token.retryable) return@withLock Result.retry()
                markAuthorizationRequired(workspaces.map { it.accountId to it.teamId })
                return@withLock Result.success()
            }
            DriveAccessTokenResult.Disconnected,
            is DriveAccessTokenResult.UserActionRequired,
            -> {
                markAuthorizationRequired(workspaces.map { it.accountId to it.teamId })
                return@withLock Result.success()
            }
        }
        var retry = false
        for (workspace in workspaces) {
            if (databaseRuntime.hasPendingSyncActivation()) break
            dao.markTeamWorkspaceStatus(
                workspace.accountId,
                workspace.teamId,
                TeamWorkspaceStatus.SYNCING,
                System.currentTimeMillis(),
            )
            try {
                when (coordinator.sync(granted, workspace.accountId)) {
                    TeamSyncResult.NoChanges,
                    is TeamSyncResult.Uploaded,
                    -> dao.markTeamWorkspaceStatus(
                        workspace.accountId,
                        workspace.teamId,
                        TeamWorkspaceStatus.SYNCED,
                        System.currentTimeMillis(),
                    )
                    TeamSyncResult.NoData -> dao.markTeamWorkspaceStatus(
                        workspace.accountId,
                        workspace.teamId,
                        TeamWorkspaceStatus.LOCAL_ONLY,
                        System.currentTimeMillis(),
                    )
                    TeamSyncResult.SnapshotRemoved -> Unit
                    is TeamSyncResult.Applied -> break
                    is TeamSyncResult.Conflict -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: DriveApiException) {
                when {
                    error.statusCode in setOf(401, 403, 404) -> markAuthorizationRequired(
                        listOf(workspace.accountId to workspace.teamId),
                    )
                    error.retryable -> {
                        retry = true
                        dao.markTeamWorkspaceStatus(
                            workspace.accountId,
                            workspace.teamId,
                            TeamWorkspaceStatus.WAITING_NETWORK,
                            System.currentTimeMillis(),
                        )
                    }
                    else -> dao.markTeamWorkspaceStatus(
                        workspace.accountId,
                        workspace.teamId,
                        TeamWorkspaceStatus.FAILED,
                        System.currentTimeMillis(),
                    )
                }
            } catch (_: IOException) {
                retry = true
                dao.markTeamWorkspaceStatus(
                    workspace.accountId,
                    workspace.teamId,
                    TeamWorkspaceStatus.WAITING_NETWORK,
                    System.currentTimeMillis(),
                )
            } catch (_: Exception) {
                // A corrupt candidate is already rejected by staging. Leave the active graph untouched.
                dao.markTeamWorkspaceStatus(
                    workspace.accountId,
                    workspace.teamId,
                    TeamWorkspaceStatus.FAILED,
                    System.currentTimeMillis(),
                )
            }
        }
        if (retry) Result.retry() else Result.success()
    }

    private suspend fun markAuthorizationRequired(workspaces: List<Pair<Long, String>>) {
        val now = System.currentTimeMillis()
        val dao = database.kronDao()
        workspaces.forEach { (accountId, teamId) ->
            dao.markTeamWorkspaceStatus(accountId, teamId, TeamWorkspaceStatus.AUTH_REQUIRED, now)
        }
    }

    private fun enabled(): Boolean = BuildConfig.DRIVE_SYNC_CONFIGURED && BuildConfig.TEAM_ACCOUNT_ENABLED
}
