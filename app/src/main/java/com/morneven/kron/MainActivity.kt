package com.morneven.kron

import android.os.Bundle
import android.os.Process
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.morneven.kron.data.KronDatabase
import com.morneven.kron.security.DatabaseEncryptionManager
import com.morneven.kron.security.DatabaseKeyManager
import com.morneven.kron.security.DatabaseKeyProfileMismatchException
import com.morneven.kron.security.DatabaseKeyUnavailableException
import com.morneven.kron.security.DatabaseRecoveryRequiredException
import com.morneven.kron.ui.KronApp
import com.morneven.kron.ui.MainViewModel
import com.morneven.kron.sync.DriveSyncRuntime
import com.morneven.kron.sync.DriveSyncRuntimeFactory
import com.morneven.kron.ui.theme.KronTheme
import dagger.Lazy
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var driveSyncRuntimeFactory: Lazy<DriveSyncRuntimeFactory>

    private var driveSyncRuntime: DriveSyncRuntime? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val databaseError = runCatching { KronDatabase.getInstance(this) }.exceptionOrNull()
        if (databaseError != null) {
            android.util.Log.e("KRON_DB", "Gagal membuka database: ${databaseError.javaClass.simpleName}")
            val databaseFile = getDatabasePath(KronDatabase.DATABASE_NAME)
            val encryption = DatabaseEncryptionManager(this, DatabaseKeyManager(this))
            val canRestorePreEncryption = encryption.hasRecoverablePreEncryptionCopy(databaseFile)
            val inspection = encryption.inspectPrimaryDatabase(databaseFile)
            setContent {
                DatabaseRecoveryScreen(
                    error = databaseError,
                    inspection = inspection,
                    onRestart = ::restartApplication,
                    onRestorePreEncryption = if (canRestorePreEncryption) {
                        {
                            runCatching { encryption.restorePreEncryptionCopy(databaseFile) }.exceptionOrNull()
                        }
                    } else {
                        null
                    },
                )
            }
            return
        }
        driveSyncRuntime = if (BuildConfig.DRIVE_SYNC_CONFIGURED) {
            runCatching { driveSyncRuntimeFactory.get().create(this) }.getOrNull()
        } else {
            null
        }
        setContent {
            KronApp(
                viewModel = viewModel,
                activity = this,
                driveSyncRuntime = driveSyncRuntime,
            )
        }
    }

    private fun restartApplication() {
        finishAffinity()
        Process.killProcess(Process.myPid())
    }
}

@Composable
private fun DatabaseRecoveryScreen(
    error: Throwable? = null,
    inspection: DatabaseEncryptionManager.DatabaseInspection? = null,
    onRestart: () -> Unit,
    onRestorePreEncryption: (() -> Throwable?)? = null,
) {
    var recoveryError by remember { mutableStateOf<String?>(null) }
    val errorChain = generateSequence(error) { it.cause }.toList()
    val isDeviceKeyMissing = errorChain.any { it is DatabaseKeyUnavailableException }
    val isProfileMismatch = errorChain.any { it is DatabaseKeyProfileMismatchException }
    val isRecoveryRequired = errorChain.any { it is DatabaseRecoveryRequiredException }
    KronTheme("DARK") {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Outlined.Security,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
            Text(
                "Pembaruan data belum dapat diterapkan",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )
            Text(
                when {
                    isDeviceKeyMissing ->
                        "Database terenkripsi ditemukan, tetapi kunci perangkat aslinya tidak tersedia. Jangan hapus data atau instal ulang KRON. Gunakan cadangan yang dibuat sebelum pergantian perangkat atau pemulihan data."
                    isProfileMismatch ->
                        "Database dan profil kunci tidak cocok. Semua penulisan diblokir dan data lama tetap dipertahankan untuk pemulihan."
                    isRecoveryRequired ->
                        "Database utama tidak ditemukan, tetapi salinan pemulihan masih tersedia. KRON tidak akan membuat database atau kunci baru sebelum data lama diselesaikan."
                    else ->
                        "Database lama tetap dipertahankan. Jangan hapus data atau instal ulang KRON. Mulai ulang aplikasi, lalu gunakan backup pra-upgrade jika masalah berlanjut."
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
                        append(" · raw key ")
                        append(if (report.acceptsRawKey) "cocok" else "tidak cocok")
                        append(" · profil kunci ")
                        append(
                            when {
                                report.keyProfileValid -> "valid"
                                report.keyProfilePresent -> "ada/tidak valid"
                                else -> "belum dibuat"
                            },
                        )
                        append(" · passphrase ")
                        append(if (report.acceptsPassphrase) "cocok" else "tidak cocok")
                        if (report.keyInitializationPending) append(" · inisialisasi baru tertunda")
                        if (report.hasPreEncryptionCopy) append(" · salinan pra-enkripsi tersedia")
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
            onRestorePreEncryption?.let { restore ->
                Button(
                    onClick = {
                        recoveryError = restore()?.message
                        if (recoveryError == null) onRestart()
                    },
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Text("Pulihkan salinan pra-enkripsi")
                }
            }
            Button(onClick = onRestart) { Text("Mulai ulang KRON") }
        }
    }
}
