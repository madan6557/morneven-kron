package com.morneven.kron.audit

import android.content.Context
import com.morneven.kron.sync.PreferencesSelectedGoogleAccountStore
import com.morneven.kron.team.TeamDriveScopeProbeFactory

/** Selects the signed-in identity without persisting it in a Team snapshot. */
internal class AuditIdentity(context: Context) {
    private val privateAccount = PreferencesSelectedGoogleAccountStore(context)
    private val teamAccount = PreferencesSelectedGoogleAccountStore(
        context,
        TeamDriveScopeProbeFactory.TEAM_ACCOUNT_PREFERENCES,
    )

    suspend fun actor(isTeam: Boolean, keyId: String): String =
        (if (isTeam) teamAccount else privateAccount).read()?.email
            ?.trim()
            ?.lowercase()
            ?.takeIf(String::isNotBlank)
            ?: "Perangkat ${keyId.substringAfter(':', keyId).take(12)}"
}
