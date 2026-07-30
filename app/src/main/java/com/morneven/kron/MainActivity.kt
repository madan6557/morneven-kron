package com.morneven.kron

import android.os.Bundle
import android.os.Process
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.morneven.kron.backup.PreUpgradeBackupManager
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.security.DatabaseAccessGate
import com.morneven.kron.security.DatabaseBootstrapManager
import com.morneven.kron.security.DatabaseBootstrapState
import com.morneven.kron.security.DatabaseEncryptionManager
import com.morneven.kron.security.DatabaseKeyManager
import com.morneven.kron.security.DatabaseKeyProfileMismatchException
import com.morneven.kron.security.DatabaseKeyUnavailableException
import com.morneven.kron.security.DatabaseRecoveryRequiredException
import com.morneven.kron.security.DatabaseRuntime
import com.morneven.kron.sync.DriveSyncRuntime
import com.morneven.kron.sync.DriveSyncRuntimeFactory
import com.morneven.kron.sync.DriveSyncScheduler
import com.morneven.kron.automation.AutomationWorker
import com.morneven.kron.team.TeamDriveScopeProbe
import com.morneven.kron.team.TeamDriveScopeProbeFactory
import com.morneven.kron.team.TeamSyncRuntime
import com.morneven.kron.team.TeamSyncScheduler
import com.morneven.kron.ui.KronApp
import com.morneven.kron.ui.MainViewModel
import com.morneven.kron.ui.theme.KronTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var driveSyncRuntimeFactory: Lazy<DriveSyncRuntimeFactory>
    @Inject lateinit var teamDriveScopeProbeFactory: Lazy<TeamDriveScopeProbeFactory>
    @Inject lateinit var teamSyncRuntime: Lazy<TeamSyncRuntime>
    @Inject lateinit var databaseRuntime: DatabaseRuntime

    private var driveSyncRuntime: DriveSyncRuntime? = null
    private var teamDriveScopeProbe: TeamDriveScopeProbe? = null
    private lateinit var bootstrapManager: DatabaseBootstrapManager
    private lateinit var preUpgradeBackupManager: PreUpgradeBackupManager
    private var pendingBackupPassword: CharArray? = null
    private var backupUiState by mutableStateOf<BackupUiState>(BackupUiState.Form)
    private var databaseOpening = false
    private var freshInstall = false
    private var pendingSyncActivation = false

    private val createPreUpgradeBackup = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val password = pendingBackupPassword
        pendingBackupPassword = null
        if (uri == null || password == null) {
            password?.fill('\u0000')
            backupUiState = BackupUiState.Error(
                "Pembuatan backup dibatalkan. Backup wajib dibuat sebelum upgrade.",
            )
            return@registerForActivityResult
        }
        backupUiState = BackupUiState.Exporting
        lifecycleScope.launch(Dispatchers.IO) {
            val error = runCatching {
                preUpgradeBackupManager.export(uri, password)
                bootstrapManager.markExternalBackupVerified()
            }.exceptionOrNull()
            withContext(Dispatchers.Main) {
                if (error == null) {
                    openDatabaseAndStart()
                } else {
                    backupUiState = BackupUiState.Error(error.message ?: "Backup pra-upgrade gagal dibuat.")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            databaseRuntime.pendingSyncActivation.collect { pending ->
                if (pending) applyPendingSyncActivationIfSafe()
            }
        }
        enableEdgeToEdge()
        bootstrapManager = DatabaseBootstrapManager(this)
        preUpgradeBackupManager = PreUpgradeBackupManager(this)
        val bootstrap = bootstrapManager.inspect()
        freshInstall = !bootstrap.inspection.exists && !bootstrap.inspection.hasRecoveryArtifacts
        when (bootstrap.state) {
            DatabaseBootstrapState.BACKUP_REQUIRED -> setContent {
                PreUpgradeBackupScreen(
                    state = backupUiState,
                    onCreateBackup = ::requestPreUpgradeBackup,
                )
            }
            DatabaseBootstrapState.RECOVERY_REQUIRED -> showRecoveryScreen(
                DatabaseRecoveryRequiredException(
                    bootstrap.message ?: "Database memerlukan pemulihan.",
                ),
                bootstrap.inspection,
            )
            else -> openDatabaseAndStart()
        }
    }

    override fun onResume() {
        super.onResume()
        if (DatabaseAccessGate.isReady()) viewModel.refreshForCurrentDate()
        applyPendingSyncActivationIfSafe()
    }

    private fun applyPendingSyncActivationIfSafe() {
        if (
            !DatabaseAccessGate.isReady() ||
            pendingSyncActivation ||
            !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
            !databaseRuntime.hasPendingSyncActivation()
        ) return
        pendingSyncActivation = true
        lifecycleScope.launch {
            val result = applyStagedSnapshot()
            if (result.isSuccess) {
                recreate()
            } else {
                viewModel.showMessage(
                    result.exceptionOrNull()?.message
                        ?: "Pembaruan tersinkron tidak dapat diterapkan.",
                )
            }
            pendingSyncActivation = false
        }
    }

    private fun requestPreUpgradeBackup(password: String, confirmation: String) {
        if (password.length < 12) {
            backupUiState = BackupUiState.Error("Recovery passphrase minimal 12 karakter.")
            return
        }
        if (password != confirmation) {
            backupUiState = BackupUiState.Error("Konfirmasi recovery passphrase tidak cocok.")
            return
        }
        pendingBackupPassword?.fill('\u0000')
        pendingBackupPassword = password.toCharArray()
        backupUiState = BackupUiState.WaitingForLocation
        createPreUpgradeBackup.launch("KRON-pre-upgrade-1.5.0.kronbackup")
    }

    private fun openDatabaseAndStart() {
        if (databaseOpening) return
        databaseOpening = true
        setContent { DatabaseOpeningScreen() }
        lifecycleScope.launch(Dispatchers.IO) {
            val databaseError = runCatching { KronDatabase.getInstance(this@MainActivity) }.exceptionOrNull()
            val inspection = if (databaseError != null) {
                DatabaseEncryptionManager(this@MainActivity, DatabaseKeyManager(this@MainActivity))
                    .inspectPrimaryDatabase(getDatabasePath(KronDatabase.DATABASE_NAME))
            } else {
                null
            }
            withContext(Dispatchers.Main) {
                databaseOpening = false
                if (databaseError != null) {
                    showRecoveryScreen(databaseError, requireNotNull(inspection))
                    return@withContext
                }
                DatabaseAccessGate.markReady()
                if (freshInstall) bootstrapManager.markFreshInstallValidated()
                bootstrapManager.recordSuccessfulColdLaunch()
                (application as KronApplication).startDataServices()
                driveSyncRuntime = if (BuildConfig.DRIVE_SYNC_CONFIGURED) {
                    runCatching { driveSyncRuntimeFactory.get().create(this@MainActivity) }.getOrNull()
                } else {
                    null
                }
                teamDriveScopeProbe = if (BuildConfig.DRIVE_SYNC_CONFIGURED && BuildConfig.TEAM_ACCOUNT_ENABLED) {
                    runCatching { teamDriveScopeProbeFactory.get().create(this@MainActivity) }.getOrNull()
                } else {
                    null
                }
                setContent {
                    KronApp(
                        viewModel = viewModel,
                        activity = this@MainActivity,
                        driveSyncRuntime = driveSyncRuntime,
                        teamDriveScopeProbe = teamDriveScopeProbe,
                        onApplyStagedSnapshot = ::applyStagedSnapshot,
                    )
                }
            }
        }
    }

    private suspend fun applyStagedSnapshot(): Result<Unit> {
        DriveSyncScheduler.cancel(this)
        TeamSyncScheduler.cancelScheduledWork(this)
        androidx.work.WorkManager.getInstance(this).cancelUniqueWork(AutomationWorker.UNIQUE_WORK_NAME)
        val result = databaseRuntime.activatePendingSnapshot()
        if (result.isSuccess) {
            driveSyncRuntimeFactory.get().refreshAfterDatabaseActivation()
            teamSyncRuntime.get().refreshAfterDatabaseActivation()
            (application as KronApplication).startDataServices()
        }
        return result
    }

    private fun showRecoveryScreen(
        databaseError: Throwable,
        inspection: DatabaseEncryptionManager.DatabaseInspection,
    ) {
        android.util.Log.e("KRON_DB", "Gagal membuka database: ${databaseError.javaClass.simpleName}")
        val databaseFile = getDatabasePath(KronDatabase.DATABASE_NAME)
        val encryption = DatabaseEncryptionManager(this, DatabaseKeyManager(this))
        val canRestorePreUpgrade = encryption.hasRecoverablePreEncryptionCopy(databaseFile)
        setContent {
            DatabaseRecoveryScreen(
                error = databaseError,
                inspection = inspection,
                onRestart = ::restartApplication,
                onRestorePreUpgrade = if (canRestorePreUpgrade) {
                    {
                        runCatching { encryption.restorePreEncryptionCopy(databaseFile) }.exceptionOrNull()
                    }
                } else {
                    null
                },
            )
        }
    }

    private fun restartApplication() {
        finishAffinity()
        Process.killProcess(Process.myPid())
    }
}

