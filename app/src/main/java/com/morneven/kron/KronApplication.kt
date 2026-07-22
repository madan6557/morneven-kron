package com.morneven.kron

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Notification
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.morneven.kron.automation.AutomationWorker
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.security.SqlCipherLibrary
import com.morneven.kron.sync.DriveSyncRuntimeFactory
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@HiltAndroidApp
class KronApplication : Application() {
    @Inject lateinit var driveSyncRuntimeFactory: Lazy<DriveSyncRuntimeFactory>

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        SqlCipherLibrary.ensureLoaded()
        BackupManager.applyPendingRestore(this)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    fun startDataServices() {
        val dataReady = runCatching { driveSyncRuntimeFactory.get().start(applicationScope) }.isSuccess
        if (!dataReady) return
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
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
