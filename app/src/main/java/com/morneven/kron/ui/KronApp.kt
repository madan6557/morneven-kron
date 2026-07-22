package com.morneven.kron.ui

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.WorkManager
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.morneven.kron.R
import com.morneven.kron.automation.AutomationWorker
import com.morneven.kron.ui.dialogs.AccountDialog
import com.morneven.kron.ui.dialogs.EditAccountDialog
import com.morneven.kron.data.AccountEntity
import com.morneven.kron.ui.dialogs.AuditDialog
import com.morneven.kron.ui.dialogs.BudgetDetailDialog
import com.morneven.kron.ui.dialogs.BudgetHistoryDialog
import com.morneven.kron.ui.dialogs.ChannelTransferDialog
import com.morneven.kron.ui.dialogs.ExpenseDialog
import com.morneven.kron.ui.dialogs.IncomeDialog
import com.morneven.kron.ui.dialogs.PortfolioDialog
import com.morneven.kron.ui.dialogs.ResolveDialog
import com.morneven.kron.ui.dialogs.TransferDialog
import com.morneven.kron.ui.screens.ActivityScreen
import com.morneven.kron.ui.screens.BudgetScreen
import com.morneven.kron.ui.screens.HomeScreen
import com.morneven.kron.ui.screens.OnboardingScreen
import com.morneven.kron.ui.screens.ReportsScreen
import com.morneven.kron.ui.screens.SettingsScreen
import com.morneven.kron.ui.screens.CloudBackupUiState
import com.morneven.kron.ui.screens.CloudSyncStatus
import com.morneven.kron.sync.ConflictResolution
import com.morneven.kron.sync.DriveConnectResult
import com.morneven.kron.sync.DriveSyncRuntime
import com.morneven.kron.sync.GoogleAccountIdentity
import com.morneven.kron.sync.SyncConflict
import com.morneven.kron.sync.SyncRunResult
import com.morneven.kron.ui.theme.KronTheme
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class ActionDialog { INCOME, EXPENSE, TRANSFER, PORTFOLIO, RESOLVE, CHANNEL_TRANSFER, ACCOUNT }

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)
private data class CriticalAction(val title: String, val summary: String, val onConfirm: (String) -> Unit)

private val destinations = listOf(
    Destination("home", "Beranda", Icons.Outlined.Home),
    Destination("budget", "Budget", Icons.Outlined.AccountBalanceWallet),
    Destination("activity", "Transaksi", Icons.AutoMirrored.Outlined.ReceiptLong),
    Destination("reports", "Laporan", Icons.Outlined.Assessment),
    Destination("settings", "Pengaturan", Icons.Outlined.Settings),
)

private fun routePosition(route: String?): Int = destinations.indexOfFirst { it.route == route }.takeIf { it >= 0 } ?: 0

