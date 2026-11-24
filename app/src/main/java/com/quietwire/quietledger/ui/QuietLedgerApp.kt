package com.quietwire.quietledger.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import com.quietwire.quietledger.ui.backup.AutoBackupViewModel
import com.quietwire.quietledger.ui.assets.AssetSection
import com.quietwire.quietledger.ui.assets.AssetsScaffold
import com.quietwire.quietledger.ui.investments.ImportResultDialog
import com.quietwire.quietledger.ui.investments.InvestmentsScreen
import com.quietwire.quietledger.ui.investments.InvestmentsViewModel
import com.quietwire.quietledger.ui.metals.MetalsScreen
import com.quietwire.quietledger.ui.metals.MetalsViewModel
import com.quietwire.quietledger.ui.metals.SpotPriceDialog
import androidx.compose.material.icons.filled.Star
import com.quietwire.quietledger.ui.savings.MovementDialog
import com.quietwire.quietledger.ui.savings.SavingsEditorDialog
import com.quietwire.quietledger.ui.savings.SavingsScreen
import com.quietwire.quietledger.ui.savings.SavingsViewModel
import androidx.compose.material.icons.filled.Refresh
import com.quietwire.quietledger.ui.expenses.ExpenseEditorDialog
import com.quietwire.quietledger.ui.expenses.ExpensesScreen
import com.quietwire.quietledger.ui.expenses.ExpensesViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.quietwire.quietledger.core.biometric.BiometricGate
import com.quietwire.quietledger.ui.security.EnrolmentStep
import com.quietwire.quietledger.ui.security.RecoveryCodesDialog
import com.quietwire.quietledger.ui.security.AboutScreen
import com.quietwire.quietledger.ui.security.AboutViewModel
import com.quietwire.quietledger.ui.security.SecurityScreen
import com.quietwire.quietledger.ui.security.SecurityViewModel
import com.quietwire.quietledger.ui.security.TotpEnrolmentDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.quietwire.quietledger.ui.backup.BackupDialog
import com.quietwire.quietledger.ui.backup.BackupResultDialog
import com.quietwire.quietledger.ui.backup.BackupUiState
import com.quietwire.quietledger.ui.backup.BackupViewModel
import com.quietwire.quietledger.ui.backup.ConfirmImportDialog
import com.quietwire.quietledger.ui.backup.ExportPassphraseDialog
import com.quietwire.quietledger.ui.backup.ImportPassphraseDialog
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.quietwire.quietledger.ui.dashboard.DashboardScreen
import com.quietwire.quietledger.ui.dashboard.DashboardViewModel
import com.quietwire.quietledger.ui.debts.DebtDetailPane
import com.quietwire.quietledger.ui.debts.DebtEditorDialog
import com.quietwire.quietledger.ui.debts.DebtsScreen
import com.quietwire.quietledger.ui.debts.DebtsViewModel
import com.quietwire.quietledger.ui.payoff.PayoffScreen
import com.quietwire.quietledger.ui.payoff.PayoffViewModel

private enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    DASHBOARD("dashboard", "Overview", Icons.Default.Home),
    DEBTS("debts", "Debts", Icons.AutoMirrored.Filled.List),
    EXPENSES("expenses", "Expenses", Icons.Default.Refresh),
    ASSETS("assets", "Assets", Icons.Default.Star),
    PAYOFF("payoff", "Payoff", Icons.Default.DateRange)
}

