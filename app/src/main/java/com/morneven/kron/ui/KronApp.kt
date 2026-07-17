package com.morneven.kron.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Assessment
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.morneven.kron.R
import com.morneven.kron.ui.dialogs.AccountDialog
import com.morneven.kron.ui.dialogs.AuditDialog
import com.morneven.kron.ui.dialogs.BudgetDetailDialog
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
import com.morneven.kron.ui.theme.KronTheme
import java.time.LocalDate

private enum class ActionDialog { INCOME, EXPENSE, TRANSFER, PORTFOLIO, RESOLVE, CHANNEL_TRANSFER, ACCOUNT }

private data class Destination(val route: String, val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector)

private val destinations = listOf(
    Destination("home", "Beranda", Icons.Outlined.Home),
    Destination("budget", "Budget", Icons.Outlined.AccountBalanceWallet),
    Destination("activity", "Transaksi", Icons.AutoMirrored.Outlined.ReceiptLong),
    Destination("reports", "Laporan", Icons.Outlined.Assessment),
    Destination("settings", "Pengaturan", Icons.Outlined.Settings),
)

@Composable
fun KronApp(viewModel: MainViewModel, activity: FragmentActivity) {
    val state by viewModel.uiState.collectAsState()
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(state.onboardingComplete) {
        if (state.onboardingComplete && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
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
            OnboardingScreen(viewModel::completeOnboarding)
            return@KronTheme
        }
        var locked by rememberSaveable { mutableStateOf(false) }
        var backgroundAt by remember { mutableLongStateOf(0L) }
        var lockError by remember { mutableStateOf<String?>(null) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val authenticate = remember(activity) {
            {
                val executor = ContextCompat.getMainExecutor(activity)
                val prompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        locked = false
                        lockError = null
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        lockError = errString.toString()
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
                    Lifecycle.Event.ON_START -> if (state.appLockEnabled && backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt >= 60_000) {
                        locked = true
                        viewModel.hideValuesForLock()
                        authenticate()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
        if (locked) {
            LockScreen(lockError, authenticate)
        } else {
            MainScaffold(state, viewModel)
        }
    }
}

@Composable
private fun MainScaffold(state: KronUiState, viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination?.route ?: "home"
    val snackbar = remember { SnackbarHostState() }
    var dialog by remember { mutableStateOf<ActionDialog?>(null) }
    var auditId by remember { mutableStateOf<String?>(null) }
    var detailPeriodId by remember { mutableStateOf<Long?>(null) }
    var passwordMode by remember { mutableStateOf<String?>(null) }
    var pendingPassword by remember { mutableStateOf<CharArray?>(null) }
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
    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.clearMessage()
        }
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
        floatingActionButton = {
            if (current != "settings") FloatingActionButton(onClick = { dialog = ActionDialog.EXPENSE }) {
                Icon(Icons.Outlined.Add, contentDescription = "Tambah transaksi")
            }
        },
        contentWindowInsets = WindowInsets.systemBars,
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") {
                HomeScreen(
                    state,
                    viewModel::toggleValues,
                    { dialog = ActionDialog.INCOME },
                    { dialog = ActionDialog.EXPENSE },
                    { dialog = ActionDialog.TRANSFER },
                    { dialog = ActionDialog.RESOLVE },
                    { navController.navigate("activity") },
                )
            }
            composable("budget") {
                BudgetScreen(state, { dialog = ActionDialog.PORTFOLIO }, { dialog = ActionDialog.RESOLVE }, viewModel::fundPeriod, { dialog = ActionDialog.CHANNEL_TRANSFER }, viewModel::releaseRolloverToVault, { detailPeriodId = it })
            }
            composable("activity") { ActivityScreen(state, { auditId = it }) }
            composable("reports") { ReportsScreen(state, onExport = { reportLauncher.launch("KRON-laporan-${LocalDate.now()}.csv") }) }
            composable("settings") {
                SettingsScreen(
                    state,
                    { dialog = ActionDialog.ACCOUNT },
                    viewModel::setRememberVisibility,
                    viewModel::setAppLock,
                    viewModel::setTheme,
                    { passwordMode = "BACKUP" },
                    { passwordMode = "RESTORE" },
                )
            }
        }
    }
    when (dialog) {
        ActionDialog.INCOME -> IncomeDialog(state, { dialog = null }) { account, amount, category, title, note, recurring, startDate, endDate, interval, recordNow -> dialog = null; viewModel.addIncome(account, amount, category, title, note, recurring, startDate, endDate, interval, recordNow) }
        ActionDialog.EXPENSE -> ExpenseDialog(state, { dialog = null }) { account, amount, splits, title, note, recurring, startDate, endDate, interval, recordNow -> dialog = null; viewModel.addExpense(account, amount, splits, title, note, recurring, startDate, endDate, interval, recordNow) }
        ActionDialog.TRANSFER -> TransferDialog(state, { dialog = null }) { from, to, amount, note -> dialog = null; viewModel.transfer(from, to, amount, note) }
        ActionDialog.PORTFOLIO -> PortfolioDialog(state, { dialog = null }) { name, cadence, income, rollover, drafts, startDate, endDate, interval -> dialog = null; viewModel.createPortfolio(name, cadence, income, rollover, drafts, startDate, endDate, interval) }
        ActionDialog.RESOLVE -> ResolveDialog(state, { dialog = null }, { source, target, amount, note -> dialog = null; viewModel.resolveFromAllocation(source, target, amount, note) }, { target, amount, note -> dialog = null; viewModel.resolveFromVault(target, amount, note) }, { target, amount, note -> dialog = null; viewModel.resolveFromRollover(target, amount, note) }, { target, amount, note -> dialog = null; viewModel.allocateUnallocated(target, amount, note) })
        ActionDialog.CHANNEL_TRANSFER -> ChannelTransferDialog(state, { dialog = null }) { allocation, from, to, amount, note -> dialog = null; viewModel.transferBookedChannel(allocation, from, to, amount, note) }
        ActionDialog.ACCOUNT -> AccountDialog({ dialog = null }) { name, type, channel, opening -> dialog = null; viewModel.addAccount(name, type, channel, opening) }
        null -> Unit
    }
    auditId?.let { id -> state.activities.firstOrNull { it.id == id }?.let { event -> AuditDialog(event, state, { auditId = null }) { eventId, reason -> auditId = null; viewModel.reverseEvent(eventId, reason) } } }
    detailPeriodId?.let { periodId -> BudgetDetailDialog(state, periodId, { detailPeriodId = null }, viewModel::correctAllocation) }
    passwordMode?.let { mode ->
        PasswordDialog(mode == "BACKUP", { passwordMode = null }) { password ->
            passwordMode = null
            pendingPassword = password.toCharArray()
            if (mode == "BACKUP") backupLauncher.launch("KRON-${LocalDate.now()}.kronbackup")
            else restoreLauncher.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
        }
    }
}

@Composable
private fun LockScreen(error: String?, onUnlock: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
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
                Spacer(Modifier.height(if (error == null) 22.dp else 14.dp))
                Button(
                    onClick = onUnlock,
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isBackup) "Enkripsi backup" else "Buka backup") },
        text = { OutlinedTextField(password, { password = it }, label = { Text("Password minimal 8 karakter") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Button(onClick = { onConfirm(password) }, enabled = password.length >= 8) { Text(if (isBackup) "Pilih lokasi" else "Pilih file") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } },
    )
}
