package com.morneven.kron.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.morneven.kron.R
import com.morneven.kron.data.SkippedAutomation

/**
 * Tells the user when a scheduled transaction could not be posted.
 *
 * A skipped schedule keeps its due date and is retried on the next pass, so nothing is lost. The
 * risk is that it stays unnoticed: the user believes a recurring expense was recorded when it never
 * was. The notification carries the schedule title and the reason but never a monetary value, and
 * the lock screen version reveals nothing at all.
 */
class AutomationNotifier(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun notifySkipped(skipped: List<SkippedAutomation>) {
        if (skipped.isEmpty()) {
            preferences.edit().clear().apply()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // One notification per schedule per due date. Re-notifying on every daily pass for the same
        // stalled schedule would train the user to dismiss it without reading.
        val fresh = skipped.filter { preferences.getLong(it.ruleId, Long.MIN_VALUE) != it.dueEpochDay }
        if (fresh.isEmpty()) return
        preferences.edit().apply {
            clear()
            skipped.forEach { putLong(it.ruleId, it.dueEpochDay) }
        }.apply()

        val first = fresh.first()
        val message = if (fresh.size == 1) {
            "Jadwal \"${first.title}\" belum dapat dijalankan. ${first.reason}"
        } else {
            "${fresh.size} jadwal otomatis belum dapat dijalankan, termasuk \"${first.title}\". ${first.reason}"
        }
        val publicVersion = NotificationCompat.Builder(context, AutomationWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Perhatian KRON")
            .setContentText("Buka KRON untuk melihat detail")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, AutomationWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Jadwal otomatis tertunda")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private companion object {
        const val PREFERENCES = "automation_skipped_rules"
        const val NOTIFICATION_ID = 0x4B524E31
    }
}
