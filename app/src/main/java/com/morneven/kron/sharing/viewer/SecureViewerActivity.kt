package com.morneven.kron.sharing.viewer

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.activity.compose.setContent
import androidx.fragment.app.FragmentActivity
import com.morneven.kron.capsule.CapsuleCodec
import com.morneven.kron.capsule.CapsuleSnapshot
import com.morneven.kron.ui.components.formatIdr

class SecureViewerActivity : FragmentActivity() {
    private var capsuleId: String = ""
    private var snapshot: CapsuleSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        capsuleId = intent?.getStringExtra(EXTRA_CAPSULE_ID) ?: ""
        if (capsuleId.isEmpty()) {
            Toast.makeText(this, "Kapsul tidak valid", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("Kapsul - $capsuleId", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Memverifikasi...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (capsuleId.isNotEmpty()) authenticateAndLoad()
    }

    override fun onStop() { super.onStop(); zeroize() }
    override fun onDestroy() { super.onDestroy(); zeroize() }

    private fun authenticateAndLoad() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { loadSnapshot(); return }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = loadSnapshot()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(this@SecureViewerActivity, errString, Toast.LENGTH_LONG).show(); finish()
            }
            override fun onAuthenticationFailed() = Toast.makeText(this@SecureViewerActivity, "Gagal", Toast.LENGTH_SHORT).show()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Buka Kapsul").setSubtitle("Verifikasi untuk membuka kapsul")
            .setNegativeButtonText("Batal").build())
    }

    private fun loadSnapshot() {
        val path = intent?.getStringExtra(EXTRA_SNAPSHOT_FILE) ?: run {
            Toast.makeText(this, "Data kapsul tidak tersedia", Toast.LENGTH_SHORT).show(); finish(); return
        }
        val file = java.io.File(path)
        val bytes = runCatching { file.readBytes() }.getOrNull()
        if (bytes == null || !file.delete()) {
            Toast.makeText(this, "Gagal membaca kapsul", Toast.LENGTH_SHORT).show(); finish(); return
        }
        snapshot = runCatching {
            CapsuleCodec.deserializeSnapshot(bytes)
        }.getOrNull()
        if (snapshot == null) {
            Toast.makeText(this, "Gagal membaca kapsul", Toast.LENGTH_SHORT).show(); finish(); return
        }
        runOnUiThread { showSnapshot(snapshot!!) }
    }

    private fun showSnapshot(snapshot: CapsuleSnapshot) {
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("Kapsul", style = MaterialTheme.typography.titleMedium)
                        Text(snapshot.accountName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text("Mode: ${snapshot.sharingMode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))

                        SectionHeader("Ringkasan")
                        SummaryRow("Kas", formatIdr(snapshot.cashBalance))
                        SummaryRow("Vault", formatIdr(snapshot.vaultBalance))
                        SummaryRow("Belum dialokasi", formatIdr(snapshot.unallocatedBalance))

                        if (snapshot.transactions.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionHeader("Transaksi (${snapshot.transactions.size})")
                            snapshot.transactions.take(50).forEach { t ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(java.time.LocalDate.ofEpochDay(t.effectiveEpochDay).toString(), fontSize = 10.sp, modifier = Modifier.width(72.dp))
                                    Text(t.title, fontSize = 10.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1)
                                    Text(formatIdr(t.amount), fontSize = 10.sp, color = if (t.direction == "INCOME") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (snapshot.allocations.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionHeader("Anggaran (${snapshot.allocations.size})")
                            snapshot.allocations.take(20).forEach { a ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(a.categoryName, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Text("${formatIdr(a.spentAmount)} / ${formatIdr(a.plannedAmount)}", fontSize = 10.sp)
                                }
                            }
                        }

                        if (snapshot.portfolios.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionHeader("Portfolio (${snapshot.portfolios.size})")
                            snapshot.portfolios.forEach { p ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                    Text(p.name, fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Text("${p.cadence} | ${formatIdr(p.plannedIncome)}", fontSize = 10.sp)
                                }
                            }
                        }

                        if (snapshot.auditEntries.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            SectionHeader("Audit (${snapshot.auditEntries.size})")
                            snapshot.auditEntries.take(20).forEach { a ->
                                Text("${a.type} | ${a.actor ?: "-"} | ${a.sealedAt}", fontSize = 9.sp, modifier = Modifier.padding(vertical = 1.dp))
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Kapsul ini adalah snapshot finansial read-only. Tidak menerima pembaruan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun zeroize() {
        snapshot = null
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }

    @Composable
    private fun SummaryRow(label: String, value: String) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }

    companion object {
        private const val EXTRA_CAPSULE_ID = "capsule_id"
        private const val EXTRA_SNAPSHOT_FILE = "snapshot_file"

        fun createIntent(context: Context, capsuleId: String, snapshotFile: String): Intent =
            Intent(context, SecureViewerActivity::class.java).apply {
                putExtra(EXTRA_CAPSULE_ID, capsuleId)
                putExtra(EXTRA_SNAPSHOT_FILE, snapshotFile)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
