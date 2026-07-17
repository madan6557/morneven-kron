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
    }

    val rememberVisibility: Flow<Boolean> = context.dataStore.data.map { it[Keys.rememberVisibility] ?: false }
    val rememberedVisibility: Flow<Boolean> = context.dataStore.data.map { prefs ->
        if (prefs[Keys.rememberVisibility] == true) prefs[Keys.lastVisibility] ?: false else false
    }
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.appLockEnabled] ?: false }
    val theme: Flow<String> = context.dataStore.data.map { it[Keys.theme] ?: "DARK" }
    val onboardingComplete: Flow<Boolean> = context.dataStore.data.map { it[Keys.onboardingComplete] ?: false }

    suspend fun setRememberVisibility(value: Boolean) = context.dataStore.edit { it[Keys.rememberVisibility] = value }
    suspend fun setLastVisibility(value: Boolean) = context.dataStore.edit { it[Keys.lastVisibility] = value }
    suspend fun setAppLockEnabled(value: Boolean) = context.dataStore.edit { it[Keys.appLockEnabled] = value }
    suspend fun setTheme(value: String) = context.dataStore.edit { it[Keys.theme] = value }
    suspend fun completeOnboarding() = context.dataStore.edit { it[Keys.onboardingComplete] = true }
}
