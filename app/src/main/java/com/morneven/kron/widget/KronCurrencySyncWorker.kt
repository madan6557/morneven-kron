package com.morneven.kron.widget

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** This worker uses only currency preferences and never opens the financial database. */
class KronCurrencySyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val sync = KronCurrencyManager.checkAndAutoSync(applicationContext)
        return when {
            sync.isSuccess -> Result.success()
            runAttemptCount < MAX_RETRIES -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        internal const val MAX_RETRIES = 2
        internal const val UNIQUE_WORK_NAME = "kron_currency_sync_when_connected"
        internal const val UNIQUE_PERIODIC_WORK_NAME = "kron_currency_periodic_sync"
    }
}

object KronCurrencySyncScheduler {
    fun scheduleIfStale(context: Context) {
        if (!KronCurrencyManager.isRatesStale(context)) return
        val request = OneTimeWorkRequestBuilder<KronCurrencySyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            KronCurrencySyncWorker.UNIQUE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    fun schedulePeriodic(context: Context) {
        val request = androidx.work.PeriodicWorkRequestBuilder<KronCurrencySyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            KronCurrencySyncWorker.UNIQUE_PERIODIC_WORK_NAME,
            androidx.work.ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
