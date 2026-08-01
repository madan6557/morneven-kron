package com.morneven.kron.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.morneven.kron.BuildConfig
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.data.AccountBalanceRow
import com.morneven.kron.data.AccountSharingMode
import com.morneven.kron.data.RecurringRuleEntity
import com.morneven.kron.data.TeamRole
import com.morneven.kron.ui.KronUiState
import com.morneven.kron.ui.components.ChannelBadge
import com.morneven.kron.ui.components.HudCard
import com.morneven.kron.ui.components.SectionHeader
import com.morneven.kron.ui.components.displayMoney
import com.morneven.kron.ui.theme.KronGreen
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class CloudSyncStatus {
    NOT_CONNECTED,
    SYNCING,
    SYNCED,
    WAITING_NETWORK,
    NEEDS_AUTHORIZATION,
    FAILED,
    CONFLICT,
    RESTART_REQUIRED,
    FREE_ONLY_BLOCKED,
    UNAVAILABLE,
}

/** UI contract for the optional Google Drive integration. It intentionally contains no tokens. */
data class CloudBackupUiState(
    val status: CloudSyncStatus = CloudSyncStatus.UNAVAILABLE,
    val accountLabel: String? = null,
    val lastSyncedAt: Long? = null,
    val wifiOnly: Boolean = false,
    val detail: String? = null,
)

enum class SyncMode { PRIVATE_DRIVE, TEAM }

