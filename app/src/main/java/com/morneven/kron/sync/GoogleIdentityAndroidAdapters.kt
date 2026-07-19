package com.morneven.kron.sync

import android.accounts.Account
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.activity.result.IntentSenderRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout

class AndroidCredentialManagerAccountSelector(
    private val activity: Activity,
    private val webClientId: String,
    private val credentialManager: CredentialManager = CredentialManager.create(activity),
) : CredentialManagerAccountSelector {
    init {
        require(webClientId.isNotBlank()) { "Google Web Client ID belum dikonfigurasi" }
    }

    override suspend fun selectAccount(): GoogleAccountIdentity {
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()
        val credential = try {
            withTimeout(30_000L) {
                credentialManager.getCredential(activity, request).credential
            }
        } catch (error: TimeoutCancellationException) {
            Log.w(TAG, "CredentialManager.getCredential() timed out after 30s")
            throw IllegalStateException(
                "Pemilihan akun Google tidak merespon. Coba gunakan WiFi lalu sambungkan Drive.",
                error,
            )
        } catch (error: NoCredentialException) {
            Log.w(TAG, "NoCredentialException: ${error.message}")
            throw IllegalStateException(
                "Tidak ada akun Google yang tersedia. Tambahkan akun Google di perangkat lalu coba lagi.",
                error,
            )
        } catch (error: GetCredentialCancellationException) {
            Log.w(TAG, "GetCredentialCancellationException: ${error.message}")
            throw IllegalStateException("Pemilihan akun Google dibatalkan", error)
        } catch (error: GetCredentialException) {
            Log.w(TAG, "GetCredentialException type=${error.type} message=${error.message}")
            throw IllegalStateException(
                "Pemilih akun Google tidak tersedia (${error.type}). " +
                    "Perbarui Google Play Services atau gunakan WiFi lalu coba lagi.",
                error,
            )
        }
        require(
            credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL,
        ) { "Credential Google tidak dikenali" }
        val google = GoogleIdTokenCredential.createFrom(credential.data)
        val subject = google.uniqueId.takeIf(String::isNotBlank)
            ?: google.id.takeIf(String::isNotBlank)
            ?: error("Identitas akun Google kosong")
        val email = google.email?.takeIf(String::isNotBlank) ?: error("Email akun Google kosong")
        return GoogleAccountIdentity(subject, email, google.displayName)
    }

    companion object {
        private const val TAG = "KronAccountSelector"
    }
}

/**
 * Thin adapter around Play Services AuthorizationClient. PendingIntent objects
 * stay only in memory and are never serialized or logged.
 */
class PlayServicesAuthorizationClientBridge(
    context: Context,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : AuthorizationClientBridge {
    private val client = Identity.getAuthorizationClient(context)
    private val resolutions = ConcurrentHashMap<String, PendingIntent>()

    override suspend fun authorize(
        account: GoogleAccountIdentity?,
        requestedScopes: Set<String>,
        interactive: Boolean,
    ): AuthorizationClientResult {
        require(requestedScopes == setOf(DRIVE_APPDATA_SCOPE)) { "KRON hanya mengizinkan scope appDataFolder" }
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes.map(::Scope))
            .setOptOutIncludingGrantedScopes(true)
        if (account != null) {
            builder.setAccount(Account(account.email, GOOGLE_ACCOUNT_TYPE))
        }
        val request = builder.build()
        return runCatching { client.authorize(request).awaitTask() }
            .fold(
                onSuccess = { it.toBridgeResult(interactive) },
                onFailure = { AuthorizationClientResult.Failed("Otorisasi Google Drive gagal", retryable = true) },
            )
    }

    fun pendingIntent(resolutionId: String): PendingIntent? = resolutions[resolutionId]

    fun intentSenderRequest(resolutionId: String): IntentSenderRequest {
        val pending = requireNotNull(resolutions[resolutionId]) { "Permintaan otorisasi sudah tidak berlaku" }
        return IntentSenderRequest.Builder(pending.intentSender).build()
    }

    fun completeResolution(
        resolutionId: String,
        resultCode: Int,
        resultData: Intent?,
    ): AuthorizationClientResult {
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            resolutions.remove(resolutionId)
            return AuthorizationClientResult.Failed("Izin Google Drive dibatalkan", retryable = false)
        }
        return completeResolution(resolutionId, resultData)
    }

    fun completeResolution(resolutionId: String, resultData: Intent): AuthorizationClientResult {
        require(resolutions.remove(resolutionId) != null) { "Permintaan otorisasi sudah tidak berlaku" }
        return runCatching { client.getAuthorizationResultFromIntent(resultData) }
            .fold(
                onSuccess = { it.toBridgeResult(interactive = false) },
                onFailure = { AuthorizationClientResult.Failed("Izin Google Drive tidak diberikan", retryable = false) },
            )
    }

    fun discardResolution(resolutionId: String) {
        resolutions.remove(resolutionId)
    }

    override suspend fun revokeAccess(account: GoogleAccountIdentity) {
        RevokeAccessRequest.builder()
            .setAccount(Account(account.email, GOOGLE_ACCOUNT_TYPE))
            .setScopes(listOf(Scope(DRIVE_APPDATA_SCOPE)))
            .build()
            .let(client::revokeAccess)
            .awaitTask()
    }

    override suspend fun clearToken(accessToken: String) {
        if (accessToken.isBlank()) return
        ClearTokenRequest.builder()
            .setToken(accessToken)
            .build()
            .let(client::clearToken)
            .awaitTask()
    }

    private fun AuthorizationResult.toBridgeResult(interactive: Boolean): AuthorizationClientResult {
        if (hasResolution()) {
            if (!interactive) return AuthorizationClientResult.UserActionRequired(NON_INTERACTIVE_RESOLUTION)
            val id = UUID.randomUUID().toString()
            resolutions[id] = requireNotNull(pendingIntent)
            return AuthorizationClientResult.UserActionRequired(id)
        }
        val token = accessToken?.takeIf(String::isNotBlank)
            ?: return AuthorizationClientResult.Failed("Access token Google Drive kosong", retryable = false)
        return AuthorizationClientResult.Granted(
            accessToken = token,
            expiresAtEpochMillis = nowEpochMillis() + TOKEN_CACHE_MILLIS,
            grantedScopes = grantedScopes.toSet(),
        )
    }

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val TOKEN_CACHE_MILLIS = 50 * 60 * 1000L
        const val NON_INTERACTIVE_RESOLUTION = "authorization_required"
    }
}
