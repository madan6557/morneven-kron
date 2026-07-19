package com.morneven.kron.preferences

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "kron_preferences")

@Singleton
class PrivacyPreferences @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private object Keys {
        val rememberVisibility = booleanPreferencesKey("remember_visibility")
        val lastVisibility = booleanPreferencesKey("last_visibility")
        val appLockEnabled = booleanPreferencesKey("app_lock_enabled")
        val theme = stringPreferencesKey("theme")
        val onboardingComplete = booleanPreferencesKey("onboarding_complete")
        val authFailures = androidx.datastore.preferences.core.intPreferencesKey("auth_failures")
        val authLockedUntil = androidx.datastore.preferences.core.longPreferencesKey("auth_locked_until")
        val budgetAlertsEnabled = booleanPreferencesKey("budget_alerts_enabled")
    }

    val rememberVisibility: Flow<Boolean> = context.dataStore.data.map { it[Keys.rememberVisibility] ?: false }
    val rememberedVisibility: Flow<Boolean> = context.dataStore.data.map { prefs ->
        if (prefs[Keys.rememberVisibility] == true) prefs[Keys.lastVisibility] ?: false else false
    }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.appLockEnabled] ?: false }
    val theme: Flow<String> = context.dataStore.data.map { it[Keys.theme] ?: "DARK" }
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { it[Keys.onboardingComplete] ?: false }
    val authFailures: Flow<Int> = context.dataStore.data.map { it[Keys.authFailures] ?: 0 }
    val authLockedUntil: Flow<Long> = context.dataStore.data.map { it[Keys.authLockedUntil] ?: 0L }
    val budgetAlertsEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.budgetAlertsEnabled] ?: false }

    suspend fun setRememberVisibility(value: Boolean) = context.dataStore.edit { it[Keys.rememberVisibility] = value }
    suspend fun setLastVisibility(value: Boolean) = context.dataStore.edit { it[Keys.lastVisibility] = value }
    suspend fun setAppLockEnabled(value: Boolean) = context.dataStore.edit { it[Keys.appLockEnabled] = value }
    suspend fun setTheme(value: String) = context.dataStore.edit { it[Keys.theme] = value }
    suspend fun completeOnboarding() = context.dataStore.edit { it[Keys.onboardingComplete] = true }
    suspend fun setBudgetAlertsEnabled(value: Boolean) = context.dataStore.edit { it[Keys.budgetAlertsEnabled] = value }
    suspend fun recordAuthFailure(now: Long = System.currentTimeMillis()) = context.dataStore.edit { prefs ->
        val failures = (prefs[Keys.authFailures] ?: 0) + 1
        prefs[Keys.authFailures] = failures
        prefs[Keys.authLockedUntil] = now + AuthThrottlePolicy.lockDurationMillis(failures)
    }
    suspend fun resetAuthFailures() = context.dataStore.edit {
        it[Keys.authFailures] = 0
        it[Keys.authLockedUntil] = 0L
    }
}

internal object AuthThrottlePolicy {
    fun lockDurationMillis(failureCount: Int): Long {
        require(failureCount >= 1) { "Jumlah kegagalan autentikasi tidak valid" }
        val seconds = when {
            failureCount < INITIAL_FAILURE_LIMIT -> 0L
            failureCount == INITIAL_FAILURE_LIMIT -> INITIAL_LOCK_SECONDS
            else -> (INITIAL_LOCK_SECONDS * (1L shl (failureCount - INITIAL_FAILURE_LIMIT).coerceAtMost(4)))
                .coerceAtMost(MAX_LOCK_SECONDS)
        }
        return seconds * 1_000L
    }

    private const val INITIAL_FAILURE_LIMIT = 5
    private const val INITIAL_LOCK_SECONDS = 30L
    private const val MAX_LOCK_SECONDS = 300L
}
