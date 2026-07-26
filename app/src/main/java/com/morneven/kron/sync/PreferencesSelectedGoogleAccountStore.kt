package com.morneven.kron.sync

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PreferencesSelectedGoogleAccountStore(
    context: Context,
    preferencesName: String = PREFERENCES_NAME,
) : SelectedGoogleAccountStore {
    private val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    override suspend fun read(): GoogleAccountIdentity? = mutex.withLock {
        val subject = preferences.getString(KEY_SUBJECT, null) ?: return@withLock null
        val email = preferences.getString(KEY_EMAIL, null) ?: return@withLock null
        GoogleAccountIdentity(subject, email, preferences.getString(KEY_NAME, null))
    }

    override suspend fun write(account: GoogleAccountIdentity?) = mutex.withLock {
        val editor = preferences.edit()
        if (account == null) {
            editor.clear()
        } else {
            editor.putString(KEY_SUBJECT, account.subjectId)
                .putString(KEY_EMAIL, account.email)
                .apply {
                    if (account.displayName == null) remove(KEY_NAME) else putString(KEY_NAME, account.displayName)
                }
        }
        check(editor.commit()) { "Gagal menyimpan akun sinkronisasi" }
    }

    companion object {
        private const val PREFERENCES_NAME = "kron_google_account"
        private const val KEY_SUBJECT = "subject"
        private const val KEY_EMAIL = "email"
        private const val KEY_NAME = "display_name"
    }
}
