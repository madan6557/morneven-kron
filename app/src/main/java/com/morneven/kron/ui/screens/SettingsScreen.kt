package com.morneven.kron.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.morneven.kron.BuildConfig
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.data.AccountEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun SettingsScreen(
    state: KronUiState,
    onAddAccount: () -> Unit,
    onEditAccount: (AccountEntity) -> Unit,
    onArchiveAccount: (AccountEntity) -> Unit,
    onRestoreAccount: (AccountEntity) -> Unit,
    onActivateAccount: (AccountEntity) -> Unit,
    onViewAudit: () -> Unit,
    onRememberVisibility: (Boolean) -> Unit,
    onAppLock: (Boolean) -> Unit,
    onTheme: (String) -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showArchive by remember { mutableStateOf(false) }
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("PENGATURAN", style = MaterialTheme.typography.headlineMedium)
            Text("Privasi, akun, tampilan, dan data", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            SectionHeader("Akun")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 10.dp)) {
                FilterChip(selected = !showArchive, onClick = { showArchive = false }, label = { Text("Aktif") })
                FilterChip(selected = showArchive, onClick = { showArchive = true }, label = { Text("Arsip (${state.archivedAccounts.size})") })
            }
            HudCard {
                if (!showArchive) {
                    state.accountBalances.forEach { account ->
                        val entity = state.accounts.firstOrNull { it.id == account.id }
                        Column(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.name, style = MaterialTheme.typography.titleMedium)
                                    Text(if (account.isActive) "AKUN AKTIF" else "Tidak aktif", style = MaterialTheme.typography.labelSmall, color = if (account.isActive) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                androidx.compose.material3.IconButton(onClick = { entity?.let(onEditAccount) }) {
                                    Icon(Icons.Outlined.Edit, contentDescription = "Edit akun")
                                }
                                androidx.compose.material3.IconButton(onClick = { entity?.let(onArchiveAccount) }, enabled = !account.isActive && account.cashBalance == 0L && account.eBudgetBalance == 0L) {
                                    Icon(Icons.Outlined.Archive, contentDescription = "Arsipkan akun")
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Column(Modifier.weight(1f)) {
                                    ChannelBadge("CASH")
                                    Text(displayMoney(account.cashBalance, state.valuesVisible), style = MaterialTheme.typography.labelLarge)
                                }
                                Column(Modifier.weight(1f)) {
                                    ChannelBadge("EBUDGET")
                                    Text(displayMoney(account.eBudgetBalance, state.valuesVisible), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                            if (!account.isActive && entity != null) {
                                TextButton(onClick = { onActivateAccount(entity) }) { Text("Jadikan akun aktif") }
                            }
                        }
                    }
                    SettingRow(Icons.Outlined.Add, "Tambah akun", "Setiap akun memiliki Cash dan eBudget", onClick = onAddAccount)
                } else if (state.archivedAccounts.isEmpty()) {
                    Text("Belum ada akun di arsip.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
                } else {
                    state.archivedAccounts.forEach { account ->
                        val archivedDate = account.archivedAt?.let {
                            Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(archiveDateFormat)
                        } ?: "Tidak diketahui"
                        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(account.name, style = MaterialTheme.typography.titleMedium)
                            Text("Diarsipkan $archivedDate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChannelBadge("CASH")
                                ChannelBadge("EBUDGET")
                            }
                            Text("Saldo kedua kanal telah diselesaikan sebelum arsip.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = onViewAudit) { Text("Lihat audit") }
                                TextButton(onClick = { onRestoreAccount(account) }) {
                                    Icon(Icons.Outlined.Restore, contentDescription = null)
                                    Text("Pulihkan akun")
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionHeader("Privasi")
            HudCard {
                ToggleRow(Icons.Outlined.Lock, "Kunci aplikasi", "Biometrik atau kredensial perangkat", state.appLockEnabled, onAppLock)
                ToggleRow(Icons.Outlined.AccountBalance, "Ingat visibilitas", "App lock tetap menyembunyikan nilai", state.rememberVisibility, onRememberVisibility)
            }
        }
        item {
            SectionHeader("Tema")
            HudCard {
                listOf("DARK" to "Gelap", "LIGHT" to "Terang", "SYSTEM" to "Ikuti sistem").forEach { (key, label) ->
                    SettingRow(Icons.Outlined.DarkMode, label, if (state.theme == key) "Aktif" else "", onClick = { onTheme(key) })
                }
            }
        }
        item {
            SectionHeader("Backup")
            HudCard {
                SettingRow(Icons.Outlined.Backup, "Buat .kronbackup", "Terenkripsi AES-256-GCM", onClick = onBackup)
                SettingRow(Icons.Outlined.Restore, "Pulihkan backup", "Validasi password dan jurnal", onClick = onRestore)
            }
        }
        item {
            Spacer(Modifier.height(28.dp))
            Text("KRON ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Local-first. Tidak memerlukan akun atau internet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(80.dp))
        }
    }
}

private val archiveDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
