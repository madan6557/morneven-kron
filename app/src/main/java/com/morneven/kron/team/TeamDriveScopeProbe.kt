package com.morneven.kron.team

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.morneven.kron.BuildConfig
import com.morneven.kron.data.TeamRole
import com.morneven.kron.sync.AndroidCredentialManagerAccountSelector
import com.morneven.kron.sync.AuthorizationClientDriveSession
import com.morneven.kron.sync.CredentialManagerAccountSelector
import com.morneven.kron.sync.DRIVE_FILE_SCOPE
import com.morneven.kron.sync.DriveAccessTokenResult
import com.morneven.kron.sync.DriveConnectResult
import com.morneven.kron.sync.DrivePickerCompletionResult
import com.morneven.kron.sync.DrivePickerStartResult
import com.morneven.kron.sync.GoogleAccountIdentity
import com.morneven.kron.sync.PlayServicesAuthorizationClientBridge
import com.morneven.kron.sync.PreferencesSelectedGoogleAccountStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

sealed interface TeamScopeProbeResult {
    data class Ready(val message: String) : TeamScopeProbeResult
    data class UserActionRequired(
        val account: GoogleAccountIdentity,
        val resolutionId: String,
    ) : TeamScopeProbeResult
    data class PickerActionRequired(val resolutionId: String) : TeamScopeProbeResult
    data class Failed(val message: String) : TeamScopeProbeResult
}

