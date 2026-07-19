package com.morneven.kron.automation

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.morneven.kron.R
import com.morneven.kron.data.AllocationBalanceRow
import com.morneven.kron.data.PeriodStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BudgetNotifier @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val preferences = context.getSharedPreferences("budget_notification_thresholds", Context.MODE_PRIVATE)

    fun sync(rows: List<AllocationBalanceRow>) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        rows.filter { it.periodStatus == PeriodStatus.ACTIVE || it.periodStatus == PeriodStatus.RESOLUTION_REQUIRED }.forEach { row ->
            val threshold = threshold(row)
            val key = "${row.periodId}:${row.id}"
            val previous = preferences.getInt(key, 0)
            if (threshold > previous) {
                notify(row, threshold)
                preferences.edit().putInt(key, threshold).apply()
            }
        }
    }

    private fun threshold(row: AllocationBalanceRow): Int {
        if (row.availableAmount < 0) return 110
        if (row.bookedAmount <= 0) return 0
        val percent = row.spentAmount.toBigInteger().multiply(BigInteger.valueOf(100)).divide(row.bookedAmount.toBigInteger()).toInt()
        return when {
            percent >= 100 -> 100
            percent >= 90 -> 90
            percent >= 75 -> 75
            else -> 0
        }
    }

    private fun notify(row: AllocationBalanceRow, threshold: Int) {
        val message = if (threshold == 110) "${row.categoryName} melewati budget dan perlu resolusi" else "${row.categoryName} telah memakai $threshold% budget"
        val publicVersion = NotificationCompat.Builder(context, AutomationWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Perhatian KRON")
            .setContentText("Buka KRON untuk melihat detail")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        val notification = NotificationCompat.Builder(context, AutomationWorker.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Perhatian budget KRON")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(key(row), notification)
    }

    private fun key(row: AllocationBalanceRow): Int = (31 * row.periodId + row.id).hashCode()
}
