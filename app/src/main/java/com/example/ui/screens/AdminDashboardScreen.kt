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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.AccountBalanceWallet
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.core.CurrencyFormatter
import com.example.data.model.AccountStatus
import com.example.data.model.AccountType
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.SupportTicket
import com.example.data.model.TicketStatus
import com.example.data.model.TransactionStatus
import com.example.data.model.UserProfile
import com.example.ui.components.InlineStatusBanner
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusSuccess
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
    val catalogState by adminViewModel.allCatalogState.collectAsStateWithLifecycle()
    val ticketsState by adminViewModel.allTicketsState.collectAsStateWithLifecycle()
    val logsState by adminViewModel.providerLogsState.collectAsStateWithLifecycle()
    val banner by adminViewModel.statusBanner.collectAsStateWithLifecycle()
    val isBusy by adminViewModel.isBusy.collectAsStateWithLifecycle()
    val providerHealth by adminViewModel.providerHealth.collectAsStateWithLifecycle()
    val platformConfig by adminViewModel.platformConfig.collectAsStateWithLifecycle()
    val activeBackendUrl by adminViewModel.activeBackendUrl.collectAsStateWithLifecycle()

    val users = (usersState as? UiState.Success)?.data.orEmpty()
    val transactions = (txState as? UiState.Success)?.data.orEmpty()
    val catalog = (catalogState as? UiState.Success)?.data.orEmpty()
    val tickets = (ticketsState as? UiState.Success)?.data.orEmpty()
    val logs = (logsState as? UiState.Success)?.data.orEmpty()

    var activeTab by remember { mutableIntStateOf(0) }
    var editingCatalogItem by remember { mutableStateOf<ServiceCatalogItem?>(null) }
    var managingUser by remember { mutableStateOf<UserProfile?>(null) }
    var replyingTicket by remember { mutableStateOf<SupportTicket?>(null) }
    var notifyingUser by remember { mutableStateOf<UserProfile?>(null) }

    val tabs = listOf("Overview", "Customers", "Pricing", "API & Providers", "Support")

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
                        modifier = Modifier.testTag("admin_tab_${title.lowercase()}")
                    )
                }
            }

            when (activeTab) {
                0 -> {
                    // OVERVIEW KPIs
                    val individualCount = users.count { it.resolvedAccountType() == AccountType.INDIVIDUAL }
                    val businessCount = users.count { it.resolvedAccountType() == AccountType.BUSINESS }
                    val totalWalletLiability = users.sumOf { it.walletBalanceKobo }
                    val totalVolume = transactions.sumOf { it.totalAmountKobo }
                    val successCount = transactions.count { it.resolvedStatus() == TransactionStatus.SUCCESSFUL }
                    val pendingCount = transactions.count { it.resolvedStatus() == TransactionStatus.PENDING || it.resolvedStatus() == TransactionStatus.PROCESSING }
                    val failedCount = transactions.count { it.resolvedStatus() == TransactionStatus.FAILED }
                    val grossMargin = transactions
                        .filter { it.resolvedStatus() == TransactionStatus.SUCCESSFUL }
                        .sumOf { it.grossMarginKobo }

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                KpiCard("Total Users", users.size.toString(), "$individualCount Ind • $businessCount Biz", Modifier.weight(1f))
                                KpiCard("Wallet Liabilities", CurrencyFormatter.formatKobo(totalWalletLiability), "All NGN Wallets", Modifier.weight(1f))
                            }
                        }
                        item {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                                KpiCard("Total Volume", CurrencyFormatter.formatKobo(totalVolume), "${transactions.size} Txns", Modifier.weight(1f))
                                KpiCard("Gross Margin", CurrencyFormatter.formatKobo(grossMargin), "Settled Profit", Modifier.weight(1f))
                            }
                        }
                        item {
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text("Transaction Health Breakdown", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Successful: $successCount", color = StatusSuccess, fontWeight = FontWeight.Bold)
                                        Text("Pending: $pendingCount", color = StatusPending, fontWeight = FontWeight.Bold)
                                        Text("Failed: $failedCount", color = StatusFailed, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                1 -> {
                    // CUSTOMERS
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(users, key = { it.id.ifBlank { it.userId } }) { user ->
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
                                            Text("${user.accountType} • Balance: ${CurrencyFormatter.formatKobo(user.walletBalanceKobo)}", style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono))
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

                2 -> {
                    // CENTRAL PRICING & SERVICE CATALOG
                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            Button(
                                onClick = { adminViewModel.seedDefaultServiceCatalog() },
                                enabled = !isBusy,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("admin_seed_catalog_button")
                            ) {
                                Icon(Icons.Filled.CloudSync, contentDescription = "Seed catalog", modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Bootstrap / Reset Default Nigerian Service Catalog", fontWeight = FontWeight.Bold)
                            }
                        }

                        items(catalog, key = { it.id }) { item ->
                            Card(
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${item.providerName} • ${item.productName}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "Cost: ${CurrencyFormatter.formatKobo(item.providerCostKobo)} | Sell: ${CurrencyFormatter.formatKobo(item.sellingPriceKobo)} | Fee: ${CurrencyFormatter.formatKobo(item.feeKobo)}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono)
                                        )
                                        Text(
                                            text = "Margin: ${CurrencyFormatter.formatKobo(item.grossMarginKobo())} • Disc: ${item.discountPercent}% • ${if (item.enabled) "ENABLED" else "DISABLED"}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (item.enabled) StatusSuccess else StatusFailed
                                        )
                                    }
                                    IconButton(onClick = { editingCatalogItem = item }) {
                                        Icon(Icons.Filled.Edit, contentDescription = "Edit pricing")
                                    }
                                }
                            }
                        }
                    }
                }

                3 -> {
                    // API, PROVIDERS & RECONCILIATION
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

                    LazyColumn(
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // 1. Live Backend API Endpoint & Mode Configuration (Non-Secret)
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
                                        Text(
                                            "Trusted Backend API",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
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
                                    Text(
                                        text = "Point the app to your deployed Render (or Cloud Run / VPS) HTTPS URL. ClubKonnect & Flutterwave secret keys remain strictly inside backend environment variables.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                                                FilterChip(
                                                    selected = ckMode == mode,
                                                    onClick = { ckMode = mode },
                                                    label = { Text(mode) }
                                                )
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
                                                FilterChip(
                                                    selected = flwMode == mode,
                                                    onClick = { flwMode = mode },
                                                    label = { Text(mode) }
                                                )
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

                        // 2. Live Provider Health & Catalog Sync
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
                                        Text("ClubKonnect & Flutterwave Health", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    }
                                    Text(
                                        text = providerHealth?.let {
                                            "Env: ${it.environment}\n" +
                                                "ClubKonnect: ${if (it.clubKonnectConfigured) "Configured" else "Missing"} (Balance: ₦${it.clubKonnectBalanceNaira ?: "N/A"})\n" +
                                                "Flutterwave: ${if (it.flutterwaveConfigured) "Configured" else "Missing"}\n" +
                                                "Flutterwave Main NGN — Available: ₦${it.flutterwaveAvailableBalanceNaira ?: "N/A"} | Ledger: ₦${it.flutterwaveLedgerBalanceNaira ?: "N/A"}"
                                        } ?: "Query live ClubKonnect & Flutterwave Main NGN balances, wire past funded bank transfers to tied wallets, or sync ClubKonnect bundle costs.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(
                                        onClick = { adminViewModel.checkProviderHealth() },
                                        enabled = !isBusy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_check_provider_health_button")
                                    ) {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Check health", modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Query Live Provider Balances")
                                    }
                                    Button(
                                        onClick = { adminViewModel.syncAllPastFlutterwaveDeposits() },
                                        enabled = !isBusy,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("admin_sync_all_deposits_button")
                                    ) {
                                        Icon(Icons.Filled.CloudSync, contentDescription = "Sync past deposits", modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sync Past Flutterwave Deposits", fontWeight = FontWeight.Bold)
                                    }
                                    OutlinedButton(
                                        onClick = { adminViewModel.syncLiveClubKonnectCatalog() },
                                        enabled = !isBusy,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_sync_clubkonnect_catalog_button")
                                    ) {
                                        Icon(Icons.Filled.CloudSync, contentDescription = "Sync ClubKonnect", modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Sync Live ClubKonnect Catalog Costs")
                                    }
                                }
                            }
                        }

                        // 2B. Super Admin: Fund Admin Wallet from Flutterwave Main NGN Account
                        item {
                            var mainFundNaira by remember { mutableStateOf("5000") }
                            Card(
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Filled.AccountBalanceWallet,
                                            contentDescription = "Flutterwave main balance",
                                            tint = MaterialTheme.colorScheme.secondary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            "Super Admin Main Account Funding",
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Text(
                                        text = "Draw from your live Flutterwave Main NGN Available Balance into your Super Admin app wallet to execute utility transactions directly. Backend verifies available NGN balance before crediting.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (!providerHealth?.flutterwaveAvailableBalanceNaira.isNullOrBlank()) {
                                        Text(
                                            text = "Live Flutterwave Main Available: ₦${providerHealth?.flutterwaveAvailableBalanceNaira}",
                                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                            color = StatusSuccess,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    OutlinedTextField(
                                        value = mainFundNaira,
                                        onValueChange = { mainFundNaira = it.filter { c -> c.isDigit() } },
                                        label = { Text("Amount to Wire to Admin Wallet (₦)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        singleLine = true,
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("admin_main_fund_amount_input")
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        listOf("5000", "10000", "25000", "50000").forEach { preset ->
                                            FilterChip(
                                                selected = mainFundNaira == preset,
                                                onClick = { mainFundNaira = preset },
                                                label = { Text("₦$preset") }
                                            )
                                        }
                                    }
                                    Button(
                                        onClick = {
                                            val kobo = CurrencyFormatter.nairaToKobo(mainFundNaira) ?: 0L
                                            adminViewModel.fundAdminWalletFromMainBalance(kobo)
                                        },
                                        enabled = !isBusy,
                                        shape = RoundedCornerShape(14.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .testTag("admin_fund_from_main_button")
                                    ) {
                                        Text("Wire From Main Account to Admin Wallet", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        // 3. Pending Transaction Reconciliation Queue
                        if (pendingTxs.isNotEmpty()) {
                            item {
                                Text(
                                    "Pending Reconciliation (${pendingTxs.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            items(pendingTxs, key = { it.reference.ifBlank { it.id } }) { tx ->
                                Card(
                                    shape = RoundedCornerShape(14.dp),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(tx.productName.ifBlank { tx.type }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${tx.reference} • ${CurrencyFormatter.formatKobo(tx.totalAmountKobo)}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono)
                                            )
                                            Text("Status: ${tx.status} • Recipient: ${tx.recipient}", style = MaterialTheme.typography.bodySmall)
                                        }
                                        OutlinedButton(
                                            onClick = { adminViewModel.reconcilePendingTransaction(tx.reference) },
                                            enabled = !isBusy
                                        ) {
                                            Text("Requery")
                                        }
                                    }
                                }
                            }
                        }

                        // 4. Provider API Audit Logs
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

                4 -> {
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

    // Edit Pricing Dialog
    editingCatalogItem?.let { item ->
        var costText by remember(item.id) { mutableStateOf((item.providerCostKobo / 100.0).toString()) }
        var sellText by remember(item.id) { mutableStateOf((item.sellingPriceKobo / 100.0).toString()) }
        var feeText by remember(item.id) { mutableStateOf((item.feeKobo / 100.0).toString()) }
        var discText by remember(item.id) { mutableStateOf(item.discountPercent.toString()) }
        var enabled by remember(item.id) { mutableStateOf(item.enabled) }

        AlertDialog(
            onDismissRequest = { editingCatalogItem = null },
            title = { Text("Configure ${item.productName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = costText, onValueChange = { costText = it }, label = { Text("Provider Cost (₦)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = sellText, onValueChange = { sellText = it }, label = { Text("Selling Price (₦)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = feeText, onValueChange = { feeText = it }, label = { Text("Service Fee (₦)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(value = discText, onValueChange = { discText = it }, label = { Text("Discount (%)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Text("Service Enabled")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    adminViewModel.updateServicePricing(
                        serviceId = item.id,
                        providerCostKobo = CurrencyFormatter.nairaToKobo(costText) ?: item.providerCostKobo,
                        sellingPriceKobo = CurrencyFormatter.nairaToKobo(sellText) ?: item.sellingPriceKobo,
                        feeKobo = CurrencyFormatter.nairaToKobo(feeText) ?: item.feeKobo,
                        discountPercent = discText.toDoubleOrNull() ?: item.discountPercent,
                        commissionPercent = item.commissionPercent,
                        enabled = enabled
                    )
                    editingCatalogItem = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { editingCatalogItem = null }) { Text("Cancel") } }
        )
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
                            FilterChip(
                                selected = chosenStatus == st,
                                onClick = { chosenStatus = st },
                                label = { Text(st.name) }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it },
                        label = { Text("Compliance / Restriction Reason") }
                    )
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
                    adminViewModel.respondToSupportTicket(ticket.id, status, response, notes)
                    replyingTicket = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { replyingTicket = null }) { Text("Cancel") } }
        )
    }

    // Send Notification Dialog
    notifyingUser?.let { user ->
        var title by remember(user.userId) { mutableStateOf("Platform Notice") }
        var body by remember(user.userId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { notifyingUser = null },
            title = { Text("Notify ${user.displayTitle()}") },
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
                ) { Text("Send") }
            },
            dismissButton = { TextButton(onClick = { notifyingUser = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun KpiCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono), fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
    }
}
