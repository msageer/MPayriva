package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.auth.authStateFlow
import com.example.auth.signOutUser
import com.example.data.model.ServiceType
import com.example.data.model.TransactionRecord
import com.example.data.remote.TrustedBackendClient
import com.example.data.repository.FintechRepository
import com.example.notifications.FintechNotificationManager
import com.example.ui.components.InlineStatusBanner
import com.example.ui.screens.AccountRegistrationScreen
import com.example.ui.screens.AdminDashboardScreen
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.HomeDashboardScreen
import com.example.ui.screens.NotificationsScreen
import com.example.ui.screens.ProfileScreen
import com.example.ui.screens.SupportScreen
import com.example.ui.screens.TransactionReceiptDialog
import com.example.ui.screens.TransactionsScreen
import com.example.ui.screens.UtilityServicesScreen
import com.example.ui.screens.WalletScreen
import com.example.ui.theme.MSageerFintechTheme
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.FintechViewModel
import com.example.viewmodel.UiState
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FintechNotificationManager.ensureChannel(applicationContext)
        enableEdgeToEdge()
        setContent {
            MSageerFintechTheme {
                AppNavigation()
            }
        }
    }
}

private enum class MainTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    HOME("Home", Icons.Filled.Home, Icons.Outlined.Home),
    SERVICES("Services", Icons.Filled.GridView, Icons.Outlined.GridView),
    TRANSACTIONS("History", Icons.Filled.ReceiptLong, Icons.Outlined.ReceiptLong),
    WALLET("Wallet", Icons.Filled.AccountBalanceWallet, Icons.Outlined.AccountBalanceWallet),
    PROFILE("Profile", Icons.Filled.Person, Icons.Outlined.Person)
}

private sealed interface OverlayRoute {
    data object None : OverlayRoute
    data class Support(val prefilledReference: String? = null) : OverlayRoute
    data object Notifications : OverlayRoute
    data object Admin : OverlayRoute
}

@Composable
fun AppNavigation() {
    val auth = Firebase.auth
    val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
    val user = currentUser

    if (user == null) {
        AuthScreen(onAuthSuccess = { /* Reactive authStateFlow transitions automatically */ })
    } else {
        key(user.uid) {
            AuthenticatedFintechShell(
                currentUserId = user.uid,
                userEmail = user.email.orEmpty(),
                userDisplayName = user.displayName.orEmpty()
            )
        }
    }
}