private sealed interface BackupUiState {
    data object Form : BackupUiState
    data object WaitingForLocation : BackupUiState
    data object Exporting : BackupUiState
    data class Error(val message: String) : BackupUiState
}

@Composable
private fun PreUpgradeBackupScreen(
    state: BackupUiState,
    onCreateBackup: (String, String) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    KronTheme("DARK") {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 28.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Outlined.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    "Amankan data sebelum upgrade",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 18.dp),
                )
                Text(
                    "KRON mendeteksi database dari versi stabil sebelumnya. Buat backup pemulihan eksternal sebelum database diperbarui.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp, bottom = 20.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Recovery passphrase") },
                    supportingText = { Text("Minimal 12 karakter. Simpan di tempat aman.") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it },
                    label = { Text("Konfirmasi passphrase") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.padding(top = 12.dp),
                )
                if (state is BackupUiState.Error) {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
                if (state == BackupUiState.Exporting) {
                    CircularProgressIndicator(modifier = Modifier.padding(top = 20.dp))
                    Text("Membuat dan memverifikasi backup", modifier = Modifier.padding(top = 10.dp))
                } else {
                    Button(
                        onClick = { onCreateBackup(password, confirmation) },
                        enabled = state != BackupUiState.WaitingForLocation && password.length >= 12,
                        modifier = Modifier.padding(top = 20.dp),
                    ) {
                        Text("Pilih lokasi backup")
                    }
                }
            }
        }
    }
}

