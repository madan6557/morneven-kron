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

    suspend fun setRememberVisibility(value: Boolean) = context.dataStore.edit { it[Keys.rememberVisibility] = value }
    suspend fun setLastVisibility(value: Boolean) = context.dataStore.edit { it[Keys.lastVisibility] = value }
    suspend fun setAppLockEnabled(value: Boolean) = context.dataStore.edit { it[Keys.appLockEnabled] = value }
    suspend fun setTheme(value: String) = context.dataStore.edit { it[Keys.theme] = value }
    suspend fun completeOnboarding() = context.dataStore.edit { it[Keys.onboardingComplete] = true }
    suspend fun recordAuthFailure(now: Long = System.currentTimeMillis()) = context.dataStore.edit { prefs ->
        val failures = (prefs[Keys.authFailures] ?: 0) + 1
        val lockSeconds = when {
            failures < 5 -> 0L
            failures == 5 -> 30L
            else -> (30L * (1L shl (failures - 5).coerceAtMost(4))).coerceAtMost(300L)
        }
        prefs[Keys.authFailures] = failures
        prefs[Keys.authLockedUntil] = now + lockSeconds * 1_000L
    }
    suspend fun resetAuthFailures() = context.dataStore.edit {
        it[Keys.authFailures] = 0
        it[Keys.authLockedUntil] = 0L
    }
}
