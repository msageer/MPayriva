package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.HealthAndSafety
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.BrandingConfig
import com.example.core.CurrencyFormatter
import com.example.data.model.AccountStatus
import com.example.data.model.SupportTicket
import com.example.data.model.TicketStatus
import com.example.data.model.TransactionStatus
import com.example.data.model.UserProfile
import com.example.ui.components.InlineStatusBanner
import com.example.ui.theme.JetBrainsMono
import com.example.viewmodel.AdminViewModel
import com.example.viewmodel.UiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminDashboardScreen(
    adminViewModel: AdminViewModel,
    onBack: () -> Unit,
    adminEmail: String = "",
    isPrimaryAdminHome: Boolean = false,
    onSwitchToCustomerView: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null
) {
    if (!isPrimaryAdminHome) {
        BackHandler(onBack = onBack)
    }

    val usersState by adminViewModel.allUsersState.collectAsStateWithLifecycle()
    val txState by adminViewModel.allTransactionsState.collectAsStateWithLifecycle()
    val ticketsState by adminViewModel.allTicketsState.collectAsStateWithLifecycle()
    val logsState by adminViewModel.providerLogsState.collectAsStateWithLifecycle()
    val banner by adminViewModel.statusBanner.collectAsStateWithLifecycle()
    val isBusy by adminViewModel.isBusy.collectAsStateWithLifecycle()
    val providerHealth by adminViewModel.providerHealth.collectAsStateWithLifecycle()
    val providerControls by adminViewModel.providerControls.collectAsStateWithLifecycle()
    val overviewMetrics by adminViewModel.overviewMetrics.collectAsStateWithLifecycle()
    val pricingMatrix by adminViewModel.pricingMatrix.collectAsStateWithLifecycle()
    val isLoadingPricing by adminViewModel.isLoadingPricing.collectAsStateWithLifecycle()
    val nigerianBanks by adminViewModel.nigerianBanks.collectAsStateWithLifecycle()
    val resolvedAccountName by adminViewModel.resolvedAccountName.collectAsStateWithLifecycle()
    val treasuryConfig by adminViewModel.treasuryConfig.collectAsStateWithLifecycle()
    val treasuryTransfers by adminViewModel.treasuryTransfers.collectAsStateWithLifecycle()
    val platformConfig by adminViewModel.platformConfig.collectAsStateWithLifecycle()
    val activeBackendUrl by adminViewModel.activeBackendUrl.collectAsStateWithLifecycle()

    val users = (usersState as? UiState.Success)?.data.orEmpty()
    val transactions = (txState as? UiState.Success)?.data.orEmpty()
    val tickets = (ticketsState as? UiState.Success)?.data.orEmpty()
    val logs = (logsState as? UiState.Success)?.data.orEmpty()

    var activeTab by remember { mutableIntStateOf(0) }
    var managingUser by remember { mutableStateOf<UserProfile?>(null) }
    var replyingTicket by remember { mutableStateOf<SupportTicket?>(null) }
    var notifyingUser by remember { mutableStateOf<UserProfile?>(null) }
    var showBroadcastDialog by remember { mutableStateOf(false) }

    val tabs = listOf("Overview", "Treasury", "Pricing", "Users", "API & Logs", "Support")

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Admin Operations", fontWeight = FontWeight.Bold)
                        if (adminEmail.isNotBlank()) {
                            Text(
                                text = adminEmail,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (!isPrimaryAdminHome) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (onSwitchToCustomerView != null) {
                        IconButton(
                            onClick = onSwitchToCustomerView,
                            modifier = Modifier.testTag("admin_switch_customer_view")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Storefront,
                                contentDescription = "Switch to Customer App"
                            )
                        }
                    }
                    if (onSignOut != null) {
                        IconButton(
                            onClick = onSignOut,
                            modifier = Modifier.testTag("admin_sign_out_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Logout,
                                contentDescription = "Sign Out"
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (banner != null) {
                InlineStatusBanner(
                    message = banner!!.message,
                    isError = banner!!.isError,
                    onDismiss = adminViewModel::clearBanner,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }

            ScrollableTabRow(selectedTabIndex = activeTab, edgePadding = 12.dp) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        text = { Text(title) },
                        modifier = Modifier.testTag("admin_tab_${title.lowercase().replace(" ", "_")}")
                    )
                }
            }

            when (activeTab) {
                0 -> AdminOverviewTab(
                    metrics = overviewMetrics,
                    users = users,
                    transactions = transactions,
                    tickets = tickets,
                    isBusy = isBusy,
                    onRefreshMetrics = { adminViewModel.loadOverviewMetrics(silent = false) },
                    onReconcileAllPending = adminViewModel::reconcileAllPendingTransactions,
                    onSyncPastDeposits = adminViewModel::syncAllPastFlutterwaveDeposits,
                    onOpenTreasuryTab = { activeTab = 1 },
                    onOpenPricingTab = { activeTab = 2 }
                )

                1 -> AdminTreasuryTab(
                    providerHealth = providerHealth,
                    nigerianBanks = nigerianBanks,
                    resolvedAccountName = resolvedAccountName,
                    savedFundingAccount = treasuryConfig,
                    recentTransfers = treasuryTransfers,
                    isBusy = isBusy,
                    onLoadTreasuryWorkspace = { adminViewModel.loadTreasuryWorkspace(silent = false) },
                    onResolveBankAccount = adminViewModel::resolveBankAccount,
                    onClearResolvedAccount = adminViewModel::clearResolvedAccount,
                    onSaveClubKonnectAccount = adminViewModel::saveClubKonnectFundingAccount,
                    onSendLiveFlutterwaveToClubKonnect = adminViewModel::initiateLiveFlutterwaveToClubKonnectTransfer,
                    onCheckTransferStatus = adminViewModel::checkLiveTransferStatus,
                    onFundAdminFromMain = adminViewModel::fundAdminWalletFromMainBalance,
                    onFundAdminFromClubKonnect = adminViewModel::fundAdminWalletFromClubKonnect,
                    onRecordClubKonnectToMainSweep = adminViewModel::transferClubKonnectToMainAccount,
                    onSyncPastDeposits = adminViewModel::syncAllPastFlutterwaveDeposits
                )

                2 -> AdminPricingTab(
                    pricingMatrix = pricingMatrix,
                    isLoadingPricing = isLoadingPricing,
                    platformConfig = platformConfig,
                    providerControls = providerControls,
                    isBusy = isBusy,
                    onLoadPricingMatrix = { refresh -> adminViewModel.loadPricingMatrix(refresh = refresh) },
                    onSavePricingRules = adminViewModel::savePricingRules,
                    onSavePlanOverride = adminViewModel::savePlanOverride,
                    onToggleUtilityService = adminViewModel::toggleUtilityService,
                    onRefreshProviderControls = { adminViewModel.loadProviderControls(silent = false) },
                    onToggleApiProvider = adminViewModel::toggleApiProvider,
                    onUpdateServiceRouting = adminViewModel::updateServiceRouting
                )

                3 -> {
                    // USERS & PUSH BROADCAST
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Button(
                                onClick = { showBroadcastDialog = true },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("admin_broadcast_push_button")
                            ) {
                                Icon(Icons.Filled.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Broadcast Push Announcement to All Users", fontWeight = FontWeight.Bold)
                            }
                        }
                        items(users, key = { it.id.ifBlank { it.userId } }) { user ->
                            val roleLabel = if (user.email.equals(BrandingConfig.Default.bootstrappedAdminEmail, ignoreCase = true)) {
                                "Super Admin"
                            } else {
                                "User"
                            }
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(user.displayTitle(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text("${user.email} • ${user.phoneNumber}", style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                "$roleLabel • Balance: ${CurrencyFormatter.formatKobo(user.walletBalanceKobo)}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono)
                                            )
                                        }
                                        Row {
                                            IconButton(onClick = { notifyingUser = user }) {
                                                Icon(Icons.Filled.NotificationsActive, contentDescription = "Notify user")
                                            }
                                            IconButton(onClick = { managingUser = user }) {
                                                Icon(Icons.Filled.Edit, contentDescription = "Manage status")
                                            }
                                        }
                                    }
                                    Surface(
                                        color = if (user.accountStatus == "ACTIVE") MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = user.accountStatus,
                                            style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                4 -> {
                    // API CONFIGURATION, PENDING RECONCILIATION & PROVIDER LOGS
                    var backendUrlInput by remember(platformConfig.backendBaseUrl) {
                        mutableStateOf(platformConfig.backendBaseUrl.ifBlank { activeBackendUrl })
                    }
                    var ckMode by remember(platformConfig.clubKonnectMode) { mutableStateOf(platformConfig.clubKonnectMode) }
                    var flwMode by remember(platformConfig.flutterwaveMode) { mutableStateOf(platformConfig.flutterwaveMode) }
                    var vaEnabled by remember(platformConfig.virtualAccountsEnabled) { mutableStateOf(platformConfig.virtualAccountsEnabled) }
                    var cardEnabled by remember(platformConfig.cardFundingEnabled) { mutableStateOf(platformConfig.cardFundingEnabled) }
                    var maintMode by remember(platformConfig.maintenanceMode) { mutableStateOf(platformConfig.maintenanceMode) }
                    val pendingTxs = transactions.filter {
                        it.resolvedStatus() == TransactionStatus.PENDING || it.resolvedStatus() == TransactionStatus.PROCESSING
                    }
                    val urlConfigured = activeBackendUrl.startsWith("https://") || activeBackendUrl.startsWith("http://10.0.2.2")

                    LaunchedEffect(Unit) {
                        adminViewModel.loadProviderControls(silent = true)
                    }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            AdminProvidersControlCard(
                                controls = providerControls,
                                isBusy = isBusy,
                                onRefresh = { adminViewModel.loadProviderControls(silent = false) },
                                onToggleProvider = adminViewModel::toggleApiProvider,
                                onUpdateServiceRouting = adminViewModel::updateServiceRouting
                            )
                        }

                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Trusted Backend API", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Surface(
                                            color = if (urlConfigured) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                                            shape = RoundedCornerShape(50)
                                        ) {
                                            Text(
                                                text = if (urlConfigured) "CONNECTED" else "URL NOT SET",
                                                style = MaterialTheme.typography.labelSmall,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = backendUrlInput,
                                        onValueChange = { backendUrlInput = it },
                                        label = { Text("Backend HTTPS Base URL") },
                                        placeholder = { Text("https://msageer-pay-backend.onrender.com") },
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_backend_url_input")
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("ClubKonnect Mode", style = MaterialTheme.typography.bodyMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("SANDBOX", "LIVE").forEach { mode ->
                                                FilterChip(selected = ckMode == mode, onClick = { ckMode = mode }, label = { Text(mode) })
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Flutterwave Mode", style = MaterialTheme.typography.bodyMedium)
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            listOf("SANDBOX", "LIVE").forEach { mode ->
                                                FilterChip(selected = flwMode == mode, onClick = { flwMode = mode }, label = { Text(mode) })
                                            }
                                        }
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Dedicated Virtual Accounts", style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = vaEnabled, onCheckedChange = { vaEnabled = it })
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Card / Checkout Funding", style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = cardEnabled, onCheckedChange = { cardEnabled = it })
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Maintenance Mode", style = MaterialTheme.typography.bodyMedium)
                                        Switch(checked = maintMode, onCheckedChange = { maintMode = it })
                                    }
                                    Button(
                                        onClick = {
                                            adminViewModel.saveApiPlatformConfig(
                                                backendBaseUrl = backendUrlInput,
                                                clubKonnectMode = ckMode,
                                                flutterwaveMode = flwMode,
                                                virtualAccountsEnabled = vaEnabled,
                                                cardFundingEnabled = cardEnabled,
                                                maintenanceMode = maintMode
                                            )
                                        },
                                        enabled = !isBusy,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("admin_save_api_config_button")
                                    ) {
                                        Text("Save API Configuration", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.HealthAndSafety, contentDescription = "Providers", tint = MaterialTheme.colorScheme.secondary)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Provider Connectivity & Diagnostics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        text = providerHealth?.let {
                                            "Env: ${it.environment}\n" +
                                                "ClubKonnect: ${if (it.clubKonnectConfigured) "Configured" else "Missing"} • ${if (it.clubKonnectEnabled) "ON" else "OFF"} (₦${it.clubKonnectBalanceNaira ?: "N/A"})\n" +
                                                "AlrahuzData: ${if (it.alrahuzDataConfigured) "Configured" else "Token Not Set"} • ${if (it.alrahuzDataEnabled) "ON" else "OFF"} (₦${it.alrahuzDataBalanceNaira ?: "N/A"})\n" +
                                                "Flutterwave: ${if (it.flutterwaveConfigured) "Configured" else "Missing"} (Avail: ₦${it.flutterwaveAvailableBalanceNaira ?: "N/A"})"
                                        } ?: "Tap Query Provider Health to verify backend connectivity.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(
                                        onClick = { adminViewModel.checkProviderHealth() },
                                        enabled = !isBusy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_check_provider_health_button")
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Query Live Provider Health")
                                    }
                                    OutlinedButton(
                                        onClick = { adminViewModel.syncLiveClubKonnectCatalog() },
                                        enabled = !isBusy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_sync_clubkonnect_catalog_button")
                                    ) {
                                        Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Sync ClubKonnect Costs to Custom Overrides")
                                    }
                                }
                            }
                        }

                        if (pendingTxs.isNotEmpty()) {
                            item {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Pending Queue (${pendingTxs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    TextButton(onClick = adminViewModel::reconcileAllPendingTransactions, enabled = !isBusy) {
                                        Text("Reconcile All")
                                    }
                                }
                            }
                            items(pendingTxs, key = { it.reference.ifBlank { it.id } }) { tx ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tx.productName.ifBlank { tx.type }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${tx.reference} • ${CurrencyFormatter.formatKobo(tx.totalAmountKobo)}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono)
                                            )
                                            Text("Status: ${tx.status} • Recipient: ${tx.recipient}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        OutlinedButton(onClick = { adminViewModel.reconcilePendingTransaction(tx.reference) }, enabled = !isBusy) {
                                            Text("Requery")
                                        }
                                    }
                                }
                            }
                        }

                        item {
                            Text("Provider Audit Logs (${logs.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        items(logs, key = { it.id }) { log ->
                            Card(
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("${log.provider} • ${log.action} (${log.outcome})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text(log.message, style = MaterialTheme.typography.bodySmall)
                                    Text("Ref: ${log.reference} • HTTP ${log.statusCode}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                5 -> {
                    // SUPPORT & DISPUTES
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(tickets, key = { it.id }) { ticket ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text("${ticket.subject} (${ticket.status})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    Text("${ticket.userName} • ${ticket.userEmail}", style = MaterialTheme.typography.bodySmall)
                                    if (!ticket.transactionReference.isNullOrBlank()) {
                                        Text("Tx Ref: ${ticket.transactionReference}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono))
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(ticket.message, style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    OutlinedButton(onClick = { replyingTicket = ticket }) {
                                        Text("Respond / Update Status")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Manage Customer Account Status Dialog
    managingUser?.let { user ->
        var chosenStatus by remember(user.userId) { mutableStateOf(user.resolvedAccountStatus()) }
        var reason by remember(user.userId) { mutableStateOf(user.restrictionReason ?: "") }
        AlertDialog(
            onDismissRequest = { managingUser = null },
            title = { Text("Manage ${user.displayTitle()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AccountStatus.entries.forEach { st ->
                            FilterChip(selected = chosenStatus == st, onClick = { chosenStatus = st }, label = { Text(st.name) })
                        }
                    }
                    OutlinedTextField(value = reason, onValueChange = { reason = it }, label = { Text("Compliance / Restriction Reason") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    adminViewModel.updateCustomerStatus(user.userId, chosenStatus, reason)
                    managingUser = null
                }) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { managingUser = null }) { Text("Cancel") } }
        )
    }

    // Respond to Ticket Dialog
    replyingTicket?.let { ticket ->
        var status by remember(ticket.id) { mutableStateOf(ticket.resolvedStatus()) }
        var response by remember(ticket.id) { mutableStateOf(ticket.adminResponse ?: "") }
        var notes by remember(ticket.id) { mutableStateOf(ticket.adminNotes ?: "") }
        AlertDialog(
            onDismissRequest = { replyingTicket = null },
            title = { Text("Respond to Ticket") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(TicketStatus.IN_PROGRESS, TicketStatus.RESOLVED, TicketStatus.CLOSED).forEach { st ->
                            FilterChip(selected = status == st, onClick = { status = st }, label = { Text(st.label) })
                        }
                    }
                    OutlinedTextField(value = response, onValueChange = { response = it }, label = { Text("Customer Response") }, minLines = 2)
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Internal Admin Notes") })
                }
            },
            confirmButton = {
                Button(onClick = {
                    adminViewModel.respondToSupportTicket(
                        ticketId = ticket.id,
                        targetUserId = ticket.userId,
                        status = status,
                        response = response,
                        notes = notes
                    )
                    replyingTicket = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { replyingTicket = null }) { Text("Cancel") } }
        )
    }

    // Send Notification Dialog (Direct Push)
    notifyingUser?.let { user ->
        var title by remember(user.userId) { mutableStateOf("Platform Notice") }
        var body by remember(user.userId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { notifyingUser = null },
            title = { Text("Push Alert to ${user.displayTitle()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Message") }, minLines = 3)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        adminViewModel.sendAnnouncementToUser(user.userId, title, body)
                        notifyingUser = null
                    },
                    enabled = title.trim().length >= 2 && body.trim().length >= 2
                ) { Text("Send Push") }
            },
            dismissButton = { TextButton(onClick = { notifyingUser = null }) { Text("Cancel") } }
        )
    }

    // Broadcast Announcement Dialog (All Active Users Across All Platforms)
    if (showBroadcastDialog) {
        var title by remember { mutableStateOf("M-Sageer Pay Update") }
        var body by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBroadcastDialog = false },
            title = { Text("Broadcast Push to All Users") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Delivers a real-time push notification to all registered Android, iOS, and Web devices.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") })
                    OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Announcement Message") }, minLines = 3)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        adminViewModel.broadcastAnnouncementToAll(title, body)
                        showBroadcastDialog = false
                    },
                    enabled = title.trim().length >= 2 && body.trim().length >= 2
                ) { Text("Broadcast Now") }
            },
            dismissButton = { TextButton(onClick = { showBroadcastDialog = false }) { Text("Cancel") } }
        )
    }
}