@Composable
fun KronApp(
    viewModel: MainViewModel,
    activity: FragmentActivity,
    driveSyncRuntime: DriveSyncRuntime?,
) {
    val state by viewModel.uiState.collectAsState()
    KronTheme(state.theme) {
        val view = LocalView.current
        val darkTheme = when (state.theme) {
            "LIGHT" -> false
            "DARK" -> true
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }
        SideEffect {
            if (!view.isInEditMode) {
                WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                WindowCompat.getInsetsController(activity.window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
                activity.window.statusBarColor = Color.Transparent.toArgb()
                activity.window.navigationBarColor = Color.Transparent.toArgb()
            }
        }
        if (!state.onboardingComplete) {
            if (state.accounts.isNotEmpty()) {
                OnboardingScreen(viewModel::completeOnboarding)
            }
            return@KronTheme
        }
        var locked by rememberSaveable { mutableStateOf(false) }
        var backgroundAt by remember { mutableLongStateOf(0L) }
        var lockError by remember { mutableStateOf<String?>(null) }
        DisposableEffect(activity, locked) {
            if (locked) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose {
                activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        val authenticate = remember(activity, state.authLockedUntil, state.authFailures) {
            {
                if (System.currentTimeMillis() < state.authLockedUntil) {
                    lockError = "Terlalu banyak percobaan. Coba lagi nanti."
                    return@remember
                }
                val executor = ContextCompat.getMainExecutor(activity)
                var currentFailures = state.authFailures
                var blockedByKron = false
                lateinit var prompt: BiometricPrompt
                prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        locked = false
                        lockError = null
                        viewModel.resetAuthFailures()
                        viewModel.restoreRememberedVisibility()
                    }

                    override fun onAuthenticationFailed() {
                        currentFailures += 1
                        viewModel.recordAuthFailure()
                        if (currentFailures >= 5) {
                            blockedByKron = true
                            lockError = "Batas percobaan tercapai. KRON dikunci sementara."
                            prompt.cancelAuthentication()
                        } else {
                            lockError = "Autentikasi gagal. Sisa percobaan: ${5 - currentFailures}."
                        }
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (!blockedByKron) lockError = errString.toString()
                    }
                })
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Buka KRON")
                    .setSubtitle("Lindungi catatan keuangan Anda")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                    .build()
                prompt.authenticate(info)
            }
        }
        LaunchedEffect(state.appLockEnabled) {
            if (state.appLockEnabled) {
                locked = true
                viewModel.hideValuesForLock()
                authenticate()
            } else locked = false
        }
        DisposableEffect(lifecycleOwner, state.appLockEnabled) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_STOP -> backgroundAt = SystemClock.elapsedRealtime()
                    Lifecycle.Event.ON_START -> {
                        if (
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            state.budgetAlertsEnabled &&
                            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.setBudgetAlertsEnabled(false)
                        }
                        if (state.appLockEnabled && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= 60_000) {
                            locked = true
                            viewModel.hideValuesForLock()
                            authenticate()
                        }
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        if (locked) {
            LockScreen(lockError, state.authFailures, state.authLockedUntil, authenticate)
        } else {
            MainScaffold(state, viewModel, activity, driveSyncRuntime)
        }
    }
}

@Composable
private fun MainScaffold(
    state: KronUiState,
    viewModel: MainViewModel,
    activity: FragmentActivity,
    driveSyncRuntime: DriveSyncRuntime?,
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "home"
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<ActionDialog?>(null) }
    var auditId by remember { mutableStateOf<String?>(null) }
    var detailPeriod by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    var historyPortfolioId by remember { mutableStateOf<Long?>(null) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    var criticalAction by remember { mutableStateOf<CriticalAction?>(null) }
    var criticalReason by remember { mutableStateOf("") }
    var editAccount by remember { mutableStateOf<AccountEntity?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
    var receiptTargetEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraTargetEventId by rememberSaveable { mutableStateOf<String?>(null) }
    var showCameraCapture by remember { mutableStateOf(false) }
    var dialogReceiptUri by remember { mutableStateOf<Uri?>(null) }
    var dialogCameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val manualRestoreReady by viewModel.isManualRestoreReady.collectAsState()
    val pendingDriveSubject by viewModel.pendingDriveSubjectId.collectAsState()
    val pendingDriveEmail by viewModel.pendingDriveEmail.collectAsState()
    val pendingDriveName by viewModel.pendingDriveDisplayName.collectAsState()
    val pendingDriveResolution by viewModel.pendingDriveResolutionId.collectAsState()
    val pendingAuthorization = remember(
        pendingDriveSubject,
        pendingDriveEmail,
        pendingDriveName,
        pendingDriveResolution,
    ) {
        val resolutionId = pendingDriveResolution ?: return@remember null
        if (pendingDriveSubject != null && pendingDriveEmail != null) {
            DriveConnectResult.UserActionRequired(
                account = GoogleAccountIdentity(
                    subjectId = pendingDriveSubject!!,
                    email = pendingDriveEmail!!,
                    displayName = pendingDriveName,
                ),
                resolutionId = resolutionId,
            )
        } else {
            DriveConnectResult.UserActionRequired(
                account = null,
                resolutionId = resolutionId,
            )
        }
    }
    var launchedAuthorizationId by rememberSaveable { mutableStateOf<String?>(null) }
    var cloudConflict by remember { mutableStateOf<SyncConflict?>(null) }
    var restartRequired by rememberSaveable { mutableStateOf(false) }
    var cloudWifiOnly by rememberSaveable(driveSyncRuntime) {
        mutableStateOf(driveSyncRuntime?.isWifiOnly() ?: false)
    }
    val scope = rememberCoroutineScope()

    fun handleSyncResult(result: SyncRunResult) {
        when (result) {
            is SyncRunResult.Synchronized -> viewModel.showMessage(
                if (result.uploaded) "Data terenkripsi berhasil dikirim ke Drive" else "Data Drive berhasil diterapkan",
            )
            is SyncRunResult.RestartRequired -> {
                restartRequired = true
                driveSyncRuntime?.let { runtime ->
                    scope.launch { runtime.suspendForRestart() }
                }
                WorkManager.getInstance(activity).cancelUniqueWork(AutomationWorker.UNIQUE_WORK_NAME)
            }
            SyncRunResult.NoChanges -> viewModel.showMessage("Data perangkat dan Drive sudah sama")
            SyncRunResult.NoData -> viewModel.showMessage("Belum ada data yang perlu disinkronkan")
            SyncRunResult.Disabled -> viewModel.showMessage("Sinkronisasi Drive dinonaktifkan")
            SyncRunResult.AuthorizationRequired -> {
                viewModel.showMessage("Otorisasi Drive perlu diperbarui")
                driveSyncRuntime?.let { runtime ->
                    scope.launch {
                        when (val connection = runtime.reauthorizeCurrent()) {
                            is DriveConnectResult.Connected -> handleSyncResult(runtime.syncNow())
                            is DriveConnectResult.UserActionRequired -> {
                                launchedAuthorizationId = null
                                viewModel.setPendingDriveAuthorization(connection.account, connection.resolutionId)
                            }
                            is DriveConnectResult.Failed -> viewModel.showMessage(connection.message)
                        }
                    }
                }
            }
            SyncRunResult.PassphraseRequired -> passwordMode = "DRIVE_UNLOCK"
            SyncRunResult.FreeOnlyBlocked -> viewModel.showMessage(
                "Sinkronisasi Drive dihentikan karena layanan meminta billing. Backup manual tetap tersedia.",
            )
            is SyncRunResult.Conflict -> cloudConflict = result.value
            is SyncRunResult.Error -> viewModel.showMessage(result.message)
        }
    }

    fun handleConnectResult(result: DriveConnectResult) {
        when (result) {
            is DriveConnectResult.Connected -> {
                viewModel.showMessage("Terhubung ke ${result.account.email}")
                driveSyncRuntime?.let { runtime ->
                    scope.launch { handleSyncResult(runtime.syncNow()) }
                }
            }
            is DriveConnectResult.UserActionRequired -> {
                launchedAuthorizationId = null
                viewModel.setPendingDriveAuthorization(result.account, result.resolutionId)
            }
            is DriveConnectResult.Failed -> viewModel.showMessage(result.message)
        }
    }
    val backupLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val password = pendingPassword
        if (uri != null && password != null) viewModel.exportBackup(uri, password)
        pendingPassword = null
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val password = pendingPassword
        if (uri != null && password != null) viewModel.stageRestore(uri, password)
        pendingPassword = null
    }
    val reportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) viewModel.exportCsv(uri)
    }
    val receiptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val eventId = receiptTargetEventId
        if (uri != null && eventId != null) {
            viewModel.attachReceipt(eventId, uri)
        } else if (uri != null) {
            dialogReceiptUri = uri
        }
        receiptTargetEventId = null
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.setBudgetAlertsEnabled(granted)
        if (!granted) viewModel.showMessage("Izin notifikasi tidak diberikan")
    }
    val authorizationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        val pending = pendingAuthorization
        viewModel.clearPendingDriveAuthorization()
        launchedAuthorizationId = null
        if (pending != null && driveSyncRuntime != null) {
            scope.launch {
                runCatching {
                    driveSyncRuntime.completeAuthorization(
                        account = pending.account,
                        resolutionId = pending.resolutionId,
                        resultCode = result.resultCode,
                        data = result.data,
                    )
                }.onSuccess(::handleConnectResult)
                    .onFailure {
                        driveSyncRuntime.cancelAuthorization(pending.resolutionId)
                        viewModel.showMessage("Persetujuan Drive tidak dapat diselesaikan. Silakan hubungkan ulang.")
                    }
            }
        }
    }
    LaunchedEffect(pendingAuthorization?.resolutionId) {
        val pending = pendingAuthorization ?: return@LaunchedEffect
        val runtime = driveSyncRuntime ?: return@LaunchedEffect
        if (launchedAuthorizationId == pending.resolutionId) return@LaunchedEffect
        launchedAuthorizationId = pending.resolutionId
        runCatching { runtime.authorizationRequest(pending.resolutionId) }
            .onSuccess(authorizationLauncher::launch)
            .onFailure {
                viewModel.clearPendingDriveAuthorization()
                launchedAuthorizationId = null
                runtime.cancelAuthorization(pending.resolutionId)
                viewModel.showMessage("Permintaan otorisasi Drive sudah tidak berlaku")
            }
    }
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
    }
    val cloudBackupState = cloudBackupUiState(
        syncState = state.syncState,
        available = driveSyncRuntime != null,
        wifiOnly = cloudWifiOnly,
    )
    LaunchedEffect(state.syncState?.status) {
        if (state.syncState?.status == "RESTART_REQUIRED") {
            restartRequired = true
            driveSyncRuntime?.suspendForRestart()
            WorkManager.getInstance(activity).cancelUniqueWork(AutomationWorker.UNIQUE_WORK_NAME)
        }
    }
    LaunchedEffect(manualRestoreReady) {
        if (manualRestoreReady) restartRequired = true
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = current == destination.route,
                        onClick = { navController.navigate(destination.route) { popUpTo("home"); launchSingleTop = true } },
                        icon = { Icon(destination.icon, contentDescription = destination.label) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        NavHost(
            navController,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            enterTransition = {
                val direction = if (routePosition(targetState.destination.route) >= routePosition(initialState.destination.route)) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(220))
            },
            exitTransition = {
                val direction = if (routePosition(targetState.destination.route) >= routePosition(initialState.destination.route)) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideOutOfContainer(direction, tween(220))
            },
            popEnterTransition = {
                val direction = if (routePosition(targetState.destination.route) >= routePosition(initialState.destination.route)) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideIntoContainer(direction, tween(220))
            },
            popExitTransition = {
                val direction = if (routePosition(targetState.destination.route) >= routePosition(initialState.destination.route)) AnimatedContentTransitionScope.SlideDirection.Left else AnimatedContentTransitionScope.SlideDirection.Right
                slideOutOfContainer(direction, tween(220))
            },
        ) {
            composable("home") {
                HomeScreen(
                    state,
                    viewModel::toggleValues,
                    { dialog = ActionDialog.INCOME },
                    { dialog = ActionDialog.EXPENSE },
                    { dialog = ActionDialog.TRANSFER },
                    { dialog = ActionDialog.RESOLVE },
                    { navController.navigate("activity") },
                    { ruleId -> criticalAction = CriticalAction("Hentikan jadwal otomatis", "Occurrence berikutnya tidak akan dibuat. Riwayat lama tetap tersimpan.") { viewModel.pauseRecurringRule(ruleId, it) }; criticalReason = "" },
                )
            }
            composable("budget") {
                BudgetScreen(
                    state = state,
                    onCreate = { dialog = ActionDialog.PORTFOLIO },
                    onResolve = { dialog = ActionDialog.RESOLVE },
                    onFund = viewModel::fundPeriod,
                    onChannelTransfer = { dialog = ActionDialog.CHANNEL_TRANSFER },
                    onReleaseRollover = viewModel::releaseRolloverToVault,
                    onDetail = { periodId, readOnly -> detailPeriod = periodId to readOnly },
                    onHistory = { historyPortfolioId = it },
                    onPause = { portfolioId ->
                        criticalAction = CriticalAction("Jeda portfolio", "Periode baru tidak akan dibuat sampai portfolio dilanjutkan. Riwayat tetap tersimpan.") { viewModel.pausePortfolio(portfolioId, it) }
                        criticalReason = ""
                    },
                    onResume = { portfolioId ->
                        criticalAction = CriticalAction("Lanjutkan portfolio", "KRON akan kembali membuat periode berikutnya sesuai jadwal portfolio.") { viewModel.resumePortfolio(portfolioId, it) }
                        criticalReason = ""
                    },
                    onArchive = { portfolioId ->
                        val rows = state.allocations.filter { it.portfolioId == portfolioId }
                        val allocationIds = rows.map { it.id }.toSet()
                        val releaseCash = rows.filter { it.fundingChannel == "CASH" && it.availableAmount > 0 }.sumOf { it.availableAmount }
                        val releaseEBudget = rows.filter { it.fundingChannel == "EBUDGET" && it.availableAmount > 0 }.sumOf { it.availableAmount }
                        val rules = state.rules.count { it.allocationId in allocationIds && !it.isPaused }
                        criticalAction = CriticalAction(
                            "Arsipkan portfolio",
                            "Sisa Cash ${com.morneven.kron.ui.components.displayMoney(releaseCash, state.valuesVisible)} dan eBudget ${com.morneven.kron.ui.components.displayMoney(releaseEBudget, state.valuesVisible)} akan kembali ke Main Vault. Periode terbuka ditutup dan $rules jadwal terkait dijeda.",
                        ) { viewModel.archivePortfolio(portfolioId, it) }
                        criticalReason = ""
                    },
                    onRestore = { portfolioId, activate ->
                        val portfolio = state.archivedPortfolios.firstOrNull { it.id == portfolioId }
                        val rows = state.allocations.filter { it.portfolioId == portfolioId }
                        val latestRows = rows.groupBy { it.periodId }.maxByOrNull { (_, values) -> values.maxOf { it.startEpochDay } }?.value.orEmpty()
                        val cashNeed = latestRows.filter { it.fundingChannel == "CASH" }.sumOf { it.plannedAmount }
                        val eBudgetNeed = latestRows.filter { it.fundingChannel == "EBUDGET" }.sumOf { it.plannedAmount }
                        val summary = if (activate) {
                            "${portfolio?.name ?: "Portfolio"} kembali ke tab Aktif. Periode valid berikutnya memerlukan Cash ${com.morneven.kron.ui.components.displayMoney(cashNeed, state.valuesVisible)} dan eBudget ${com.morneven.kron.ui.components.displayMoney(eBudgetNeed, state.valuesVisible)}. Jika Vault belum cukup, status menjadi UNDERFUNDED."
                        } else {
                            "${portfolio?.name ?: "Portfolio"} kembali ke tab Aktif dalam keadaan dijeda. Periode historis tidak berubah."
                        }
                        criticalAction = CriticalAction(if (activate) "Pulihkan dan aktifkan" else "Pulihkan portfolio", summary) { viewModel.restorePortfolio(portfolioId, activate, it) }
                        criticalReason = ""
                    },
                )
            }
            composable("activity") { ActivityScreen(state, { auditId = it }) }
            composable("reports") {
                ReportsScreen(
                    state = state,
                    onExport = { reportLauncher.launch("KRON-laporan-${LocalDate.now()}.csv") },
                    eventChannels = state.eventChannels,
                )
            }
            composable("settings") {
                SettingsScreen(
                    state = state,
                    onAddAccount = { dialog = ActionDialog.ACCOUNT },
                    onEditAccount = { editAccount = it },
                    onArchiveAccount = { account -> criticalAction = CriticalAction("Arsipkan akun", "Riwayat akun tetap tersimpan. Akun hanya disembunyikan dari daftar aktif.") { reason -> viewModel.archiveAccount(account.id, reason) }; criticalReason = "" },
                    onRestoreAccount = { account -> criticalAction = CriticalAction("Pulihkan akun", "Akun kembali ke tab Aktif sebagai akun tidak aktif. Pilih Jadikan akun aktif secara terpisah.") { reason -> viewModel.restoreAccount(account.id, reason) }; criticalReason = "" },
                    onActivateAccount = { account -> viewModel.activateAccount(account.id) },
                    onViewAudit = { navController.navigate("activity") },
                    onRememberVisibility = viewModel::setRememberVisibility,
                    onAppLock = viewModel::setAppLock,
                    onTheme = viewModel::setTheme,
                    onBackup = { passwordMode = "BACKUP" },
                    onRestore = { passwordMode = "RESTORE" },
                    onPauseRule = { ruleId ->
                        criticalAction = CriticalAction(
                            "Jeda jadwal otomatis",
                            "Occurrence berikutnya tidak akan dibuat. Transaksi dan audit lama tetap tersimpan.",
                        ) { viewModel.pauseRecurringRule(ruleId, it) }
                        criticalReason = ""
                    },
                    onResumeRule = { ruleId, fromToday ->
                        criticalAction = CriticalAction(
                            "Lanjutkan jadwal otomatis",
                            if (fromToday) {
                                "Occurrence berikutnya dihitung dari hari ini. Riwayat lama tidak diubah."
                            } else {
                                "Occurrence berikutnya dihitung dari anchor jadwal terakhir. Catch-up tetap idempotent."
                            },
                        ) { viewModel.resumeRecurringRule(ruleId, fromToday, it) }
                        criticalReason = ""
                    },
                    cloudBackupState = cloudBackupState,
                    onConnectCloud = null,
                    onSyncNow = null,
                    onDisconnectCloud = null,
                    onChangeCloudAccount = null,
                    onWifiOnly = null,
                    onBudgetAlertsChanged = { enabled ->
                        if (!enabled) {
                            viewModel.setBudgetAlertsEnabled(false)
                        } else if (
                            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                            ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.setBudgetAlertsEnabled(true)
                        } else {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onNotificationSettings = {
                        activity.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName)
                            },
                        )
                    },
                )
            }
        }
    }
    editAccount?.let { account ->
        EditAccountDialog(account, { editAccount = null }) { name ->
            editAccount = null
            viewModel.updateAccount(account.id, name)
        }
    }
    when (dialog) {
        ActionDialog.INCOME -> IncomeDialog(
            state = state, onDismiss = { dialog = null; dialogReceiptUri = null; dialogCameraFile = null },
            receiptUri = dialogReceiptUri, cameraFile = dialogCameraFile,
            onGalleryPick = {
                dialogCameraFile = null
                receiptLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCameraCapture = {
                dialogReceiptUri = null
                showCameraCapture = true
            },
            onSubmit = { account, channel, amount, category, target, title, note, recurring, startDate, endDate, interval, recordNow, _, _ -> dialog = null; viewModel.addIncome(account, channel, amount, category, target, title, note, recurring, startDate, endDate, interval, recordNow, dialogReceiptUri, dialogCameraFile); dialogReceiptUri = null; dialogCameraFile = null },
        )
        ActionDialog.EXPENSE -> ExpenseDialog(
            state = state, onDismiss = { dialog = null; dialogReceiptUri = null; dialogCameraFile = null },
            receiptUri = dialogReceiptUri, cameraFile = dialogCameraFile,
            onGalleryPick = {
                dialogCameraFile = null
                receiptLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onCameraCapture = {
                dialogReceiptUri = null
                showCameraCapture = true
            },
            onSubmit = { account, channel, amount, splits, title, note, unexpected, recurring, startDate, endDate, interval, recordNow, _, _ -> dialog = null; viewModel.addExpense(account, channel, amount, splits, title, note, unexpected, recurring, startDate, endDate, interval, recordNow, dialogReceiptUri, dialogCameraFile); dialogReceiptUri = null; dialogCameraFile = null },
        )
        ActionDialog.TRANSFER -> TransferDialog(state, { dialog = null }) { fromAccount, fromChannel, toAccount, toChannel, amount, note -> dialog = null; viewModel.transfer(fromAccount, fromChannel, toAccount, toChannel, amount, note) }
        ActionDialog.PORTFOLIO -> PortfolioDialog(state, { dialog = null }) { name, cadence, income, rollover, drafts, startDate, endDate, interval -> dialog = null; viewModel.createPortfolio(name, cadence, income, rollover, drafts, startDate, endDate, interval) }
        ActionDialog.RESOLVE -> ResolveDialog(state, { dialog = null }, { source, target, amount, note -> dialog = null; viewModel.resolveFromAllocation(source, target, amount, note) }, { target, amount, note -> dialog = null; viewModel.resolveFromVault(target, amount, note) }, { target, amount, note -> dialog = null; viewModel.resolveFromRollover(target, amount, note) }, { target, amount, note -> dialog = null; viewModel.allocateUnallocated(target, amount, note) })
        ActionDialog.CHANNEL_TRANSFER -> ChannelTransferDialog(state, { dialog = null }) { allocation, from, to, amount, note -> dialog = null; viewModel.transferBookedChannel(allocation, from, to, amount, note) }
        ActionDialog.ACCOUNT -> AccountDialog({ dialog = null }) { name, openingCash, openingEBudget -> dialog = null; viewModel.addAccount(name, openingCash, openingEBudget) }
        null -> Unit
    }
    auditId?.let { id ->
        state.activities.firstOrNull { it.id == id }?.let { event ->
            AuditDialog(
                event = event,
                state = state,
                onDismiss = { auditId = null },
                onGalleryPick = { eventId ->
                    receiptTargetEventId = eventId
                    receiptLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onCameraCapture = { eventId ->
                    cameraTargetEventId = eventId
                    showCameraCapture = true
                },
                onRevert = { eventId, reason ->
                    auditId = null
                    viewModel.reverseEvent(eventId, reason)
                },
            )
        }
    }
    detailPeriod?.let { (periodId, readOnly) -> BudgetDetailDialog(state, periodId, readOnly, { detailPeriod = null }, viewModel::correctAllocation) }
    historyPortfolioId?.let { portfolioId ->
        BudgetHistoryDialog(
            state = state,
            portfolioId = portfolioId,
            onDismiss = { historyPortfolioId = null },
            onDetail = { periodId -> historyPortfolioId = null; detailPeriod = periodId to false },
        )
    }
    passwordMode?.let { mode ->
        if (mode == "BACKUP" || mode == "RESTORE") {
            PasswordDialog(mode == "BACKUP", { passwordMode = null }) { password ->
                passwordMode = null
                pendingPassword = password.toCharArray()
                if (mode == "BACKUP") backupLauncher.launch("KRON-${LocalDate.now()}.kronbackup")
                else restoreLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
            }
        } else {
            SyncPassphraseDialog(
                reconnecting = mode == "DRIVE_UNLOCK",
                onDismiss = { passwordMode = null },
            ) { passphrase ->
                passwordMode = null
                val runtime = driveSyncRuntime
                if (runtime == null) {
                    passphrase.fill('\u0000')
                    viewModel.showMessage("Konfigurasi OAuth Drive belum tersedia")
                } else {
                    scope.launch {
                        if (mode == "DRIVE_UNLOCK") {
                            handleSyncResult(runtime.supplyPassphrase(passphrase))
                        } else {
                            handleConnectResult(runtime.connect(passphrase))
                        }
                    }
                }
            }
        }
    }
    criticalAction?.let { action ->
        AlertDialog(
            onDismissRequest = { criticalAction = null },
            title = { Text(action.title) },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    Text(action.summary)
                    OutlinedTextField(criticalReason, { criticalReason = it }, label = { Text("Alasan wajib") }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { Button(onClick = { action.onConfirm(criticalReason); criticalAction = null }, enabled = criticalReason.isNotBlank()) { Text("Konfirmasi") } },
            dismissButton = { TextButton(onClick = { criticalAction = null }) { Text("Batal") } },
        )
    }
    cloudConflict?.let { conflict ->
        AlertDialog(
            onDismissRequest = { cloudConflict = null },
            title = { Text("Pusat Konflik Drive") },
            text = {
                Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
                    Text(conflictDescription(conflict))
                    Text(
                        "Simpan keduanya adalah pilihan paling aman. KRON menyimpan data perangkat sebagai snapshot pemulihan sebelum menerapkan data Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    Button(onClick = {
                        cloudConflict = null
                        driveSyncRuntime?.let { runtime ->
                            scope.launch { handleSyncResult(runtime.resolveConflict(conflict, ConflictResolution.KEEP_BOTH)) }
                        }
                    }) { Text("Simpan keduanya") }
                    TextButton(onClick = {
                        cloudConflict = null
                        criticalAction = CriticalAction(
                            "Gunakan perangkat ini",
                            "Snapshot aktif Drive akan diganti oleh data perangkat ini. Snapshot pemulihan lama tetap mengikuti kebijakan retensi.",
                        ) {
                            driveSyncRuntime?.let { runtime ->
                                scope.launch { handleSyncResult(runtime.resolveConflict(conflict, ConflictResolution.USE_THIS_DEVICE)) }
                            }
                        }
                        criticalReason = ""
                    }) { Text("Gunakan perangkat ini") }
                    TextButton(onClick = {
                        cloudConflict = null
                        criticalAction = CriticalAction(
                            "Pulihkan dari Drive",
                            "Data lokal saat ini diamankan sebagai snapshot pemulihan, lalu data Drive disiapkan untuk restore setelah KRON dibuka ulang.",
                        ) {
                            driveSyncRuntime?.let { runtime ->
                                scope.launch { handleSyncResult(runtime.resolveConflict(conflict, ConflictResolution.USE_DRIVE)) }
                            }
                        }
                        criticalReason = ""
                    }) { Text("Pulihkan dari Drive") }
                }
            },
            dismissButton = { TextButton(onClick = { cloudConflict = null }) { Text("Nanti") } },
        )
    }
    if (restartRequired) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Buka ulang KRON") },
            text = {
                Text("Data restore sudah lolos validasi. KRON harus ditutup sebelum database dan lampiran diganti secara aman. Jangan catat transaksi baru sebelum membuka ulang aplikasi.")
            },
            confirmButton = {
                Button(onClick = {
                    activity.finishAffinity()
                    Process.killProcess(Process.myPid())
                }) { Text("Tutup KRON") }
            },
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        )
    }
    if (showCameraCapture) {
        CameraCaptureScreen(
            onPhotoCaptured = { uri ->
                val file = uri.path?.let { java.io.File(it) }
                if (file != null) {
                    if (cameraTargetEventId != null) {
                        viewModel.attachCameraReceipt(cameraTargetEventId!!, file)
                        cameraTargetEventId = null
                    } else {
                        dialogCameraFile = file
                    }
                }
                showCameraCapture = false
            },
            onCancel = {
                cameraTargetEventId = null
                showCameraCapture = false
            },
        )
    }
}

