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
            KronRepository(databaseRuntime, postingEngine, TeamAccessGuard(databaseRuntime)).run {
                seedIfNeeded()
                var report = processDueRules(direction = TransactionDirection.INCOME)
                reconcilePortfolios()
                report += processDueRules(direction = TransactionDirection.EXPENSE)
                report
            }
        }
    }.fold(
        onSuccess = { report ->
            // A schedule that cannot run keeps its due date and is retried on the next pass, so the
            // worker itself has succeeded. Telling the user is what turns a silent stall into
            // something they can act on.
            AutomationNotifier(applicationContext).notifySkipped(report.skipped)
            Result.success()
        },
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
