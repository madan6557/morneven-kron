package com.morneven.kron.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        // Some devices (notably POCO F7) have slow Credential Manager responses.
        // Increase the timeout on those devices to avoid premature fallback that
        // causes a slow account-picker UX. Detect device model and tune timeout.
        val model = android.os.Build.MODEL.orEmpty().lowercase()
        val isSlowCredentialDevice = model.contains("f7") || model.contains("poco")
        val initialTimeout = if (isSlowCredentialDevice) 30_000L else 15_000L

        return try {
            selectViaCredentialManager(retryTimeout = initialTimeout)
        } catch (error: TimeoutCancellationException) {
            // Try AccountManager quick fallback first
            getAccountFromAccountManager()?.let { return it }
            // One retry with a longer timeout
            try {
                // Second attempt should be generous on slow devices
                val secondAttemptTimeout = if (isSlowCredentialDevice) 60_000L else 30_000L
                selectViaCredentialManager(retryTimeout = secondAttemptTimeout)
            } catch (second: TimeoutCancellationException) {
                selectViaAccountPicker()
            }
        } catch (error: NoCredentialException) {
            throw IllegalStateException(
                "Tidak ada akun Google yang tersedia. Tambahkan akun Google di perangkat lalu coba lagi.",
                error,
            )
        } catch (error: GetCredentialCancellationException) {
            throw IllegalStateException("Pemilihan akun Google dibatalkan", error)
        } catch (error: GetCredentialException) {
            getAccountFromAccountManager()?.let { return it }
            throw IllegalStateException(
                "Pemilih akun Google tidak tersedia (${error.type}). Perbarui Google Play Services atau periksa koneksi internet.",
                error,
            )
        }
    }

    private suspend fun selectViaCredentialManager(retryTimeout: Long = 15_000L): GoogleAccountIdentity {
        val googleOption = GetGoogleIdOption.Builder()
            .setServerClientId(webClientId)
            .setFilterByAuthorizedAccounts(false)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()
        val credential = withTimeout(retryTimeout) {
            credentialManager.getCredential(activity, request).credential
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

    private fun getAccountFromAccountManager(): GoogleAccountIdentity? {
        return try {
            val am = AccountManager.get(activity)
            val accounts: Array<Account> = am.getAccountsByType("com.google")
            if (accounts.isEmpty()) return null
            // Prefer a primary account; otherwise take first
            val account = accounts.first()
            val email = account.name
            GoogleAccountIdentity(email, email, null)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun selectViaAccountPicker(): GoogleAccountIdentity = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val registry = (activity as? ComponentActivity)?.activityResultRegistry
                ?: run {
                    continuation.resumeWithException(IllegalStateException("Pemilih akun tidak tersedia"))
                    return@suspendCancellableCoroutine
                }
            val pickerIntent = AccountManager.newChooseAccountIntent(
                null, null, arrayOf("com.google"),
                true, "Pilih akun Google untuk KRON", null, null, null,
            )
            val key = "account_picker_${System.nanoTime()}"
            val launcher = registry.register(
                key,
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                val data = result.data
                if (result.resultCode != Activity.RESULT_OK || data == null) {
                    continuation.resumeWithException(
                        IllegalStateException("Pemilihan akun Google dibatalkan"),
                    )
                    return@register
                }
                val email = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                if (email.isNullOrBlank()) {
                    continuation.resumeWithException(
                        IllegalStateException("Akun Google tidak ditemukan"),
                    )
                    return@register
                }
                continuation.resume(GoogleAccountIdentity(email, email, null))
            }
            continuation.invokeOnCancellation {
                try { launcher.unregister() } catch (_: RuntimeException) { }
            }
            launcher.launch(pickerIntent)
        }
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
    private val pickerResolutions = ConcurrentHashMap<String, PendingDrivePicker>()

    override suspend fun authorize(
        account: GoogleAccountIdentity?,
        requestedScopes: Set<String>,
        interactive: Boolean,
    ): AuthorizationClientResult {
        require(requestedScopes in setOf(setOf(DRIVE_APPDATA_SCOPE), setOf(DRIVE_FILE_SCOPE))) {
            "Scope Google Drive tidak didukung"
        }
        val builder = AuthorizationRequest.builder()
            .setRequestedScopes(requestedScopes.map(::Scope))
        if (account != null) {
            builder.setAccount(Account(account.email, GOOGLE_ACCOUNT_TYPE))
        }
        val request = builder.build()
        return try {
            client.authorize(request).awaitTask().toBridgeResult(interactive)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            AuthorizationClientResult.Failed("Otorisasi Google Drive gagal", retryable = true)
        }
    }

    fun pendingIntent(resolutionId: String): PendingIntent? = resolutions[resolutionId]

    fun intentSenderRequest(resolutionId: String): IntentSenderRequest {
        val pending = resolutions[resolutionId] ?: pickerResolutions[resolutionId]?.pendingIntent
        requireNotNull(pending) { "Permintaan otorisasi sudah tidak berlaku" }
        return IntentSenderRequest.Builder(pending.intentSender).build()
    }

    suspend fun openDriveFolderPicker(
        account: GoogleAccountIdentity,
        folderId: String,
    ): DrivePickerStartResult {
        require(folderId.length in 1..512 && folderId.all { it.isLetterOrDigit() || it in "-_." }) {
            "Folder Team tidak valid"
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .setAccount(Account(account.email, GOOGLE_ACCOUNT_TYPE))
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "false")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "true")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_MIMETYPES, DRIVE_FOLDER_MIME_TYPE)
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_FILE_IDS, folderId)
            .build()
        return try {
            val result = client.authorize(request).awaitTask()
            if (!result.hasResolution()) {
                DrivePickerStartResult.Failed("Google Picker tidak menampilkan pemilih folder")
            } else {
                val resolutionId = UUID.randomUUID().toString()
                pickerResolutions[resolutionId] = PendingDrivePicker(
                    pendingIntent = requireNotNull(result.pendingIntent),
                    account = account,
                    expectedFileId = folderId,
                    label = "folder Team",
                )
                DrivePickerStartResult.UserActionRequired(resolutionId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DrivePickerStartResult.Failed("Google Picker tidak dapat dibuka")
        }
    }

    fun completeDriveFolderPicker(
        resolutionId: String,
        resultCode: Int,
        resultData: Intent?,
    ): DrivePickerCompletionResult {
        return completeDrivePicker(resolutionId, resultCode, resultData)
    }

    suspend fun openDriveFilePicker(
        account: GoogleAccountIdentity,
        fileId: String,
    ): DrivePickerStartResult {
        require(fileId.length in 1..512 && fileId.all { it.isLetterOrDigit() || it in "-_." }) {
            "File snapshot Team tidak valid"
        }
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(DRIVE_FILE_SCOPE)))
            .setAccount(Account(account.email, GOOGLE_ACCOUNT_TYPE))
            .setOptOutIncludingGrantedScopes(true)
            .setPrompt(AuthorizationRequest.Prompt.CONSENT)
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_OAUTH_TRIGGER, "true")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_MULTIPLE, "false")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_ALLOW_FOLDER_SELECTION, "false")
            .addResourceParameter(AuthorizationRequest.ResourceParameter.PICKER_FILE_IDS, fileId)
            .build()
        return try {
            val result = client.authorize(request).awaitTask()
            if (!result.hasResolution()) DrivePickerStartResult.Failed("Google Picker tidak menampilkan file Team")
            else {
                val resolutionId = UUID.randomUUID().toString()
                pickerResolutions[resolutionId] = PendingDrivePicker(
                    pendingIntent = requireNotNull(result.pendingIntent), account = account,
                    expectedFileId = fileId, label = "file Team",
                )
                DrivePickerStartResult.UserActionRequired(resolutionId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            DrivePickerStartResult.Failed("Google Picker tidak dapat dibuka")
        }
    }

    fun completeDriveFilePicker(
        resolutionId: String,
        resultCode: Int,
        resultData: Intent?,
    ): DrivePickerCompletionResult = completeDrivePicker(resolutionId, resultCode, resultData)

    private fun completeDrivePicker(
        resolutionId: String,
        resultCode: Int,
        resultData: Intent?,
    ): DrivePickerCompletionResult {
        val pending = pickerResolutions.remove(resolutionId)
            ?: return DrivePickerCompletionResult.Failed("Permintaan Google Picker sudah tidak berlaku")
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            return DrivePickerCompletionResult.Failed("Pemilihan ${pending.label} dibatalkan")
        }
        return runCatching {
            val result = client.getAuthorizationResultFromIntent(resultData)
            require(result.grantedScopes.contains(DRIVE_FILE_SCOPE)) { "Scope drive.file tidak diberikan" }
            require(
                pickedDriveFileMatches(
                    result.tokenResponseParams?.getString(PICKED_FILE_IDS),
                    pending.expectedFileId,
                ),
            ) { "File yang dipilih tidak cocok dengan kode Team" }
            val token = requireNotNull(result.accessToken?.takeIf(String::isNotBlank)) {
                "Access token Google Picker kosong"
            }
            DrivePickerCompletionResult.Granted(pending.account, token, pending.expectedFileId)
        }.getOrElse { error ->
            DrivePickerCompletionResult.Failed(error.message ?: "Hasil Google Picker tidak valid")
        }
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
        pickerResolutions.remove(resolutionId)
    }

    override suspend fun revokeAccess(account: GoogleAccountIdentity) {
        revokeAccess(account, setOf(DRIVE_APPDATA_SCOPE))
    }

    override suspend fun revokeAccess(account: GoogleAccountIdentity, scopes: Set<String>) {
        require(scopes in setOf(setOf(DRIVE_APPDATA_SCOPE), setOf(DRIVE_FILE_SCOPE))) {
            "Scope Google Drive tidak didukung"
        }
        RevokeAccessRequest.builder()
            .setAccount(Account(account.email, GOOGLE_ACCOUNT_TYPE))
            .setScopes(scopes.map(::Scope))
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

    private data class PendingDrivePicker(
        val pendingIntent: PendingIntent,
        val account: GoogleAccountIdentity,
        val expectedFileId: String,
        val label: String,
    )

    private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { if (continuation.isActive) continuation.resume(it) }
        addOnFailureListener { if (continuation.isActive) continuation.resumeWithException(it) }
        addOnCanceledListener { continuation.cancel() }
    }

    companion object {
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val DRIVE_FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val PICKED_FILE_IDS = "picked_file_ids"
        private const val TOKEN_CACHE_MILLIS = 50 * 60 * 1000L
        const val NON_INTERACTIVE_RESOLUTION = "authorization_required"
    }
}

sealed interface DrivePickerStartResult {
    data class UserActionRequired(val resolutionId: String) : DrivePickerStartResult
    data class Failed(val message: String) : DrivePickerStartResult
}

sealed interface DrivePickerCompletionResult {
    data class Granted(
        val account: GoogleAccountIdentity,
        val accessToken: String,
        val fileId: String,
    ) : DrivePickerCompletionResult

    data class Failed(val message: String) : DrivePickerCompletionResult
}

internal fun pickedDriveFileMatches(rawPickedIds: String?, expectedFileId: String): Boolean =
    rawPickedIds?.split(',')?.map(String::trim)?.filter(String::isNotBlank) == listOf(expectedFileId)

internal fun pickedDriveFolderMatches(rawPickedIds: String?, expectedFolderId: String): Boolean =
    pickedDriveFileMatches(rawPickedIds, expectedFolderId)
