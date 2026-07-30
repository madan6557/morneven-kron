package com.morneven.kron.automation

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.morneven.kron.data.KronRepository
import com.morneven.kron.data.TeamAccessGuard
import com.morneven.kron.data.TransactionDirection
import com.morneven.kron.data.TeamAccessDeniedException
import com.morneven.kron.audit.EvidenceSigningKeyManager
import com.morneven.kron.audit.LedgerPostingEngine
import com.morneven.kron.security.DatabaseAccessGate
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.security.SnapshotOperationLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock

class AutomationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        if (!DatabaseAccessGate.isReady()) return Result.retry()
        val databaseRuntime = DatabaseRuntime(applicationContext, SnapshotOperationLock())
        val postingEngine = LedgerPostingEngine(applicationContext, databaseRuntime, EvidenceSigningKeyManager())
        SnapshotOperationLock().withLock {
            KronRepository(databaseRuntime, postingEngine, TeamAccessGuard(databaseRuntime)).apply {
                seedIfNeeded()
                processDueRules(direction = TransactionDirection.INCOME)
                reconcilePortfolios()
                processDueRules(direction = TransactionDirection.EXPENSE)
            }
        }
    }.fold(
        onSuccess = { Result.success() },
        onFailure = {
            when (it) {
                is CancellationException -> throw it
                is TeamAccessDeniedException -> Result.success()
                else -> Result.retry()
            }
        },
    )

    companion object {
        const val UNIQUE_WORK_NAME = "kron_daily_reconciliation"
        const val CHANNEL_ID = "kron_finance_activity"
    }
}