/**
 * Navigation moves to the side once there is width for it, which is what the
 * unfolded inner screen gives you. Folded, it stays at the bottom where a
 * thumb can reach.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuietLedgerApp(widthSizeClass: WindowWidthSizeClass, onLock: () -> Unit = {}) {
    val navController = rememberNavController()
    val backupViewModel: BackupViewModel = hiltViewModel()
    val backupUi by backupViewModel.ui.collectAsStateWithLifecycle()

    // Storage Access Framework, so the file can land in Proton Drive, Google
    // Drive or local storage without this app integrating with any of them.
    var pendingExportPassphrase by remember { mutableStateOf("") }
    val createDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(BackupViewModel.EXPORT_MIME)
    ) { uri ->
        if (uri != null) backupViewModel.writeExport(uri, pendingExportPassphrase)
        else backupViewModel.dismiss()
        pendingExportPassphrase = ""
    }
    val openDocument = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) backupViewModel.startImport(uri) }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val useSideNavigation = widthSizeClass != WindowWidthSizeClass.Compact

    fun navigateTo(destination: Destination) {
        navController.navigate(destination.route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun isSelected(destination: Destination) =
        currentDestination?.hierarchy?.any { it.route == destination.route } == true

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(currentTitle(currentDestination?.route)) },
                navigationIcon = {
                    // Security and About are reached from the menu rather than
                    // the bottom bar, so nothing on screen offered a way out of
                    // them. Closing the app was the only exit, which is as
                    // close to a trap as a navigation bug gets.
                    if (currentDestination?.route in MENU_ROUTES) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back"
                            )
                        }
                    }
                },
                actions = {
                    var menuOpen by remember { mutableStateOf(false) }
                    IconButton(onClick = onLock) {
                        Icon(Icons.Default.Lock, contentDescription = "Lock")
                    }
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Security") },
                            onClick = {
                                menuOpen = false
                                navController.navigate(SECURITY_ROUTE) {
                                    launchSingleTop = true
                                }
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Back up...") },
                            onClick = {
                                menuOpen = false
                                backupViewModel.startExport()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Restore...") },
                            onClick = {
                                menuOpen = false
                                openDocument.launch(arrayOf(BackupViewModel.PICKER_MIME))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("About") },
                            onClick = {
                                menuOpen = false
                                navController.navigate(ABOUT_ROUTE) {
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                }
            )
        },
        bottomBar = {
            if (!useSideNavigation) {
                NavigationBar {
                    Destination.entries.forEach { destination ->
                        NavigationBarItem(
                            selected = isSelected(destination),
                            onClick = { navigateTo(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (useSideNavigation) {
                // Material3 defaults the rail to `surface`, which is the same
                // colour as the screen behind it, so the items read as icons
                // floating in empty space rather than a navigation region.
                // The bottom bar on compact widths does not have this problem
                // because it gets its own container colour.
                NavigationRail(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer
                ) {
                    Destination.entries.forEach { destination ->
                        NavigationRailItem(
                            selected = isSelected(destination),
                            onClick = { navigateTo(destination) },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label) }
                        )
                    }
                }
            }

            NavHost(
                navController = navController,
                startDestination = Destination.DASHBOARD.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Destination.DASHBOARD.route) {
                    val viewModel: DashboardViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    DashboardScreen(state)
                }

                composable(Destination.DEBTS.route) {
                    val viewModel: DebtsViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val editor by viewModel.editor.collectAsStateWithLifecycle()
                    val history by viewModel.history.collectAsStateWithLifecycle()

                    val deleteSelected: () -> Unit = {
                        editor?.let { viewModel.delete(it.id) }
                        viewModel.cancelEditing()
                    }

                    if (useSideNavigation) {
                        // Wide enough for two columns, so the form goes in a
                        // pane instead of a dialog. Uses the width, and a pane
                        // does not have to fight the keyboard for space.
                        Row(Modifier.fillMaxSize()) {
                            DebtsScreen(
                                state = state,
                                onAdd = viewModel::startAdding,
                                onEdit = viewModel::startEditing,
                                selectedId = editor?.id?.takeIf { it != 0L },
                                modifier = Modifier.weight(0.45f)
                            )
                            VerticalDivider()
                            DebtDetailPane(
                                state = editor,
                                history = history,
                                onChange = viewModel::updateEditor,
                                onSave = viewModel::save,
                                onCancel = viewModel::cancelEditing,
                                onDelete = deleteSelected,
                                modifier = Modifier.weight(0.55f)
                            )
                        }
                    } else {
                        DebtsScreen(
                            state = state,
                            onAdd = viewModel::startAdding,
                            onEdit = viewModel::startEditing
                        )

                        editor?.let { form ->
                            DebtEditorDialog(
                                state = form,
                                onChange = viewModel::updateEditor,
                                onSave = viewModel::save,
                                onCancel = viewModel::cancelEditing,
                                onDelete = if (form.isEditing) deleteSelected else null
                            )
                        }
                    }
                }

                composable(ABOUT_ROUTE) {
                    val aboutViewModel: AboutViewModel = hiltViewModel()
                    val about by aboutViewModel.uiState.collectAsStateWithLifecycle()
                    AboutScreen(state = about, onRefresh = aboutViewModel::refresh)
                }

                composable(SECURITY_ROUTE) {
                    val viewModel: SecurityViewModel = hiltViewModel()
                    val autoBackupViewModel: AutoBackupViewModel = hiltViewModel()
                    val state by viewModel.ui.collectAsStateWithLifecycle()
                    val context = LocalContext.current

                    LaunchedEffect(Unit) {
                        viewModel.refresh(BiometricGate.availability(context))
                        // Refreshed on arrival, so a backup written while the
                        // screen was away is reflected rather than stale.
                        autoBackupViewModel.refresh()
                    }

                    SecurityScreen(
                        state = state,
                        onBiometricChange = viewModel::setBiometricRequired,
                        onSetUpAuthenticator = viewModel::beginEnrolment,
                        onDisableAuthenticator = viewModel::disableTotp,
                        autoBackup = autoBackupViewModel
                    )

                    when (val step = state.enrolment) {
                        is EnrolmentStep.ShowSecret -> TotpEnrolmentDialog(
                            step = step,
                            sampleCode = state.currentSampleCode,
                            secondsRemaining = state.secondsRemaining,
                            onCodeChange = viewModel::updateEnteredCode,
                            onConfirm = viewModel::confirmEnrolment,
                            onCancel = viewModel::cancelEnrolment
                        )
                        is EnrolmentStep.ShowRecoveryCodes -> RecoveryCodesDialog(
                            codes = step.codes,
                            onDone = viewModel::finishRecoveryCodes
                        )
                        EnrolmentStep.NotEnrolling -> Unit
                    }
                }

                composable(Destination.EXPENSES.route) {
                    val viewModel: ExpensesViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    val editor by viewModel.editor.collectAsStateWithLifecycle()

                    ExpensesScreen(
                        state = state,
                        onAdd = viewModel::startAdding,
                        onEdit = viewModel::startEditing
                    )

                    editor?.let { form ->
                        ExpenseEditorDialog(
                            state = form,
                            onChange = viewModel::updateEditor,
                            onSave = viewModel::save,
                            onCancel = viewModel::cancelEditing,
                            onDelete = if (form.isEditing) {
                                { viewModel.delete(form.id) }
                            } else {
                                null
                            },
                            onEnd = if (form.isEditing) {
                                {
                                    viewModel.end(form.id)
                                    viewModel.cancelEditing()
                                }
                            } else {
                                null
                            }
                        )
                    }
                }

                composable(Destination.ASSETS.route) {
                    val savingsViewModel: SavingsViewModel = hiltViewModel()
                    val investmentsViewModel: InvestmentsViewModel = hiltViewModel()
                    val metalsViewModel: MetalsViewModel = hiltViewModel()

                    val savingsState by savingsViewModel.uiState.collectAsStateWithLifecycle()
                    val investmentsState by investmentsViewModel.uiState.collectAsStateWithLifecycle()
                    val editor by savingsViewModel.editor.collectAsStateWithLifecycle()
                    val movement by savingsViewModel.movement.collectAsStateWithLifecycle()
                    val importState by investmentsViewModel.importState.collectAsStateWithLifecycle()
                    val metalsState by metalsViewModel.uiState.collectAsStateWithLifecycle()
                    val spotEditor by metalsViewModel.spotEditor.collectAsStateWithLifecycle()

                    var section by remember { mutableStateOf(AssetSection.SAVINGS) }

                    val pickPositions = rememberLauncherForActivityResult(
                        ActivityResultContracts.OpenDocument()
                    ) { uri -> if (uri != null) investmentsViewModel.importFrom(uri) }

                    AssetsScaffold(
                        selected = section,
                        onSelect = { section = it },
                        savingsTotal = savingsState.total,
                        retirementTotal = investmentsState.total,
                        // Market value once every held metal has a spot price,
                        // and cost until then. The Overview already did this,
                        // and the two showing different figures for the same
                        // holding is worse than either being wrong on its own.
                        metalsTotal = metalsState.marketValue ?: metalsState.totalCost
                    ) { current ->
                        when (current) {
                            AssetSection.SAVINGS -> SavingsScreen(
                                state = savingsState,
                                onAdd = savingsViewModel::startAdding,
                                onEdit = savingsViewModel::startEditing,
                                onDeposit = { savingsViewModel.startMovement(it, deposit = true) },
                                onWithdraw = { savingsViewModel.startMovement(it, deposit = false) }
                            )

                            AssetSection.RETIREMENT -> InvestmentsScreen(
                                state = investmentsState,
                                onImport = {
                                    pickPositions.launch(investmentsViewModel.pickerTypes)
                                }
                            )

                            AssetSection.METALS -> MetalsScreen(
                                state = metalsState,
                                onEditSpotPrices = metalsViewModel::startEditingSpotPrices
                            )
                        }
                    }

                    editor?.let { form ->
                        SavingsEditorDialog(
                            state = form,
                            onChange = savingsViewModel::updateEditor,
                            onSave = savingsViewModel::save,
                            onCancel = savingsViewModel::cancelEditing,
                            onDelete = if (form.isEditing) {
                                { savingsViewModel.delete(form.id) }
                            } else {
                                null
                            },
                            onClose = if (form.isEditing) {
                                {
                                    savingsViewModel.close(form.id)
                                    savingsViewModel.cancelEditing()
                                }
                            } else {
                                null
                            }
                        )
                    }

                    movement?.let { move ->
                        MovementDialog(
                            state = move,
                            onChange = savingsViewModel::updateMovement,
                            onConfirm = savingsViewModel::confirmMovement,
                            onCancel = savingsViewModel::cancelMovement
                        )
                    }

                    ImportResultDialog(
                        state = importState,
                        onDismiss = investmentsViewModel::dismissImport
                    )

                    spotEditor?.let { form ->
                        SpotPriceDialog(
                            state = form,
                            heldMetals = metalsState.summaries.map { it.metal }.toSet(),
                            onChange = metalsViewModel::updateSpotEditor,
                            onSave = metalsViewModel::saveSpotPrices,
                            onCancel = metalsViewModel::cancelSpotEditing
                        )
                    }
                }

                composable(Destination.PAYOFF.route) {
                    val viewModel: PayoffViewModel = hiltViewModel()
                    val state by viewModel.uiState.collectAsStateWithLifecycle()
                    PayoffScreen(
                        state = state,
                        onExtraPaymentChange = viewModel::setExtraPayment,
                        onRolloverChange = viewModel::setRolloverEnabled
                    )
                }
            }
        }

        // Outside the NavHost, so backup and restore stay available and keep
        // their state regardless of which screen is showing.
        BackupFlow(
            state = backupUi,
            viewModel = backupViewModel,
            onPickExportLocation = { passphrase ->
                pendingExportPassphrase = passphrase
                createDocument.launch(backupViewModel.suggestedFileName())
            }
        )
    }
}

@Composable
private fun BackupFlow(
    state: BackupUiState,
    viewModel: BackupViewModel,
    onPickExportLocation: (String) -> Unit
) {
    when (val dialog = state.dialog) {
        BackupDialog.None -> {
            val message = state.error ?: state.message
            if (message != null) {
                BackupResultDialog(
                    message = message,
                    isError = state.error != null,
                    onDismiss = viewModel::dismiss
                )
            }
        }

        BackupDialog.ExportPassphrase -> ExportPassphraseDialog(
            busy = state.busy,
            error = state.error,
            onConfirm = onPickExportLocation,
            onDismiss = viewModel::dismiss
        )

        is BackupDialog.ImportPassphrase -> ImportPassphraseDialog(
            busy = state.busy,
            error = state.error,
            onConfirm = { viewModel.inspectImport(dialog.uri, it) },
            onDismiss = viewModel::dismiss
        )

        is BackupDialog.ConfirmImport -> ConfirmImportDialog(
            summary = dialog.summary,
            busy = state.busy,
            onConfirm = { viewModel.applyImport(dialog.uri, dialog.passphrase) },
            onDismiss = viewModel::dismiss
        )
    }
}

internal const val SECURITY_ROUTE = "security"
internal const val ABOUT_ROUTE = "about"

/**
 * Destinations opened from the overflow menu.
 *
 * These sit outside the bottom bar, so they need a back arrow of their own;
 * every other destination is always one tap away.
 */
private val MENU_ROUTES = setOf(SECURITY_ROUTE, ABOUT_ROUTE)

private fun currentTitle(route: String?): String =
    Destination.entries.firstOrNull { it.route == route }?.label
        // Routes reached from the menu rather than the bottom bar, so they are
        // not Destinations but still need a title of their own.
        ?: when (route) {
            SECURITY_ROUTE -> "Security"
            ABOUT_ROUTE -> "About"
            else -> "QuietLedger"
        }
