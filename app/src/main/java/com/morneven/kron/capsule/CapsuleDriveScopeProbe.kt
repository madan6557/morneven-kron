package com.morneven.kron.capsule

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.IntentSenderRequest
import com.morneven.kron.BuildConfig
import com.morneven.kron.sync.AuthorizationClientResult
import com.morneven.kron.sync.DRIVE_FILE_SCOPE
import com.morneven.kron.sync.DriveAccessTokenResult
import com.morneven.kron.sync.DriveConnectResult
import com.morneven.kron.sync.DrivePickerCompletionResult
import com.morneven.kron.sync.DrivePickerStartResult
import com.morneven.kron.sync.DriveSyncRuntime
import com.morneven.kron.sync.GoogleAccountIdentity
import com.morneven.kron.sync.PlayServicesAuthorizationClientBridge
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

class CapsuleDriveScopeProbe internal constructor(
    private val authorizationBridge: PlayServicesAuthorizationClientBridge,
    private val driveSyncRuntime: DriveSyncRuntime?,
) {
    suspend fun ensureAuthorized(interactive: Boolean = true): DriveAccessTokenResult {
        val account = driveSyncRuntime?.currentAccount()
            ?: return DriveAccessTokenResult.Disconnected
        val result = authorizationBridge.authorize(account, setOf(DRIVE_FILE_SCOPE), interactive)
        return when (result) {
            is AuthorizationClientResult.Granted -> DriveAccessTokenResult.Granted(account, result.accessToken)
            is AuthorizationClientResult.UserActionRequired -> DriveAccessTokenResult.UserActionRequired(result.resolutionId)
            is AuthorizationClientResult.Failed -> DriveAccessTokenResult.Failed(result.message, result.retryable)
        }
    }

    suspend fun getAccount(): GoogleAccountIdentity? = driveSyncRuntime?.currentAccount()

    fun authorizationRequest(resolutionId: String): IntentSenderRequest =
        authorizationBridge.intentSenderRequest(resolutionId)

    suspend fun completeAuthorization(
        account: GoogleAccountIdentity?,
        resolutionId: String,
        resultCode: Int,
        data: Intent?,
    ): DriveConnectResult = authorizationBridge.completeResolution(resolutionId, resultCode, data)
        .let { result ->
            when (result) {
                is AuthorizationClientResult.Granted -> DriveConnectResult.Connected(
                    account ?: GoogleAccountIdentity("", ""),
                )
                is AuthorizationClientResult.UserActionRequired -> DriveConnectResult.UserActionRequired(account, result.resolutionId)
                is AuthorizationClientResult.Failed -> DriveConnectResult.Failed(result.message, result.retryable)
            }
        }

    suspend fun cancelAuthorization(resolutionId: String) {
        authorizationBridge.discardResolution(resolutionId)
    }

    suspend fun openFilePicker(fileId: String): DrivePickerStartResult {
        val account = driveSyncRuntime?.currentAccount()
        return if (account != null) {
            authorizationBridge.openDriveFilePicker(account, fileId)
        } else {
            DrivePickerStartResult.Failed("Hubungkan akun Google terlebih dahulu")
        }
    }

    fun completeFilePicker(resolutionId: String, resultCode: Int, data: Intent?): DrivePickerCompletionResult =
        authorizationBridge.completeDriveFilePicker(resolutionId, resultCode, data)
}

@Singleton
class CapsuleDriveScopeProbeFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val authorizationBridge = PlayServicesAuthorizationClientBridge(context)

    fun create(activity: Activity, driveSyncRuntime: DriveSyncRuntime?): CapsuleDriveScopeProbe? {
        if (!BuildConfig.DRIVE_SYNC_CONFIGURED) return null
        return CapsuleDriveScopeProbe(
            authorizationBridge = authorizationBridge,
            driveSyncRuntime = driveSyncRuntime,
        )
    }
}
