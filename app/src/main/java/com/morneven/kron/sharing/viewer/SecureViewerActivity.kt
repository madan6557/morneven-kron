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
import com.morneven.kron.sharing.onetime.DeviceBindingKeyManager
import com.morneven.kron.sharing.onetime.ViewCapsuleCodec
import com.morneven.kron.ui.components.formatIdr
import org.json.JSONArray
import org.json.JSONObject

class SecureViewerActivity : FragmentActivity() {
    private var capsuleId: String = ""
    private var keyManager: DeviceBindingKeyManager? = null
    private var plaintext: ByteArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)

        capsuleId = intent?.getStringExtra(EXTRA_CAPSULE_ID) ?: ""
        val encodedCapsule = intent?.getStringExtra(EXTRA_ENCODED_CAPSULE) ?: ""

        if (capsuleId.isEmpty() || encodedCapsule.isEmpty()) {
            Toast.makeText(this, "Kapsul tidak valid", Toast.LENGTH_SHORT).show()
            finish(); return
        }

        keyManager = DeviceBindingKeyManager(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("Sekali Buka - $capsuleId", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("Memverifikasi...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (capsuleId.isNotEmpty()) authenticateAndDecrypt()
    }

    override fun onStop() { super.onStop(); zeroize() }
    override fun onDestroy() { super.onDestroy(); zeroize() }

    private fun authenticateAndDecrypt() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) { decryptIfReady(); return }
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = decryptIfReady()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                Toast.makeText(this@SecureViewerActivity, errString, Toast.LENGTH_LONG).show(); finish()
            }
            override fun onAuthenticationFailed() = Toast.makeText(this@SecureViewerActivity, "Gagal", Toast.LENGTH_SHORT).show()
        })
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle("Buka Sekali").setSubtitle("Verifikasi untuk membuka kapsul")
            .setNegativeButtonText("Batal").build())
    }

    private fun decryptIfReady() {
        val encoded = intent?.getStringExtra(EXTRA_ENCODED_CAPSULE) ?: return
        val capsule = ViewCapsuleCodec.decodeFromString(encoded) ?: run {
            Toast.makeText(this, "Kapsul tidak valid", Toast.LENGTH_SHORT).show(); finish(); return
        }
        val result = keyManager?.decryptWithContentKey(capsuleId, capsule.encryptedProjection, capsule.nonce)
        if (result != null) {
            plaintext = result
            val projection = ViewCapsuleCodec.deserializeProjection(result)
            runOnUiThread { showProjection(projection) }
        } else {
            Toast.makeText(this, "Gagal membuka kapsul", Toast.LENGTH_SHORT).show(); finish()
        }
    }

    private fun showProjection(projection: com.morneven.kron.sharing.onetime.ViewProjection) {
        val summary = runCatching { JSONObject(projection.summaryJson) }.getOrNull()
        val transactions = runCatching { JSONArray(projection.transactionsJson) }.getOrNull()
        val budgets = runCatching { JSONArray(projection.budgetsJson) }.getOrNull()

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
                        Text("Sekali Buka", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(capsuleId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))

                        if (summary != null) {
                            SectionHeader("Ringkasan")
                            SummaryRow("Pemasukan", formatIdr(summary.optLong("totalIncome")), MaterialTheme.colorScheme.primary)
                            SummaryRow("Pengeluaran", formatIdr(summary.optLong("totalExpense")), MaterialTheme.colorScheme.error)
                            SummaryRow("Saldo", formatIdr(summary.optLong("balance")), MaterialTheme.colorScheme.tertiary)
                        }

                        if (transactions != null && transactions.length() > 0) {
                            Spacer(Modifier.height(12.dp))
                            SectionHeader("Transaksi (${transactions.length()})")
                            for (i in 0 until transactions.length()) {
                                val t = transactions.getJSONObject(i)
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(t.optString("date", ""), fontSize = 10.sp, modifier = Modifier.width(72.dp))
                                    Text(t.optString("description", ""), fontSize = 10.sp, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                                    Text(formatIdr(t.optLong("amount")), fontSize = 10.sp,
                                        color = if (t.optString("type") == "INCOME") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                                }
                            }
                        }

                        if (budgets != null && budgets.length() > 0) {
                            Spacer(Modifier.height(12.dp))
                            SectionHeader("Anggaran (${budgets.length()})")
                            for (i in 0 until budgets.length()) {
                                val bg = budgets.getJSONObject(i)
                                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                    Text(bg.optString("category", ""), fontSize = 10.sp, modifier = Modifier.weight(1f))
                                    Text(
                                        "${formatIdr(bg.optLong("spent"))} / ${formatIdr(bg.optLong("budget"))}",
                                        fontSize = 10.sp)
                                }
                            }
                        }

                        Spacer(Modifier.height(16.dp))
                        Text("Kapsul hanya dapat dibuka sekali.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }

    private fun zeroize() {
        plaintext?.fill(0); plaintext = null
        if (capsuleId.isNotEmpty()) keyManager?.deleteContentKey(capsuleId)
    }

    @Composable
    private fun SectionHeader(title: String) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    }

    @Composable
    private fun SummaryRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
        Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
            Text(label, modifier = Modifier.weight(1f))
            Text(value, color = color, fontWeight = FontWeight.SemiBold)
        }
    }

    companion object {
        private const val EXTRA_CAPSULE_ID = "capsule_id"
        private const val EXTRA_ENCODED_CAPSULE = "encoded_capsule"
        private const val EXTRA_MANIFEST_JSON = "manifest_json"

        fun createIntent(context: Context, capsuleId: String, encodedCapsule: String, manifestJson: String = ""): Intent =
            Intent(context, SecureViewerActivity::class.java).apply {
                putExtra(EXTRA_CAPSULE_ID, capsuleId)
                putExtra(EXTRA_ENCODED_CAPSULE, encodedCapsule)
                putExtra(EXTRA_MANIFEST_JSON, manifestJson)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
    }
}
