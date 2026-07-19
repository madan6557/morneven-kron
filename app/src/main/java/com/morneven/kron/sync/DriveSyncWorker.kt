package com.morneven.kron.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * The app registers a coordinator only after optional Drive sync is configured.
 * A missing coordinator leaves local-only KRON untouched.
 */
object DriveSyncServiceLocator {
    @Volatile
    var coordinatorProvider: (() -> DriveSyncCoordinator)? = null

    fun coordinator(): DriveSyncCoordinator? = coordinatorProvider?.invoke()

    fun install(provider: () -> DriveSyncCoordinator) {
        coordinatorProvider = provider
    }

    fun clear() {
        coordinatorProvider = null
    }
}

class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val coordinator = DriveSyncServiceLocator.coordinator() ?: return Result.success()
        return when (val result = coordinator.syncNow()) {
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
    fun schedulePeriodic(context: Context, wifiOnly: Boolean = true) {
        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(
            12,
            TimeUnit.HOURS,
            1,
            TimeUnit.HOURS,
        ).setConstraints(constraints(wifiOnly, requireBatteryNotLow = true)).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DriveSyncWorker.UNIQUE_PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun syncNow(context: Context, wifiOnly: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints(wifiOnly, requireBatteryNotLow = false))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            DriveSyncWorker.UNIQUE_IMMEDIATE_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun scheduleAfterChange(context: Context, wifiOnly: Boolean = true) {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(constraints(wifiOnly, requireBatteryNotLow = true))
            .setInitialDelay(30, TimeUnit.SECONDS)
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
}
