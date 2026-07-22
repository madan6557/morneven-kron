package com.morneven.kron.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.data.KronRepository
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.security.DatabaseAccessGate

class AutomationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        if (!DatabaseAccessGate.isReady()) return Result.retry()
        val database = KronDatabase.getInstance(applicationContext)
        val postingEngine = LedgerPostingEngine(applicationContext, database, EvidenceSigningKeyManager())
        KronRepository(database, postingEngine).apply {
            seedIfNeeded()
            processDueRules(direction = TransactionDirection.INCOME)
            reconcilePortfolios()
            processDueRules(direction = TransactionDirection.EXPENSE)
        }
    }.fold(onSuccess = { Result.success() }, onFailure = { Result.retry() })

    companion object {
        const val UNIQUE_WORK_NAME = "kron_daily_reconciliation"
        const val CHANNEL_ID = "kron_finance_activity"
    }
}