class TeamDriveScopeProbe internal constructor(
    private val context: Context,
    private val accountSelector: CredentialManagerAccountSelector,
    private val accountStore: PreferencesSelectedGoogleAccountStore,
    private val authorization: AuthorizationClientDriveSession,
    private val authorizationBridge: PlayServicesAuthorizationClientBridge,
    private val drive: TeamDriveRestClient,
) {
    private val preferences = context.getSharedPreferences(PROBE_PREFERENCES, Context.MODE_PRIVATE)

    suspend fun connect(): DriveConnectResult = authorization.connect()

    suspend fun selectMemberAccount(): TeamScopeProbeResult = try {
        val account = accountSelector.selectAccount()
        requireMember(account)
        accountStore.write(account)
        TeamScopeProbeResult.Ready("Akun Member dipilih. Lanjutkan ke Google Picker.")
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IllegalStateException) {
        TeamScopeProbeResult.Failed(error.message ?: "Akun Member tidak dapat dipilih")
    } catch (_: Exception) {
        TeamScopeProbeResult.Failed("Pemilihan akun Member dibatalkan")
    }

    suspend fun selectOwnerAndRemoveProbe(): TeamScopeProbeResult = try {
        val account = accountSelector.selectAccount()
        requireOwner(account)
        accountStore.write(account)
        removeProbe()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IllegalStateException) {
        TeamScopeProbeResult.Failed(error.message ?: "Akun Owner tidak dapat dipilih")
    } catch (_: Exception) {
        TeamScopeProbeResult.Failed("Pemilihan akun Owner dibatalkan")
    }

    fun authorizationRequest(resolutionId: String): IntentSenderRequest =
        authorizationBridge.intentSenderRequest(resolutionId)

    suspend fun completeAuthorization(
        account: GoogleAccountIdentity?,
        resolutionId: String,
        resultCode: Int,
        data: Intent?,
    ): DriveConnectResult = authorization.acceptConnectionResult(
        account,
        authorizationBridge.completeResolution(resolutionId, resultCode, data),
    )

    suspend fun cancelAuthorization(resolutionId: String) {
        authorizationBridge.discardResolution(resolutionId)
    }

    suspend fun createOwnerProbe(memberEmail: String, role: String): TeamScopeProbeResult = withToken { account, token ->
        require(role == TeamRole.EDITOR || role == TeamRole.VIEWER) { "Role probe tidak valid" }
        val teamId = java.util.UUID.randomUUID().toString()
        val workspace = drive.createWorkspace(token, teamId)
        check(workspace.capabilities.canShare && workspace.capabilities.canWrite && !workspace.writersCanShare) {
            "Workspace Owner tidak memiliki capability aman"
        }
        try {
            drive.addMember(token, workspace.folderId, memberEmail, role)
        } catch (error: Exception) {
            runCatching { drive.delete(token, workspace.folderId) }
            throw error
        }
        check(
            preferences.edit()
                .putString(KEY_FOLDER_ID, workspace.folderId)
                .putString(KEY_OWNER_HASH, subjectHash(account.subjectId))
                .putString(KEY_EXPECTED_ROLE, role)
                .commit(),
        ) { "Status probe Team tidak dapat disimpan" }
        TeamScopeProbeResult.Ready("Workspace uji dibuat. Hubungkan akun Member lalu verifikasi akses.")
    }

    suspend fun openMemberWorkspacePicker(): TeamScopeProbeResult {
        val account = authorization.currentAccount()
            ?: return TeamScopeProbeResult.Failed("Hubungkan akun Member terlebih dahulu")
        return try {
            requireMember(account)
            val folderId = preferences.getString(KEY_FOLDER_ID, null) ?: error("Workspace uji belum dibuat")
            when (val result = authorizationBridge.openDriveFolderPicker(account, folderId)) {
                is DrivePickerStartResult.UserActionRequired -> TeamScopeProbeResult.PickerActionRequired(result.resolutionId)
                is DrivePickerStartResult.Failed -> TeamScopeProbeResult.Failed(result.message)
            }
        } catch (error: IllegalStateException) {
            TeamScopeProbeResult.Failed(error.message ?: "Google Picker tidak dapat disiapkan")
        }
    }

    suspend fun completeMemberWorkspacePicker(
        resolutionId: String,
        resultCode: Int,
        data: Intent?,
    ): TeamScopeProbeResult = when (
        val result = authorizationBridge.completeDriveFolderPicker(resolutionId, resultCode, data)
    ) {
        is DrivePickerCompletionResult.Failed -> TeamScopeProbeResult.Failed(result.message)
        is DrivePickerCompletionResult.Granted -> try {
            requireMember(result.account)
            validateMemberWorkspace(result.accessToken, result.folderId)
        } catch (error: IllegalStateException) {
            TeamScopeProbeResult.Failed(error.message ?: "Workspace Team tidak dapat diverifikasi")
        } catch (_: Exception) {
            TeamScopeProbeResult.Failed("Workspace Team yang dipilih tidak dapat dibuka")
        }
    }

    suspend fun removeProbe(): TeamScopeProbeResult = withToken { account, token ->
        val folderId = preferences.getString(KEY_FOLDER_ID, null) ?: error("Workspace uji belum dibuat")
        requireOwner(account)
        drive.delete(token, folderId)
        check(preferences.edit().clear().commit()) { "Status probe Team tidak dapat dibersihkan" }
        TeamScopeProbeResult.Ready("Workspace uji sudah dihapus.")
    }

    fun hasProbe(): Boolean = preferences.contains(KEY_FOLDER_ID)

    private fun requireMember(account: GoogleAccountIdentity) {
        val ownerHash = preferences.getString(KEY_OWNER_HASH, null) ?: error("Identitas Owner probe tidak tersedia")
        check(!constantTimeEqual(subjectHash(account.subjectId), ownerHash)) {
            "Akun yang dipilih masih akun Owner"
        }
    }

    private fun requireOwner(account: GoogleAccountIdentity) {
        val ownerHash = preferences.getString(KEY_OWNER_HASH, null) ?: error("Identitas Owner probe tidak tersedia")
        check(constantTimeEqual(subjectHash(account.subjectId), ownerHash)) {
            "Pilih akun Owner untuk menghapus workspace uji"
        }
    }

    private suspend fun validateMemberWorkspace(token: String, folderId: String): TeamScopeProbeResult {
        val expectedRole = preferences.getString(KEY_EXPECTED_ROLE, null) ?: error("Role probe tidak tersedia")
        val workspace = drive.workspace(token, folderId)
        check(workspace.capabilities.canRead) { "Member tidak dapat membaca workspace" }
        check(!workspace.capabilities.canShare) { "Member tidak boleh mengelola collaborator" }
        check(workspace.capabilities.canWrite == (expectedRole == TeamRole.EDITOR)) {
            "Capability Drive tidak cocok dengan role undangan"
        }
        return TeamScopeProbeResult.Ready(
            if (workspace.capabilities.canWrite) "Google Picker membuka workspace Team untuk Editor."
            else "Google Picker membuka workspace Team untuk Viewer.",
        )
    }

    private suspend fun withToken(
        block: suspend (GoogleAccountIdentity, String) -> TeamScopeProbeResult,
    ): TeamScopeProbeResult = when (val token = authorization.accessToken(interactive = true)) {
        is DriveAccessTokenResult.Granted -> try {
            block(token.account, token.accessToken)
        } catch (error: IllegalArgumentException) {
            TeamScopeProbeResult.Failed(error.message ?: "Input probe Team tidak valid")
        } catch (error: IllegalStateException) {
            TeamScopeProbeResult.Failed(error.message ?: "Probe Team gagal")
        } catch (_: Exception) {
            TeamScopeProbeResult.Failed("Google Drive tidak dapat menyelesaikan probe Team")
        }
        is DriveAccessTokenResult.UserActionRequired -> {
            val account = authorization.currentAccount()
                ?: return TeamScopeProbeResult.Failed("Hubungkan akun Google terlebih dahulu")
            TeamScopeProbeResult.UserActionRequired(account, token.resolutionId)
        }
        is DriveAccessTokenResult.Failed -> TeamScopeProbeResult.Failed(token.message)
        DriveAccessTokenResult.Disconnected -> TeamScopeProbeResult.Failed("Hubungkan akun Google terlebih dahulu")
    }

    private fun subjectHash(subject: String): String = MessageDigest.getInstance("SHA-256")
        .digest(subject.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun constantTimeEqual(left: String, right: String): Boolean =
        MessageDigest.isEqual(left.toByteArray(Charsets.US_ASCII), right.toByteArray(Charsets.US_ASCII))

    companion object {
        private const val PROBE_PREFERENCES = "kron_team_scope_probe"
        private const val KEY_FOLDER_ID = "folder_id"
        private const val KEY_OWNER_HASH = "owner_hash"
        private const val KEY_EXPECTED_ROLE = "expected_role"
    }
}

@Singleton
class TeamDriveScopeProbeFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val authorizationBridge = PlayServicesAuthorizationClientBridge(context)
    private val accountStore = PreferencesSelectedGoogleAccountStore(context, TEAM_ACCOUNT_PREFERENCES)

    fun create(activity: Activity): TeamDriveScopeProbe {
        check(BuildConfig.DRIVE_SYNC_CONFIGURED) { "Google Drive belum dikonfigurasi" }
        val accountSelector = AndroidCredentialManagerAccountSelector(activity, BuildConfig.GOOGLE_WEB_CLIENT_ID)
        return TeamDriveScopeProbe(
            context = context,
            accountSelector = accountSelector,
            accountStore = accountStore,
            authorization = AuthorizationClientDriveSession(
                accountSelector = accountSelector,
                authorizationClient = authorizationBridge,
                accountStore = accountStore,
                requestedScopes = setOf(DRIVE_FILE_SCOPE),
            ),
            authorizationBridge = authorizationBridge,
            drive = TeamDriveRestClient(),
        )
    }

    companion object {
        private const val TEAM_ACCOUNT_PREFERENCES = "kron_team_google_account"
    }
}