@Composable
private fun DatabaseOpeningScreen() {
    KronTheme("DARK") {
        Column(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator()
            Text("Memeriksa integritas data", modifier = Modifier.padding(top = 16.dp))
        }
    }
}

@Composable
private fun DatabaseRecoveryScreen(
    error: Throwable? = null,
    inspection: DatabaseEncryptionManager.DatabaseInspection? = null,
    onRestart: () -> Unit,
    onRestorePreUpgrade: (() -> Throwable?)? = null,
) {
    var recoveryError by remember { mutableStateOf<String?>(null) }
    val errorChain = generateSequence(error) { it.cause }.toList()
    val isDeviceKeyMissing = errorChain.any { it is DatabaseKeyUnavailableException }
    val isProfileMismatch = errorChain.any { it is DatabaseKeyProfileMismatchException }
    val isRecoveryRequired = errorChain.any { it is DatabaseRecoveryRequiredException }
    KronTheme("DARK") {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Text(
                "Pembaruan data belum dapat diterapkan",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                when {
                    isDeviceKeyMissing ->
                        "Database terenkripsi ditemukan, tetapi kunci perangkat aslinya tidak tersedia. Data tetap dipertahankan dan seluruh penulisan diblokir."
                    isProfileMismatch ->
                        "Metadata kunci tidak cocok. KRON tetap mencoba seluruh format kunci historis sebelum meminta pemulihan."
                    isRecoveryRequired ->
                        "Database atau salinan pemulihan memerlukan pemeriksaan. KRON tidak membuat database maupun kunci pengganti."
                    else ->
                        "Database lama tetap dipertahankan. Jangan hapus data atau instal ulang KRON."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            )
            error?.let {
                Text(
                    "${it.javaClass.simpleName}: ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 24.dp),
                )
            }
            inspection?.let { report ->
                Text(
                    buildString {
                        append("Pemeriksaan aman: ")
                        append(if (report.isPlaintext) "plaintext" else "terenkripsi/tidak terbaca")
                        append(" · empty key ")
                        append(if (report.acceptsEmptyKey) "cocok" else "tidak cocok")
                        append(" · envelope ")
                        append(
                            when {
                                report.keyEnvelopeReadable -> "terbaca"
                                report.keyEnvelopePresent -> "ada/tidak terbaca"
                                else -> "tidak ada"
                            },
                        )
                        append(" · passphrase ")
                        append(if (report.acceptsPassphrase) "cocok" else "tidak cocok")
                        append(" · raw key ")
                        append(if (report.acceptsRawKey) "cocok" else "tidak cocok")
                        append(" · profil ")
                        append(if (report.keyProfileValid) "valid" else if (report.keyProfilePresent) "perlu diperbarui" else "belum dibuat")
                        if (report.hasPreEncryptionCopy) append(" · salinan pra-upgrade tersedia")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            recoveryError?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
            onRestorePreUpgrade?.let { restore ->
                Button(
                    onClick = {
                        recoveryError = restore()?.message
                        if (recoveryError == null) onRestart()
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Text("Pulihkan salinan pra-upgrade")
                }
            }
            Button(onClick = onRestart) { Text("Periksa ulang") }
        }
    }
}
