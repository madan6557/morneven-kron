package com.morneven.kron

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.morneven.kron.automation.AutomationWorker
import com.morneven.kron.backup.BackupManager
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit

@HiltAndroidApp
class KronApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BackupManager.applyPendingRestore(this)
        createNotificationChannel()
        val workManager = WorkManager.getInstance(this)
        workManager.enqueue(OneTimeWorkRequestBuilder<AutomationWorker>().build())
        workManager.enqueueUniquePeriodicWork(
            AutomationWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AutomationWorker>(24, TimeUnit.HOURS).build(),
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            AutomationWorker.CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.notification_channel_description)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