@Composable
private fun LockScreen(error: String?, failures: Int, lockedUntil: Long, onUnlock: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    var now by remember(lockedUntil) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntil) {
        while (lockedUntil > now) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    val remainingSeconds = ((lockedUntil - now + 999) / 1_000).coerceAtLeast(0)
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val grid = 52.dp.toPx()
            var x = 0f
            while (x < size.width) {
                drawLine(primary.copy(alpha = 0.045f), Offset(x, 0f), Offset(x, size.height), 1f)
                x += grid
            }
            var y = 0f
            while (y < size.height) {
                drawLine(tertiary.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f)
                y += grid
            }
        }

        Surface(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp, vertical = 54.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 10.dp, topEnd = 34.dp, bottomEnd = 10.dp, bottomStart = 34.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, primary.copy(alpha = 0.52f)),
            shadowElevation = 18.dp,
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                KronLogoMark()
                Spacer(Modifier.height(18.dp))
                Text("KRON", style = MaterialTheme.typography.displaySmall, color = primary, fontWeight = FontWeight.Black)
                Text("PRIVATE FINANCIAL LEDGER", style = MaterialTheme.typography.labelSmall, color = tertiary)
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Shield, contentDescription = null, tint = tertiary, modifier = Modifier.size(18.dp))
                    Text("  SESI TERLINDUNGI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Text("Brankas Anda terkunci", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
                Text(
                    "Autentikasi diperlukan untuk membuka jurnal, saldo, dan seluruh aktivitas keuangan.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (error != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.28f)),
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(12.dp))
                    }
                }
                if (remainingSeconds > 0) {
                    Text("Tunggu $remainingSeconds detik sebelum mencoba lagi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                } else if (failures > 0) {
                    Text("Percobaan gagal: $failures/5", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(if (error == null) 22.dp else 14.dp))
                Button(
                    onClick = onUnlock,
                    enabled = remainingSeconds == 0L,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                    Text("  Buka KRON", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        Text(
            "LOCAL  •  PRIVATE  •  AUDITABLE",
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun KronLogoMark() {
    val primary = MaterialTheme.colorScheme.primary
    Surface(
        modifier = Modifier.size(92.dp),
        shape = RoundedCornerShape(topStart = 14.dp, topEnd = 30.dp, bottomEnd = 14.dp, bottomStart = 30.dp),
        color = primary.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, primary.copy(alpha = 0.62f)),
        shadowElevation = 10.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Image(
                painter = painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = "Logo KRON",
                modifier = Modifier.size(80.dp),
            )
        }
    }
}

@Composable
private fun PasswordDialog(isBackup: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val valid = password.length >= 12 && (!isBackup || password == confirmation)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isBackup) "Enkripsi backup" else "Buka backup") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Passphrase minimal 12 karakter") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (visible) "Sembunyikan passphrase" else "Tampilkan passphrase")
                        }
                    },
                )
                if (isBackup) {
                    OutlinedTextField(
                        confirmation,
                        { confirmation = it },
                        label = { Text("Ulangi passphrase") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                        supportingText = {
                            if (confirmation.isNotEmpty() && confirmation != password) Text("Passphrase tidak sama", color = MaterialTheme.colorScheme.error)
                        },
                    )
                }
                Text("Passphrase tidak dapat dipulihkan oleh Google atau KRON.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(password) }, enabled = valid) { Text(if (isBackup) "Pilih lokasi" else "Pilih file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

@Composable
private fun SyncPassphraseDialog(
    reconnecting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (CharArray) -> Unit,
) {
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val valid = password.length >= 12 && password == confirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (reconnecting) "Passphrase sinkronisasi" else "Hubungkan Google Drive") },
        text = {
            Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
                Text(
                    if (reconnecting) {
                        "Masukkan passphrase dataset Drive yang sama. Google dan KRON tidak dapat memulihkannya."
                    } else {
                        "Buat passphrase untuk mengenkripsi data sebelum diunggah. Perangkat lain harus memakai passphrase yang sama."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it.take(1_024) },
                    label = { Text("Passphrase minimal 12 karakter") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        androidx.compose.material3.IconButton(onClick = { visible = !visible }) {
                            Icon(
                                if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Sembunyikan passphrase" else "Tampilkan passphrase",
                            )
                        }
                    },
                )
                OutlinedTextField(
                    value = confirmation,
                    onValueChange = { confirmation = it.take(1_024) },
                    label = { Text("Ulangi passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    supportingText = {
                        if (confirmation.isNotEmpty() && confirmation != password) {
                            Text("Passphrase tidak sama", color = MaterialTheme.colorScheme.error)
                        }
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val chars = password.toCharArray()
                    password = ""
                    confirmation = ""
                    onConfirm(chars)
                },
                enabled = valid,
            ) { Text(if (reconnecting) "Gunakan passphrase" else "Pilih akun Google") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}

private fun cloudBackupUiState(
    syncState: com.morneven.kron.data.SyncStateEntity?,
    available: Boolean,
    wifiOnly: Boolean,
): CloudBackupUiState {
    if (!available) return CloudBackupUiState(status = CloudSyncStatus.UNAVAILABLE, wifiOnly = wifiOnly)
    val status = when (syncState?.status) {
        null, "DISCONNECTED", "DISABLED" -> CloudSyncStatus.NOT_CONNECTED
        "SYNCING" -> CloudSyncStatus.SYNCING
        "IDLE", "SYNCED" -> if (syncState.accountEmail == null) CloudSyncStatus.NOT_CONNECTED else CloudSyncStatus.SYNCED
        "WAITING_FOR_NETWORK" -> CloudSyncStatus.WAITING_NETWORK
        "AUTHORIZATION_REQUIRED", "PASSPHRASE_REQUIRED" -> CloudSyncStatus.NEEDS_AUTHORIZATION
        "CONFLICT" -> CloudSyncStatus.CONFLICT
        "RESTART_REQUIRED" -> CloudSyncStatus.RESTART_REQUIRED
        "FREE_ONLY_BLOCKED" -> CloudSyncStatus.FREE_ONLY_BLOCKED
        else -> CloudSyncStatus.FAILED
    }
    return CloudBackupUiState(
        status = status,
        accountLabel = syncState?.accountEmail,
        lastSyncedAt = syncState?.lastSyncedAt,
        wifiOnly = wifiOnly,
        detail = syncState?.lastError,
    )
}

private fun conflictDescription(conflict: SyncConflict): String = when (conflict.reason) {
    com.morneven.kron.sync.SyncConflictReason.FIRST_CONNECTION_WITH_TWO_DATASETS ->
        "Perangkat dan Drive memiliki dataset berbeda pada sambungan pertama."
    com.morneven.kron.sync.SyncConflictReason.BOTH_SIDES_CHANGED ->
        "Data perangkat dan Drive sama-sama berubah sejak sinkronisasi terakhir."
    com.morneven.kron.sync.SyncConflictReason.ACCOUNT_CHANGED ->
        "Akun Google yang dipilih berbeda dari akun sinkronisasi sebelumnya."
    com.morneven.kron.sync.SyncConflictReason.DATASET_MISMATCH ->
        "Dataset Drive tidak sama dengan dataset aktif pada perangkat."
    com.morneven.kron.sync.SyncConflictReason.REMOTE_CHANGED_DURING_RESOLUTION ->
        "Snapshot Drive berubah ketika konflik sedang diselesaikan. Muat ulang sebelum memilih tindakan."
}