@Composable
private fun AuthenticatedFintechShell(
    currentUserId: String,
    userEmail: String,
    userDisplayName: String
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember(context) { CredentialManager.create(context) }

    val fintechViewModel: FintechViewModel = viewModel(
        key = "fintech_$currentUserId",
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                val dbId = app.getString(R.string.firestore_database_id)
                val db = FirebaseFirestore.getInstance(dbId)
                FintechViewModel(
                    repository = FintechRepository(db),
                    backendClient = TrustedBackendClient(),
                    currentUserId = currentUserId
                )
            }
        }
    )

    val profileState by fintechViewModel.userProfileState.collectAsStateWithLifecycle()
    val transactionsState by fintechViewModel.transactionsState.collectAsStateWithLifecycle()
    val ledgerState by fintechViewModel.ledgerState.collectAsStateWithLifecycle()
    val catalogState by fintechViewModel.catalogState.collectAsStateWithLifecycle()
    val ticketsState by fintechViewModel.supportTicketsState.collectAsStateWithLifecycle()
    val notificationsState by fintechViewModel.notificationsState.collectAsStateWithLifecycle()
    val platformConfig by fintechViewModel.platformConfig.collectAsStateWithLifecycle()
    val balanceVisible by fintechViewModel.balanceVisible.collectAsStateWithLifecycle()
    val isBackendConfigured by fintechViewModel.isBackendConfigured.collectAsStateWithLifecycle()
    val adminResolutionState by fintechViewModel.adminResolutionState.collectAsStateWithLifecycle()
    val isAdmin by fintechViewModel.isAdmin.collectAsStateWithLifecycle()
    val actionBanner by fintechViewModel.actionBanner.collectAsStateWithLifecycle()
    val isSubmitting by fintechViewModel.isSubmitting.collectAsStateWithLifecycle()
    val verifiedCustomer by fintechViewModel.verifiedCustomer.collectAsStateWithLifecycle()
    val lastCardCheckout by fintechViewModel.lastCardCheckout.collectAsStateWithLifecycle()
    val liveCatalogState by fintechViewModel.liveCatalogState.collectAsStateWithLifecycle()
    val lastVoucherReceipt by fintechViewModel.lastVoucherReceipt.collectAsStateWithLifecycle()

    var adminViewingCustomerApp by remember(currentUserId) { mutableStateOf(false) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { /* Permission result handled gracefully by FintechNotificationManager */ }

    LaunchedEffect(currentUserId) {
        FintechNotificationManager.ensureChannel(context.applicationContext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !FintechNotificationManager.hasNotificationPermission(context)
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val loadedProfile = (profileState as? UiState.Success)?.data
    LaunchedEffect(currentUserId, loadedProfile != null) {
        if (loadedProfile != null) {
            val token = FintechNotificationManager.fetchCurrentFcmToken()
            if (!token.isNullOrBlank() && token != loadedProfile.fcmToken) {
                fintechViewModel.syncFcmToken(token)
            }
        }
    }

    var notificationsInitialized by remember(currentUserId) { mutableStateOf(false) }
    val seenNotificationIds = remember(currentUserId) { mutableSetOf<String>() }
    LaunchedEffect(notificationsState) {
        val currentList = (notificationsState as? UiState.Success)?.data ?: return@LaunchedEffect
        if (!notificationsInitialized) {
            currentList.forEach { n ->
                seenNotificationIds.add(n.id.ifBlank { "${n.reference}_${n.title}_${n.createdAt?.seconds ?: 0L}" })
            }
            notificationsInitialized = true
        } else {
            currentList.forEach { n ->
                val notifKey = n.id.ifBlank { "${n.reference}_${n.title}_${n.createdAt?.seconds ?: 0L}" }
                if (seenNotificationIds.add(notifKey) && !n.read) {
                    FintechNotificationManager.showTransactionNotification(
                        context = context.applicationContext,
                        title = n.title,
                        body = n.body,
                        notificationKey = notifKey
                    )
                }
            }
        }
    }

    val performSignOut: () -> Unit = {
        signOutUser(credentialManager = credentialManager, scope = scope)
    }

    if (adminResolutionState == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center
        ) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    if (adminResolutionState == true && !adminViewingCustomerApp) {
        val adminVm: AdminViewModel = viewModel(
            key = "admin_$currentUserId",
            factory = viewModelFactory {
                initializer {
                    val app = checkNotNull(this[APPLICATION_KEY])
                    val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                    AdminViewModel(FintechRepository(db), TrustedBackendClient(), adminUserId = currentUserId)
                }
            }
        )
        AdminDashboardScreen(
            adminViewModel = adminVm,
            onBack = { adminViewingCustomerApp = true },
            adminEmail = userEmail,
            isPrimaryAdminHome = true,
            onSwitchToCustomerView = { adminViewingCustomerApp = true },
            onSignOut = performSignOut
        )
        return
    }

    when (val state = profileState) {
        UiState.Loading -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }

        is UiState.Error -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    InlineStatusBanner(
                        message = state.message,
                        isError = true,
                        onDismiss = {}
                    )
                }
            }
        }

        is UiState.Success -> {
            val profile = state.data
            if (profile == null) {
                if (isAdmin) {
                    BackHandler { adminViewingCustomerApp = false }
                }
                AccountRegistrationScreen(
                    initialEmail = userEmail,
                    initialName = userDisplayName,
                    isSubmitting = isSubmitting,
                    bannerMessage = actionBanner?.message,
                    bannerIsError = actionBanner?.isError == true,
                    onDismissBanner = fintechViewModel::clearBanner,
                    onSignOut = performSignOut,
                    onReturnToAdmin = if (isAdmin) { { adminViewingCustomerApp = false } } else null,
                    onSubmitRegistration = { type, name, phone, bizName, contact, address, category, pin ->
                        fintechViewModel.completeRegistration(
                            accountType = type,
                            fullName = name,
                            phoneNumber = phone,
                            businessName = bizName,
                            contactPerson = contact,
                            businessAddress = address,
                            businessCategory = category,
                            pin4Digits = pin
                        )
                    }
                )
            } else {
                var activeTab by remember { mutableStateOf(MainTab.HOME) }
                var selectedService by remember { mutableStateOf(ServiceType.AIRTIME) }
                var overlayRoute by remember { mutableStateOf<OverlayRoute>(OverlayRoute.None) }
                var selectedReceiptTx by remember { mutableStateOf<TransactionRecord?>(null) }

                val transactions = (transactionsState as? UiState.Success)?.data.orEmpty()
                val ledger = (ledgerState as? UiState.Success)?.data.orEmpty()
                val catalog = (catalogState as? UiState.Success)?.data.orEmpty()
                val tickets = (ticketsState as? UiState.Success)?.data.orEmpty()
                val notifications = (notificationsState as? UiState.Success)?.data.orEmpty()
                val unreadCount = notifications.count { !it.read }

                when (val overlay = overlayRoute) {
                    is OverlayRoute.Support -> {
                        SupportScreen(
                            prefilledReference = overlay.prefilledReference,
                            tickets = tickets,
                            isSubmitting = isSubmitting,
                            onBack = { overlayRoute = OverlayRoute.None },
                            onSubmitTicket = { cat, subj, msg, ref, onDone ->
                                fintechViewModel.submitSupportTicket(cat, subj, msg, ref, onDone)
                            }
                        )
                    }

                    OverlayRoute.Notifications -> {
                        NotificationsScreen(
                            notifications = notifications,
                            onMarkRead = fintechViewModel::markNotificationRead,
                            onBack = { overlayRoute = OverlayRoute.None }
                        )
                    }

                    OverlayRoute.Admin -> {
                        if (isAdmin) {
                            val adminVm: AdminViewModel = viewModel(
                                key = "admin_$currentUserId",
                                factory = viewModelFactory {
                                    initializer {
                                        val app = checkNotNull(this[APPLICATION_KEY])
                                        val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                                        AdminViewModel(FintechRepository(db), TrustedBackendClient(), adminUserId = currentUserId)
                                    }
                                }
                            )
                            AdminDashboardScreen(
                                adminViewModel = adminVm,
                                onBack = { overlayRoute = OverlayRoute.None },
                                adminEmail = userEmail,
                                isPrimaryAdminHome = false,
                                onSignOut = performSignOut
                            )
                        } else {
                            overlayRoute = OverlayRoute.None
                        }
                    }

                    OverlayRoute.None -> {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val useNavRail = maxWidth >= 700.dp

                            Scaffold(
                                bottomBar = {
                                    if (!useNavRail) {
                                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                                            MainTab.entries.forEach { tab ->
                                                val selected = activeTab == tab
                                                NavigationBarItem(
                                                    selected = selected,
                                                    onClick = { activeTab = tab },
                                                    icon = {
                                                        Icon(
                                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                            contentDescription = tab.label
                                                        )
                                                    },
                                                    label = { Text(tab.label, maxLines = 1) },
                                                    modifier = Modifier.testTag("nav_${tab.name.lowercase()}")
                                                )
                                            }
                                        }
                                    }
                                }
                            ) { innerPadding ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(innerPadding)
                                ) {
                                    if (useNavRail) {
                                        NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                                            MainTab.entries.forEach { tab ->
                                                val selected = activeTab == tab
                                                NavigationRailItem(
                                                    selected = selected,
                                                    onClick = { activeTab = tab },
                                                    icon = {
                                                        Icon(
                                                            imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                                            contentDescription = tab.label
                                                        )
                                                    },
                                                    label = { Text(tab.label) }
                                                )
                                            }
                                        }
                                    }

                                    Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.TopCenter
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .widthIn(max = 760.dp)
                                        ) {
                                            if (actionBanner != null) {
                                                InlineStatusBanner(
                                                    message = actionBanner!!.message,
                                                    isError = actionBanner!!.isError,
                                                    onDismiss = fintechViewModel::clearBanner,
                                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                                )
                                            }

                                            when (activeTab) {
                                                MainTab.HOME -> HomeDashboardScreen(
                                                    profile = profile,
                                                    platformConfig = platformConfig,
                                                    balanceVisible = balanceVisible,
                                                    isAdmin = isAdmin,
                                                    isBackendConfigured = isBackendConfigured,
                                                    unreadNotificationCount = unreadCount,
                                                    recentTransactions = transactions,
                                                    onToggleBalanceVisibility = fintechViewModel::toggleBalanceVisibility,
                                                    onFundWalletClick = { activeTab = MainTab.WALLET },
                                                    onServiceClick = { svc ->
                                                        selectedService = svc
                                                        activeTab = MainTab.SERVICES
                                                    },
                                                    onViewAllTransactions = { activeTab = MainTab.TRANSACTIONS },
                                                    onTransactionClick = { selectedReceiptTx = it },
                                                    onNotificationsClick = { overlayRoute = OverlayRoute.Notifications },
                                                    onSupportClick = { overlayRoute = OverlayRoute.Support() },
                                                    onAdminClick = {
                                                        if (isAdmin) {
                                                            adminViewingCustomerApp = false
                                                        }
                                                    }
                                                )

                                                MainTab.SERVICES -> UtilityServicesScreen(
                                                    initialService = selectedService,
                                                    profile = profile,
                                                    catalog = catalog,
                                                    liveCatalogState = liveCatalogState,
                                                    verifiedCustomer = verifiedCustomer,
                                                    isSubmitting = isSubmitting,
                                                    onRefreshLiveCatalog = { fintechViewModel.refreshLiveCatalog(force = true) },
                                                    onClearVerification = fintechViewModel::clearVerification,
                                                    onVerifyMeter = fintechViewModel::verifyElectricityMeter,
                                                    onVerifySmartCard = fintechViewModel::verifyCableSmartCard,
                                                    onVerifyBetting = fintechViewModel::verifyBettingCustomer,
                                                    onVerifySmile = fintechViewModel::verifySmileAccount,
                                                    onAuthorizePurchase = { svcType, provCode, prodCode, prodTitle, rec, amt, mType, qty, pin, idemKey, done ->
                                                        fintechViewModel.authorizeAndSubmitPurchase(
                                                            serviceType = svcType,
                                                            providerCode = provCode,
                                                            productCode = prodCode,
                                                            productTitle = prodTitle,
                                                            recipient = rec,
                                                            amountKobo = amt,
                                                            meterType = mType,
                                                            quantity = qty,
                                                            enteredPin = pin,
                                                            idempotencyKey = idemKey
                                                        ) { done() }
                                                    }
                                                )

                                                MainTab.TRANSACTIONS -> TransactionsScreen(
                                                    transactions = transactions,
                                                    onTransactionClick = { selectedReceiptTx = it }
                                                )

                                                MainTab.WALLET -> WalletScreen(
                                                    profile = profile,
                                                    balanceVisible = balanceVisible,
                                                    ledgerEntries = ledger,
                                                    lastCardCheckout = lastCardCheckout,
                                                    isSubmitting = isSubmitting,
                                                    onRequestVirtualAccount = fintechViewModel::requestDedicatedVirtualAccount,
                                                    onSyncBankDeposits = fintechViewModel::syncBankDeposits,
                                                    onInitiateCardFunding = fintechViewModel::initiateCardWalletFunding,
                                                    onVerifyCardFunding = fintechViewModel::verifyCardFunding,
                                                    onClearCardCheckout = fintechViewModel::clearCardCheckout
                                                )

                                                MainTab.PROFILE -> ProfileScreen(
                                                    profile = profile,
                                                    isAdmin = isAdmin,
                                                    isSubmitting = isSubmitting,
                                                    onSaveProfile = fintechViewModel::updateProfile,
                                                    onChangePin = fintechViewModel::changePin,
                                                    onOpenSupport = { overlayRoute = OverlayRoute.Support() },
                                                    onOpenNotifications = { overlayRoute = OverlayRoute.Notifications },
                                                    onOpenAdmin = {
                                                        if (isAdmin) {
                                                            adminViewingCustomerApp = false
                                                        }
                                                    },
                                                    onSignOut = performSignOut
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        val activeReceiptTx = selectedReceiptTx ?: lastVoucherReceipt
                        activeReceiptTx?.let { tx ->
                            TransactionReceiptDialog(
                                transaction = tx,
                                isSubmitting = isSubmitting,
                                businessHeader = profile.displayTitle(),
                                onDismiss = {
                                    selectedReceiptTx = null
                                    fintechViewModel.dismissLastVoucherReceipt()
                                },
                                onRequeryStatus = fintechViewModel::requeryTransaction,
                                onReportIssue = { ref ->
                                    selectedReceiptTx = null
                                    fintechViewModel.dismissLastVoucherReceipt()
                                    overlayRoute = OverlayRoute.Support(prefilledReference = ref)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