data class SyncCardUiState(
    val mode: SyncMode,
    val status: CloudSyncStatus,
    val accountLabel: String?,
    val kronAccountName: String?,
    val teamRole: String? = null,
    val lastSyncedAt: Long? = null,
    val wifiOnly: Boolean = false,
    val detail: String? = null,
)

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
    onEvidenceCenter: () -> Unit,
    onPauseRule: ((String) -> Unit)?,
    onResumeRule: ((String, Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    cloudBackupState: CloudBackupUiState = CloudBackupUiState(),
    onConnectCloud: (() -> Unit)? = null,
    onSyncNow: (() -> Unit)? = null,
    onSyncTeam: (() -> Unit)? = null,
    teamSyncing: Boolean = false,
    teamSyncDetail: String? = null,
    teamSyncFailed: Boolean = false,
    teamWaitingNetwork: Boolean = false,
    teamGoogleAccountLabel: String? = null,
    onDisconnectCloud: (() -> Unit)? = null,
    onChangeCloudAccount: (() -> Unit)? = null,
    onWifiOnly: ((Boolean) -> Unit)? = null,
    onClearDriveData: (() -> Unit)? = null,
    onBudgetAlertsChanged: ((Boolean) -> Unit)? = null,
    onNotificationSettings: (() -> Unit)? = null,
    onScreenshotAllowed: ((Boolean) -> Unit)? = null,
    onConvertToTeam: (() -> Unit)? = null,
    onJoinTeam: (() -> Unit)? = null,
    onManageCollaborators: (() -> Unit)? = null,
    onCreateTeamInvite: (() -> Unit)? = null,
    onLeaveTeam: (() -> Unit)? = null,
    onConvertToPrivate: (() -> Unit)? = null,
    onRunTeamScopeProbe: (() -> Unit)? = null,
    onCreateCapsule: (() -> Unit)? = null,
    onOpenCapsule: (() -> Unit)? = null,
    capsuleSentCount: Int = 0,
    capsuleReceivedCount: Int = 0,
    onViewSentCapsules: (() -> Unit)? = null,
    onViewReceivedCapsules: (() -> Unit)? = null,
    driveSyncConnected: Boolean = false,
) {
    var showArchive by rememberSaveable { mutableStateOf(false) }
    var showGlossary by rememberSaveable { mutableStateOf(false) }
    val activeAccounts = state.accountBalances
    val privateAccounts = activeAccounts.filter { account ->
        state.accounts.firstOrNull { it.id == account.id }?.sharingMode != AccountSharingMode.TEAM
    }.sortedByDescending { it.isActive }
    val teamAccounts = activeAccounts.filter { account ->
        state.accounts.firstOrNull { it.id == account.id }?.sharingMode == AccountSharingMode.TEAM
    }.sortedByDescending { it.isActive }
    val activeReadOnly = state.activeAccount?.sharingMode == AccountSharingMode.TEAM &&
        state.teamWorkspace?.localRole == TeamRole.VIEWER
    val uriHandler = LocalUriHandler.current

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("PENGATURAN", style = MaterialTheme.typography.headlineMedium)
            Text("Akun, keamanan, data, dan tampilan", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Akun aktif: ${state.activeAccount?.name ?: "Belum ada"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
        }

        item {
            SectionHeader("Akun")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 12.dp)) {
                item {
                    FilterChip(selected = !showArchive, onClick = { showArchive = false }, label = { Text("Aktif (${activeAccounts.size})") })
                }
                item {
                    FilterChip(selected = showArchive, onClick = { showArchive = true }, label = { Text("Arsip (${state.archivedAccounts.size})") })
                }
            }
        }

        if (!showArchive) {
            listOf("Akun Privat" to privateAccounts, "Akun Team" to teamAccounts)
                .filter { it.second.isNotEmpty() }
                .forEach { (title, accounts) ->
                item {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    AccountCarousel(
                        accounts = accounts,
                        state = state,
                        onEditAccount = onEditAccount,
                        onArchiveAccount = onArchiveAccount,
                        onActivateAccount = onActivateAccount,
                    )
                }
            }
            item {
                HudCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onAddAccount)) {
                    SettingRow(Icons.Outlined.Add, "Tambah akun", "Setiap akun memiliki kanal Cash dan eBudget", onClick = onAddAccount)
                }
            }
        } else if (state.archivedAccounts.isEmpty()) {
            item {
                HudCard {
                    Text("Belum ada akun di arsip", style = MaterialTheme.typography.titleMedium)
                    Text("Akun yang diarsipkan akan tersedia untuk dipulihkan di sini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(state.archivedAccounts, key = { it.id }) { account ->
                val archivedDate = account.archivedAt?.let {
                    Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().format(archiveDateFormat)
                } ?: "Tidak diketahui"
                HudCard {
                    Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("Diarsipkan $archivedDate", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        ChannelBadge("CASH")
                        ChannelBadge("EBUDGET")
                    }
                    Text("Riwayat dan audit tetap tersimpan.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onViewAudit) { Text("Lihat audit") }
                        TextButton(onClick = { onRestoreAccount(account) }) {
                            Icon(Icons.Outlined.Restore, contentDescription = null)
                            Text("Pulihkan")
                        }
                    }
                }
            }
        }

        item {
            SectionHeader("Team Account")
            HudCard {
                val account = state.activeAccount
                val workspace = state.teamWorkspace
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(Icons.Outlined.Group, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (account?.sharingMode == AccountSharingMode.TEAM) "Akun Team" else "Akun Privat",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            if (workspace == null) {
                                "Data akun ini hanya tersedia pada ruang privat KRON."
                            } else {
                                "Role ${workspace.localRole.lowercase().replaceFirstChar(Char::uppercase)}. " +
                                    "${state.teamMembers.size} collaborator tersimpan dalam cache Drive."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (!BuildConfig.TEAM_ACCOUNT_ENABLED) {
                    Text(
                        "Fondasi Team Account terpasang, tetapi aktivasi dikunci sampai uji dua akun Google Drive dan rollback lulus.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                } else if (account?.sharingMode != AccountSharingMode.TEAM) {
                    Button(
                        onClick = { onConvertToTeam?.invoke() },
                        enabled = onConvertToTeam != null && driveSyncConnected,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Ubah menjadi Team") }
                    TextButton(
                        onClick = { onJoinTeam?.invoke() },
                        enabled = onJoinTeam != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Outlined.Key, contentDescription = null)
                        Text("Masukkan kode akses")
                    }
                } else if (workspace?.localRole == TeamRole.OWNER) {
                    Button(
                        onClick = { onManageCollaborators?.invoke() },
                        enabled = onManageCollaborators != null && workspace.canShare,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Kelola collaborator") }
                    TextButton(
                        onClick = { onCreateTeamInvite?.invoke() },
                        enabled = onCreateTeamInvite != null && workspace.canShare,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Buat kode akses") }
                    TextButton(
                        onClick = { onConvertToPrivate?.invoke() },
                        enabled = onConvertToPrivate != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Kembalikan menjadi privat") }
                } else {
                    TextButton(
                        onClick = { onLeaveTeam?.invoke() },
                        enabled = onLeaveTeam != null,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) { Text("Tinggalkan Team") }
                }
            }
        }

        item { SectionHeader("Kapsul") }
        item {
            HudCard {
                Button(
                    onClick = { onCreateCapsule?.invoke() },
                    enabled = onCreateCapsule != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Buat Kapsul") }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { onOpenCapsule?.invoke() },
                    enabled = onOpenCapsule != null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text("Buka Kapsul") }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth()) {
                    TextButton(
                        onClick = { onViewSentCapsules?.invoke() },
                        enabled = onViewSentCapsules != null && capsuleSentCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Dikirim ($capsuleSentCount)") }
                    TextButton(
                        onClick = { onViewReceivedCapsules?.invoke() },
                        enabled = onViewReceivedCapsules != null && capsuleReceivedCount > 0,
                        modifier = Modifier.weight(1f),
                    ) { Text("Diterima ($capsuleReceivedCount)") }
                }
            }
        }

        if (state.rules.isNotEmpty()) {
            item { SectionHeader("Jadwal otomatis") }
            items(state.rules, key = { "rule-${it.id}" }) { rule ->
                RecurringRuleCard(
                    rule = rule,
                    valuesVisible = state.valuesVisible,
                    onPause = onPauseRule.takeUnless { activeReadOnly },
                    onResume = onResumeRule.takeUnless { activeReadOnly },
                )
            }
        }

        item {
            SectionHeader("Privasi dan keamanan")
            HudCard {
                ToggleRow(
                    Icons.Outlined.Lock,
                    "Kunci aplikasi",
                    "Gunakan biometrik atau kredensial perangkat",
                    state.appLockEnabled,
                    onAppLock,
                )
                ToggleRow(
                    Icons.Outlined.AccountBalance,
                    "Ingat visibilitas nilai",
                    "Kunci aplikasi tetap menyembunyikan nilai saat sesi terkunci",
                    state.rememberVisibility,
                    onRememberVisibility,
                )
                ToggleRow(
                    Icons.Outlined.Security,
                    "Izinkan screenshot",
                    "Aktifkan untuk mengizinkan tangkapan layar",
                    state.screenshotAllowed,
                    onScreenshotAllowed ?: {},
                )
            }
        }

        item {
            SectionHeader("Sinkronisasi")
            val teamMode = state.activeAccount?.sharingMode == AccountSharingMode.TEAM
            val teamStatus = when {
                teamSyncing -> CloudSyncStatus.SYNCING
                teamWaitingNetwork -> CloudSyncStatus.WAITING_NETWORK
                teamSyncFailed -> CloudSyncStatus.FAILED
                state.teamWorkspace?.status == "SYNCING" -> CloudSyncStatus.SYNCING
                state.teamWorkspace?.status == "MERGE_PENDING" -> CloudSyncStatus.SYNCING
                state.teamWorkspace?.status == "WAITING_NETWORK" -> CloudSyncStatus.WAITING_NETWORK
                state.teamWorkspace?.status == "FAILED" -> CloudSyncStatus.FAILED
                state.teamWorkspace?.status == "CONFLICT" -> CloudSyncStatus.CONFLICT
                state.teamWorkspace?.status == "APPLY_PENDING" -> CloudSyncStatus.RESTART_REQUIRED
                state.teamWorkspace?.status == "AUTH_REQUIRED" -> CloudSyncStatus.NEEDS_AUTHORIZATION
                state.teamWorkspace?.status == "REVOKED" -> CloudSyncStatus.FAILED
                state.teamWorkspace?.status == "SYNCED" -> CloudSyncStatus.SYNCED
                else -> CloudSyncStatus.NOT_CONNECTED
            }
            SyncCard(
                state = SyncCardUiState(
                    mode = if (teamMode) SyncMode.TEAM else SyncMode.PRIVATE_DRIVE,
                    status = if (teamMode) teamStatus else cloudBackupState.status,
                    accountLabel = if (teamMode) teamGoogleAccountLabel else cloudBackupState.accountLabel,
                    kronAccountName = state.activeAccount?.name,
                    teamRole = state.teamWorkspace?.localRole,
                    lastSyncedAt = if (teamMode && teamStatus == CloudSyncStatus.SYNCED) state.teamWorkspace?.updatedAt else cloudBackupState.lastSyncedAt,
                    wifiOnly = cloudBackupState.wifiOnly,
                    detail = if (teamMode) {
                        teamSyncDetail ?: if (state.teamWorkspace?.status == "REVOKED") {
                            "Snapshot Team telah dihapus oleh Owner atau akses Anda telah dicabut. Data lokal dipertahankan."
                        } else null
                    } else cloudBackupState.detail,
                ),
                onConnect = onConnectCloud,
                onSyncNow = if (teamMode && state.teamWorkspace?.status != "REVOKED") onSyncTeam else onSyncNow,
                onDisconnect = onDisconnectCloud,
                onChangeAccount = onChangeCloudAccount,
                onWifiOnly = onWifiOnly.takeUnless { teamMode },
                onClearDriveData = onClearDriveData.takeUnless { teamMode },
            )
        }
        item {
            HudCard {
                SettingRow(Icons.Outlined.Backup, "Buat .kronbackup", if (activeReadOnly) "Tidak tersedia untuk Viewer" else "Backup terenkripsi untuk pemulihan atau pindah perangkat", onClick = onBackup.takeUnless { activeReadOnly })
                SettingRow(Icons.Outlined.Restore, "Pulihkan .kronbackup", if (activeReadOnly) "Tidak tersedia untuk Viewer" else "Data diverifikasi sebelum mengganti database aktif", onClick = onRestore.takeUnless { activeReadOnly })
                SettingRow(Icons.Outlined.VerifiedUser, "Pusat Bukti", "Periksa ledger, ekspor PDF, dan verifikasi paket bukti", onClick = onEvidenceCenter)
            }
        }

        item {
            SectionHeader("Notifikasi")
            HudCard {
                ToggleRow(
                    Icons.Outlined.NotificationsNone,
                    "Peringatan budget",
                    "Meminta izin Android hanya saat fitur ini diaktifkan",
                    state.budgetAlertsEnabled,
                    onBudgetAlertsChanged ?: {},
                )
                SettingRow(
                    Icons.Outlined.NotificationsNone,
                    "Pengaturan notifikasi Android",
                    if (onNotificationSettings == null) "Kelola dari Pengaturan perangkat" else "Atur izin dan privasi notifikasi",
                    onClick = onNotificationSettings,
                )
            }
        }

        item {
            SectionHeader("Tampilan")
            HudCard {
                Text("Tema", style = MaterialTheme.typography.titleMedium)
                Text("Pilih tampilan yang nyaman untuk perangkat ini.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(top = 10.dp),
                ) {
                    items(listOf(
                        Triple("DARK", "Gelap", Icons.Outlined.DarkMode),
                        Triple("LIGHT", "Terang", Icons.Outlined.LightMode),
                        Triple("SYSTEM", "Sistem", Icons.Outlined.Settings),
                    ), key = { it.first }) { (key, label, icon) ->
                        FilterChip(
                            selected = state.theme == key,
                            onClick = { onTheme(key) },
                            label = { Text(label) },
                            leadingIcon = { Icon(icon, contentDescription = label) },
                        )
                    }
                }
            }
        }

        item {
            SectionHeader("Tentang")
            HudCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
                    Column(Modifier.weight(1f)) {
                        Text("KRON ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    }
                }
                Text(
                    "Local-first • Privat • Dapat diaudit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(
                    "Seluruh fitur inti tetap dapat digunakan tanpa akun Google atau internet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                SettingRow(
                    Icons.AutoMirrored.Outlined.HelpOutline,
                    "Glosarium KRON",
                    "Arti Vault, booking, rollover, resolving, dan target pemasukan",
                    onClick = { showGlossary = true },
                )
                if (BuildConfig.PRIVACY_POLICY_URL.isNotBlank()) {
                    SettingRow(
                        Icons.Outlined.Security,
                        "Kebijakan privasi",
                        "Buka dokumen publik KRON",
                        onClick = { uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL) },
                    )
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (showGlossary) {
        AlertDialog(
            onDismissRequest = { showGlossary = false },
            title = { Text("Glosarium KRON") },
            text = {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(glossaryEntries, key = { it.first }) { (term, explanation) ->
                        Column {
                            Text(term, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.tertiary)
                            Text(explanation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showGlossary = false }) { Text("Tutup") } },
        )
    }
}

@Composable
private fun RecurringRuleCard(
    rule: RecurringRuleEntity,
    valuesVisible: Boolean,
    onPause: ((String) -> Unit)?,
    onResume: ((String, Boolean) -> Unit)?,
) {
    val finished = rule.remainingOccurrences == 0 ||
        (rule.endEpochDay != null && rule.nextEpochDay > rule.endEpochDay)
    val status = when {
        finished -> "SELESAI"
        rule.pausedByArchive -> "DIJEDA OLEH ARSIP BUDGET"
        rule.isPaused -> "DIJEDA"
        else -> "AKTIF"
    }
    HudCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(rule.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.labelSmall, color = if (finished || rule.isPaused) MaterialTheme.colorScheme.onSurfaceVariant else KronGreen)
            }
            Text(displayMoney(rule.amount, valuesVisible), style = MaterialTheme.typography.titleSmall)
        }
        Text(
            "${if (rule.direction == "INCOME") "Pemasukan" else "Pengeluaran"} setiap ${rule.intervalCount} ${if (rule.cadence == "YEARLY") "tahun" else "bulan"}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (!finished) {
            Text(
                "Occurrence berikutnya ${LocalDate.ofEpochDay(rule.nextEpochDay).format(ruleDateFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !rule.isPaused && !finished && onPause != null -> {
                TextButton(onClick = { onPause(rule.id) }) { Text("Jeda jadwal") }
            }
            rule.isPaused && !rule.pausedByArchive && !finished && onResume != null -> {
                Column(horizontalAlignment = Alignment.Start) {
                    TextButton(onClick = { onResume(rule.id, false) }) { Text("Lanjutkan dari jadwal") }
                    TextButton(onClick = { onResume(rule.id, true) }) { Text("Lanjutkan dari hari ini") }
                }
            }
            rule.pausedByArchive -> Text(
                "Pulihkan budget terkait untuk melanjutkan aturan ini.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun AccountCarousel(
    accounts: List<AccountBalanceRow>,
    state: KronUiState,
    onEditAccount: (AccountEntity) -> Unit,
    onArchiveAccount: (AccountEntity) -> Unit,
    onActivateAccount: (AccountEntity) -> Unit,
) {
    val listState = rememberLazyListState()
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(accounts, key = { it.id }) { account ->
            val entity = state.accounts.firstOrNull { it.id == account.id }
            val workspace = state.teamWorkspace?.takeIf { it.accountId == account.id }
            val readOnly = entity?.sharingMode == AccountSharingMode.TEAM && workspace?.localRole == TeamRole.VIEWER
            HudCard(
                modifier = Modifier.fillParentMaxWidth().heightIn(min = 230.dp),
                accent = if (account.isActive) KronGreen.copy(alpha = 0.62f) else MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            if (account.isActive) "AKUN AKTIF" else "Tidak aktif",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (account.isActive) KronGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            if (entity?.sharingMode == AccountSharingMode.TEAM) {
                                workspace?.localRole?.let { "TEAM $it" } ?: "TEAM"
                            } else "PRIVAT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { entity?.let(onEditAccount) }, enabled = entity != null && !readOnly) {
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit akun ${account.name}")
                    }
                    IconButton(
                        onClick = { entity?.let(onArchiveAccount) },
                        enabled = entity != null && !readOnly && !account.isActive && entity.sharingMode == AccountSharingMode.PRIVATE,
                    ) {
                        Icon(Icons.Outlined.Archive, contentDescription = "Hapus akun ${account.name} dari daftar aktif")
                    }
                }
                AccountChannelBalance("CASH", account.cashBalance, state.valuesVisible)
                Spacer(Modifier.height(8.dp))
                AccountChannelBalance("EBUDGET", account.eBudgetBalance, state.valuesVisible)
                if (!account.isActive && entity?.sharingMode == AccountSharingMode.TEAM) {
                    Text(
                        "Kembalikan Team menjadi Privat sebelum menghapus akun.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                if (!account.isActive && entity != null) {
                    TextButton(onClick = { onActivateAccount(entity) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Jadikan akun aktif")
                    }
                }
            }
        }
    }
    if (accounts.size > 1) {
        val selected = listState.firstVisibleItemIndex.coerceIn(accounts.indices)
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).semantics {
                contentDescription = "Akun ${selected + 1} dari ${accounts.size}"
            },
            horizontalArrangement = Arrangement.Center,
        ) {
            accounts.indices.forEach { index ->
                Surface(
                    modifier = Modifier.padding(horizontal = 3.dp).width(if (index == selected) 18.dp else 7.dp).height(7.dp),
                    shape = MaterialTheme.shapes.extraLarge,
                    color = if (index == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                ) {}
            }
        }
    }
}

@Composable
private fun AccountChannelBalance(channel: String, value: Long, valuesVisible: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ChannelBadge(channel)
        Text(
            displayMoney(value, valuesVisible),
            style = MaterialTheme.typography.titleLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SyncCard(
    state: SyncCardUiState,
    onConnect: (() -> Unit)?,
    onSyncNow: (() -> Unit)?,
    onDisconnect: (() -> Unit)?,
    onChangeAccount: (() -> Unit)?,
    onWifiOnly: ((Boolean) -> Unit)?,
    onClearDriveData: (() -> Unit)? = null,
) {
    val team = state.mode == SyncMode.TEAM
    val connected = if (team) state.teamRole != null else state.status !in setOf(CloudSyncStatus.NOT_CONNECTED, CloudSyncStatus.UNAVAILABLE)
    val actionsBlocked = state.status in setOf(CloudSyncStatus.SYNCING, CloudSyncStatus.RESTART_REQUIRED)
    val (defaultStatusLabel, statusColor) = cloudStatusPresentation(state.status)
    val statusLabel = if (team && state.teamRole != null && state.status == CloudSyncStatus.NOT_CONNECTED) {
        "Belum tersinkron"
    } else defaultStatusLabel
    HudCard(accent = statusColor.copy(alpha = 0.55f)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            if (state.status == CloudSyncStatus.SYNCING) {
                CircularProgressIndicator(
                    modifier = Modifier.width(28.dp).height(28.dp),
                    color = statusColor,
                )
            } else {
                Icon(
                    if (connected) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = statusColor,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(if (team) "Team di Google Drive" else "Drive Privat", style = MaterialTheme.typography.titleMedium)
                Text(statusLabel, style = MaterialTheme.typography.labelSmall, color = statusColor)
                state.kronAccountName?.let { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                state.teamRole?.let { Text("Role ${it.lowercase().replaceFirstChar(Char::uppercase)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                state.accountLabel?.let { account ->
                    Text(account, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        state.lastSyncedAt?.takeIf { state.status == CloudSyncStatus.SYNCED }?.let { timestamp ->
            Text(
                "Sinkron terakhir ${Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(syncDateFormat)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        Text(
            state.detail ?: when (state.status) {
                CloudSyncStatus.UNAVAILABLE -> "Sinkronisasi belum dikonfigurasi pada build ini. Backup lokal tetap tersedia."
                CloudSyncStatus.NOT_CONNECTED -> "Opsional. Data disimpan terenkripsi pada Drive akun yang dipilih."
                CloudSyncStatus.CONFLICT -> "Data perangkat dan ${if (team) "Team" else "Drive"} sama-sama berubah. Tinjau perbedaannya sebelum melanjutkan."
                CloudSyncStatus.SYNCING -> "Memeriksa pembaruan dan mengirim atau mengambil snapshot Team terenkripsi."
                CloudSyncStatus.WAITING_NETWORK -> "Sinkronisasi akan dicoba kembali saat jaringan tersedia."
                CloudSyncStatus.FAILED -> "Sinkronisasi terakhir belum selesai. Anda dapat mencoba lagi dari kartu ini."
                CloudSyncStatus.RESTART_REQUIRED -> "Pembaruan terenkripsi sudah divalidasi dan siap diterapkan saat aplikasi aktif."
                CloudSyncStatus.FREE_ONLY_BLOCKED -> "Sinkronisasi dihentikan karena layanan meminta billing. Backup lokal tetap tersedia."
                else -> "Data lokal tetap dapat digunakan tanpa koneksi internet."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (team) {
            Text(
                "Sinkronisasi Team dapat memakai data seluler.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (onWifiOnly != null) {
            ToggleRow(
                Icons.Outlined.Sync,
                "Gunakan Wi-Fi saja",
                "Saat mati, sinkronisasi dapat memakai data seluler.",
                state.wifiOnly,
                onWifiOnly,
            )
        }
        when {
            !connected && onConnect != null -> Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Hubungkan Drive") }
            connected && onSyncNow != null -> Button(
                onClick = onSyncNow,
                enabled = !actionsBlocked && state.status != CloudSyncStatus.FREE_ONLY_BLOCKED,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Outlined.Sync, contentDescription = null)
                Text(
                    when {
                        state.status == CloudSyncStatus.CONFLICT -> "Buka Pusat Konflik"
                        team && state.teamRole == TeamRole.VIEWER -> "Ambil pembaruan Team"
                        team -> "Sinkronkan Team"
                        else -> "Sinkronkan"
                    },
                )
            }
        }
        if (connected && onChangeAccount != null && !actionsBlocked) {
            TextButton(onClick = onChangeAccount, modifier = Modifier.padding(top = 4.dp).heightIn(min = 48.dp)) {
                Text(if (team) "Otorisasi ulang Drive Team" else "Ganti akun Drive")
            }
        }
        if (connected && !actionsBlocked) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onDisconnect != null) {
                    TextButton(
                        onClick = onDisconnect,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { Text("Putuskan") }
                }
                if (onClearDriveData != null) {
                    TextButton(
                        onClick = onClearDriveData,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.weight(1f),
                    ) { Text("Hapus data Drive") }
                }
            }
        }
    }
}

@Composable
private fun cloudStatusPresentation(status: CloudSyncStatus): Pair<String, Color> = when (status) {
    CloudSyncStatus.NOT_CONNECTED -> "Belum terhubung" to MaterialTheme.colorScheme.onSurfaceVariant
    CloudSyncStatus.SYNCING -> "Sedang menyinkronkan" to MaterialTheme.colorScheme.tertiary
    CloudSyncStatus.SYNCED -> "Tersinkron" to KronGreen
    CloudSyncStatus.WAITING_NETWORK -> "Menunggu jaringan" to MaterialTheme.colorScheme.tertiary
    CloudSyncStatus.NEEDS_AUTHORIZATION -> "Perlu otorisasi ulang" to MaterialTheme.colorScheme.tertiary
    CloudSyncStatus.FAILED -> "Sinkronisasi gagal" to MaterialTheme.colorScheme.error
    CloudSyncStatus.CONFLICT -> "Konflik data" to MaterialTheme.colorScheme.error
    CloudSyncStatus.RESTART_REQUIRED -> "Pembaruan siap diterapkan" to MaterialTheme.colorScheme.tertiary
    CloudSyncStatus.FREE_ONLY_BLOCKED -> "Dihentikan agar tetap gratis" to MaterialTheme.colorScheme.error
    CloudSyncStatus.UNAVAILABLE -> "Belum tersedia" to MaterialTheme.colorScheme.onSurfaceVariant
}

private val archiveDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
private val syncDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy, HH.mm", Locale.forLanguageTag("id-ID"))
private val ruleDateFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("id-ID"))
private val glossaryEntries = listOf(
    "Main Vault" to "Dana nyata yang tersedia tetapi belum dibooking ke budget.",
    "Cash" to "Kanal dana yang tersedia sebagai uang tunai.",
    "eBudget" to "Kanal dana digital yang tetap menjadi bagian dari aset nyata.",
    "Booking" to "Pemisahan dana dari Main Vault ke kategori budget. Booking bukan pemasukan atau pengeluaran.",
    "Reserve rollover" to "Sisa positif periode lama yang ditahan terpisah sebelum digunakan pada periode berikutnya.",
    "Resolving" to "Proses menutup kategori minus dengan sumber dana yang tercatat dan dapat diaudit.",
    "Target pemasukan" to "Metadata tujuan pemasukan. Dana tetap masuk Main Vault dan tidak otomatis dibooking.",
)

@Composable
private fun SettingRow(icon: ImageVector, title: String, subtitle: String, onClick: (() -> Unit)?) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Row(
        clickModifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}
