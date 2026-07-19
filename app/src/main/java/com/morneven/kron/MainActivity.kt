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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import com.morneven.kron.data.KronDatabase
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
            android.util.Log.e("KRON_DB", "Gagal membuka database", databaseError)
            setContent { DatabaseRecoveryScreen(databaseError, ::restartApplication) }
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
private fun DatabaseRecoveryScreen(error: Throwable? = null, onRestart: () -> Unit) {
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
                "Database lama tetap dipertahankan. Jangan hapus data atau instal ulang KRON. Mulai ulang aplikasi, lalu gunakan backup pra-upgrade jika masalah berlanjut.",
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
            Button(onClick = onRestart) { Text("Mulai ulang KRON") }
        }
    }
}
