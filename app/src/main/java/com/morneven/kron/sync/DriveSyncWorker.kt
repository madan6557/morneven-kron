package com.morneven.kron.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.security.DatabaseAccessGate
import com.morneven.kron.team.TeamAtomicSwap

/**
 * The app registers a coordinator only after optional Drive sync is configured.
 * A missing coordinator leaves local-only KRON untouched.
 */
object DriveSyncServiceLocator {
    @Volatile
    var coordinatorProvider: (() -> DriveSyncCoordinator)? = null
    @Volatile
    var resultObserver: (suspend (SyncRunResult) -> Unit)? = null

    fun coordinator(): DriveSyncCoordinator? = coordinatorProvider?.invoke()

    fun install(
        provider: () -> DriveSyncCoordinator,
        observer: (suspend (SyncRunResult) -> Unit)? = null,
    ) {
        coordinatorProvider = provider
        resultObserver = observer
    }

    fun clear() {
        coordinatorProvider = null
        resultObserver = null
    }
}

class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!DatabaseAccessGate.isReady()) return Result.retry()
        // Do not publish a private recovery package while a newer Team candidate waits for foreground activation.
        if (BackupManager.hasPendingRestore(applicationContext) || TeamAtomicSwap.hasPendingSwap(applicationContext)) {
            return Result.retry()
        }
        if (DriveSyncSwitchGate.isPending(applicationContext)) return Result.success()
        val coordinator = DriveSyncServiceLocator.coordinator() ?: return Result.success()
        val result = coordinator.syncNow()
        try {
            DriveSyncServiceLocator.resultObserver?.invoke(result)
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Sync already completed; an optional Team scheduling callback must not retry it.
        }
        return when (result) {
            is SyncRunResult.Error -> if (result.retryable) Result.retry() else Result.failure()
            SyncRunResult.FreeOnlyBlocked -> {
                DriveSyncScheduler.cancelScheduledWork(applicationContext)
                Result.success()
            }
            else -> Result.success()
        }
    }

    companion object {
        const val UNIQUE_PERIODIC_WORK = "kron_drive_sync_periodic"
        const val UNIQUE_IMMEDIATE_WORK = "kron_drive_sync_immediate"
        const val UNIQUE_DEBOUNCED_WORK = "kron_drive_sync_debounced"
    }
}

object DriveSyncScheduler {
    fun schedulePeriodic(context: Context, wifiOnly: Boolean = false) {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(
            12,
            TimeUnit.HOURS,
            1,
            TimeUnit.HOURS,
        ).setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(wifiOnly, requireBatteryNotLow = true)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DriveSyncWorker.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun syncNow(context: Context, wifiOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(wifiOnly, requireBatteryNotLow = false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DriveSyncWorker.UNIQUE_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleAfterChange(context: Context, wifiOnly: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setBackoffCriteria(BackoffPolicy.LINEAR, RETRY_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setConstraints(constraints(wifiOnly, requireBatteryNotLow = true))
            .setInitialDelay(15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DriveSyncWorker.UNIQUE_DEBOUNCED_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun cancel(context: Context) {
        cancelScheduledWork(context)
        DriveSyncServiceLocator.clear()
    }

    fun cancelScheduledWork(context: Context) {
        WorkManager.getInstance(context).apply {
            cancelUniqueWork(DriveSyncWorker.UNIQUE_PERIODIC_WORK)
            cancelUniqueWork(DriveSyncWorker.UNIQUE_IMMEDIATE_WORK)
            cancelUniqueWork(DriveSyncWorker.UNIQUE_DEBOUNCED_WORK)
        }
    }

    private fun constraints(wifiOnly: Boolean, requireBatteryNotLow: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresBatteryNotLow(requireBatteryNotLow)
        .build()

    // Linear 10s backoff: retries never grow into multi-minute waits, which
    // stacked up as each account switch left an exponential backoff behind.
    private const val RETRY_BACKOFF_MILLIS = 10_000L
}
