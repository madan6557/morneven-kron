package com.morneven.kron.security

import android.content.Context
import android.annotation.SuppressLint
import com.morneven.kron.backup.BackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.team.TeamAtomicSwap
import com.morneven.kron.team.TeamKeyStore
import com.morneven.kron.sync.DataRefreshBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the process-local Room instance while a verified sync candidate is activated. */
@Singleton
class DatabaseRuntime @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val snapshotOperationLock: SnapshotOperationLock,
    private val teamKeyStore: TeamKeyStore? = null,
) {
    private val activationMutex = Mutex()
    private val mutableEpoch = MutableStateFlow(0L)
    private val mutablePendingSyncActivation = MutableStateFlow(false)
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    val epoch: StateFlow<Long> = mutableEpoch
    val pendingSyncActivation: StateFlow<Boolean> = mutablePendingSyncActivation

    init {
        refreshPendingSyncActivation()
    }

    fun current(): KronDatabase = KronDatabase.getInstance(context)

    fun markPendingSyncActivation() {
        check(preferences.edit().putBoolean(KEY_PENDING_SYNC_ACTIVATION, true).commit()) {
            "Status pembaruan sinkronisasi tidak dapat disimpan"
        }
        mutablePendingSyncActivation.value = true
    }

    fun hasPendingSyncActivation(): Boolean = refreshPendingSyncActivation()

    private fun refreshPendingSyncActivation(): Boolean {
        val hasPending = BackupManager.hasPendingRestore(context) || TeamAtomicSwap.hasPendingSwap(context)
        if (!hasPending && preferences.getBoolean(KEY_PENDING_SYNC_ACTIVATION, false)) {
            preferences.edit().remove(KEY_PENDING_SYNC_ACTIVATION).apply()
        }
        return (hasPending && preferences.getBoolean(KEY_PENDING_SYNC_ACTIVATION, false)).also {
            mutablePendingSyncActivation.value = it
        }
    }

    /**
     * Applies already validated Drive or Team staging while the process remains alive.
     * Both swap implementations restore the former database set when their activation fails.
     */
    @SuppressLint("ApplySharedPref") // The marker must be durable before reporting the swap as applied.
    suspend fun activatePendingSnapshot(): Result<Unit> = withContext(Dispatchers.IO) {
        activationMutex.withLock {
            snapshotOperationLock.withLock {
                val hasRestore = BackupManager.hasPendingRestore(context)
                val hasTeamSwap = TeamAtomicSwap.hasPendingSwap(context)
                runCatching {
                require(hasRestore || hasTeamSwap) {
                    "Tidak ada pembaruan tersinkron yang siap diterapkan"
                }
                val current = current()
                DatabaseAccessGate.markNotReady()
                current.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
                KronDatabase.closeAndForget(current)
                val restoreApplied = BackupManager.applyPendingRestore(context)
                require(!hasRestore || restoreApplied) { "Kandidat pembaruan Drive dikembalikan ke data sebelumnya" }
                val teamApplied = TeamAtomicSwap.applyPendingSwap(context)
                require(!hasTeamSwap || teamApplied) { "Kandidat pembaruan Team dikembalikan ke data sebelumnya" }
                val reopened = current()
                if (hasTeamSwap) {
                    // Refresh the private recovery package only after the new Team graph is live.
                    reopened.kronDao().markPrivateRecoveryChanged(System.currentTimeMillis())
                    teamKeyStore?.clearOrphaned(reopened.kronDao().allTeamWorkspaceIds())
                }
                mutableEpoch.value += 1
                DataRefreshBridge.emit()
                preferences.edit().remove(KEY_PENDING_SYNC_ACTIVATION).commit()
                mutablePendingSyncActivation.value = false
                DatabaseAccessGate.markReady()
                }.onFailure {
                    runCatching { KronDatabase.closeAndForget() }
                    runCatching { current() }.onSuccess { reopened ->
                        // A quarantined Team candidate is no longer pending.
                        // Clear APPLY_PENDING so the UI does not keep opening
                        // a resolver for a swap that already rolled back.
                        if (hasTeamSwap && !TeamAtomicSwap.hasPendingSwap(context)) {
                            reopened.kronDao().markFailedTeamActivations(System.currentTimeMillis())
                        }
                        DatabaseAccessGate.markReady()
                    }
                }
            }
        }
    }

    private companion object {
        const val PREFERENCES = "kron.database.runtime"
        const val KEY_PENDING_SYNC_ACTIVATION = "pending_sync_activation"
    }
}
