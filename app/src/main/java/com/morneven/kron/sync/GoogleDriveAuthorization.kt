package com.morneven.kron.sync

/**
 * Implement this interface with Credential Manager. ID tokens are used only to
 * identify the selected account and must not replace the local KRON app lock.
 */
fun interface CredentialManagerAccountSelector {
    suspend fun selectAccount(): GoogleAccountIdentity
}

sealed interface AuthorizationClientResult {
    data class Granted(
        val accessToken: String,
        val expiresAtEpochMillis: Long,
        val grantedScopes: Set<String>,
    ) : AuthorizationClientResult

    data class UserActionRequired(val resolutionId: String) : AuthorizationClientResult
    data class Failed(val message: String, val retryable: Boolean) : AuthorizationClientResult
}

/**
 * Adapter boundary for Google Play Services AuthorizationClient. The Android
 * adapter must request only the scopes supplied here and must never use an
 * embedded WebView or a client secret.
 */
interface AuthorizationClientBridge {
    suspend fun authorize(
        account: GoogleAccountIdentity?,
        requestedScopes: Set<String>,
        interactive: Boolean,
    ): AuthorizationClientResult

    suspend fun revokeAccess(account: GoogleAccountIdentity)
    suspend fun clearToken(accessToken: String)
}

interface SelectedGoogleAccountStore {
    suspend fun read(): GoogleAccountIdentity?
    suspend fun write(account: GoogleAccountIdentity?)
}

sealed interface DriveConnectResult {
    data class Connected(val account: GoogleAccountIdentity) : DriveConnectResult
    data class UserActionRequired(val account: GoogleAccountIdentity?, val resolutionId: String) : DriveConnectResult
    data class Failed(val message: String, val retryable: Boolean) : DriveConnectResult
}

sealed interface DriveAccessTokenResult {
    data class Granted(val account: GoogleAccountIdentity, val accessToken: String) : DriveAccessTokenResult
    data object Disconnected : DriveAccessTokenResult
    data class UserActionRequired(val resolutionId: String) : DriveAccessTokenResult
    data class Failed(val message: String, val retryable: Boolean) : DriveAccessTokenResult
}

interface DriveAuthorizationSession {
    suspend fun currentAccount(): GoogleAccountIdentity?
    suspend fun connect(): DriveConnectResult
    suspend fun accessToken(interactive: Boolean = false): DriveAccessTokenResult
    suspend fun disconnect()
}

class AuthorizationClientDriveSession(
    private val accountSelector: CredentialManagerAccountSelector?,
    private val authorizationClient: AuthorizationClientBridge,
    private val accountStore: SelectedGoogleAccountStore,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : DriveAuthorizationSession {
    private var cachedGrant: CachedGrant? = null

    override suspend fun currentAccount(): GoogleAccountIdentity? = accountStore.read()

    override suspend fun connect(): DriveConnectResult {
        val selector = accountSelector
            ?: return DriveConnectResult.Failed("Pemilih akun Google tidak tersedia", retryable = false)
        val account = runCatching { selector.selectAccount() }.getOrElse { error ->
            return DriveConnectResult.Failed(
                error.message ?: "Pemilihan akun Google tidak dapat diselesaikan",
                retryable = true,
            )
        }
        return acceptConnectionResult(account, authorize(account, interactive = true))
    }

    suspend fun reauthorizeCurrent(): DriveConnectResult {
        val account = accountStore.read()
            ?: return DriveConnectResult.Failed("Akun Google belum terhubung", retryable = false)
        return acceptConnectionResult(account, authorize(account, interactive = true))
    }

    /** Kept for source compatibility. Connections always begin with Credential Manager. */
    suspend fun acceptConnectionResult(
        account: GoogleAccountIdentity?,
        result: AuthorizationClientResult,
    ): DriveConnectResult = when (result) {
            is AuthorizationClientResult.Granted -> {
                if (account == null) {
                    DriveConnectResult.Failed("Pilih akun Google sebelum memberi izin Drive", retryable = true)
                } else {
                    accountStore.write(account)
                    cachedGrant = CachedGrant(account.subjectId, result.accessToken, result.expiresAtEpochMillis)
                    DriveConnectResult.Connected(account)
                }
            }
            is AuthorizationClientResult.UserActionRequired -> {
                val resolved = account ?: return DriveConnectResult.UserActionRequired(null, result.resolutionId)
                DriveConnectResult.UserActionRequired(resolved, result.resolutionId)
            }
            is AuthorizationClientResult.Failed -> DriveConnectResult.Failed(result.message, result.retryable)
        }

    override suspend fun accessToken(interactive: Boolean): DriveAccessTokenResult {
        val account = accountStore.read() ?: return DriveAccessTokenResult.Disconnected
        cachedGrant?.takeIf {
            it.subjectId == account.subjectId && it.expiresAtEpochMillis > nowEpochMillis() + TOKEN_EXPIRY_MARGIN_MILLIS
        }?.let { return DriveAccessTokenResult.Granted(account, it.accessToken) }

        return when (val result = authorize(account, interactive)) {
            is AuthorizationClientResult.Granted -> {
                cachedGrant = CachedGrant(account.subjectId, result.accessToken, result.expiresAtEpochMillis)
                DriveAccessTokenResult.Granted(account, result.accessToken)
            }
            is AuthorizationClientResult.UserActionRequired -> DriveAccessTokenResult.UserActionRequired(
                result.resolutionId,
            )
            is AuthorizationClientResult.Failed -> DriveAccessTokenResult.Failed(result.message, result.retryable)
        }
    }

    override suspend fun disconnect() {
        val account = accountStore.read()
        val token = cachedGrant?.accessToken
        cachedGrant = null
        var failure: Throwable? = null
        if (token != null) runCatching { authorizationClient.clearToken(token) }.onFailure { failure = it }
        if (account != null) runCatching { authorizationClient.revokeAccess(account) }.onFailure {
            if (failure == null) failure = it
        }
        accountStore.write(null)
        failure?.let { throw it }
    }

    private suspend fun authorize(
        account: GoogleAccountIdentity?,
        interactive: Boolean,
    ): AuthorizationClientResult {
        val result = authorizationClient.authorize(account, setOf(DRIVE_APPDATA_SCOPE), interactive)
        if (result is AuthorizationClientResult.Granted && interactive && DRIVE_APPDATA_SCOPE !in result.grantedScopes) {
            authorizationClient.clearToken(result.accessToken)
            return AuthorizationClientResult.Failed("Izin appDataFolder tidak diberikan", retryable = false)
        }
        return result
    }

    /**
     * Public wrapper to authorize a specific account. Used when the app obtains
     * the account by a manual picker and wants to continue the connect flow
     * without going through Credential Manager selection.
     */
    suspend fun authorizeAccount(account: GoogleAccountIdentity?, interactive: Boolean): AuthorizationClientResult {
        return authorize(account, interactive)
    }

    private data class CachedGrant(
        val subjectId: String,
        val accessToken: String,
        val expiresAtEpochMillis: Long,
    )

    companion object {
        private const val TOKEN_EXPIRY_MARGIN_MILLIS = 60_000L
    }
}
