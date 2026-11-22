package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.model.AccountStatus
import com.example.data.model.AccountType
import com.example.data.model.SupportTicket
import com.example.data.model.TicketStatus
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionStatus
import com.example.data.model.UserProfile
import com.example.data.remote.AdminOverviewMetricsResponse
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusSuccess

@Composable
fun AdminOverviewTab(
    metrics: AdminOverviewMetricsResponse?,
    users: List<UserProfile>,
    transactions: List<TransactionRecord>,
    tickets: List<SupportTicket>,
    isBusy: Boolean,
    onRefreshMetrics: () -> Unit,
    onReconcileAllPending: () -> Unit,
    onSyncPastDeposits: () -> Unit,
    onOpenTreasuryTab: () -> Unit,
    onOpenPricingTab: () -> Unit
) {
    LaunchedEffect(Unit) {
        if (metrics == null) {
            onRefreshMetrics()
        }
    }

    // Period filter: 0 = Daily, 1 = Weekly, 2 = Monthly, 3 = All Time
    var selectedPeriod by remember { mutableIntStateOf(3) }

    // Combine server-aggregated metrics with live Firestore fallback so UI is rich immediately
    val totalUsers = metrics?.totalUsers ?: users.size
    val individualUsers = metrics?.individualUsers ?: users.count { it.resolvedAccountType() == AccountType.INDIVIDUAL }
    val businessUsers = metrics?.businessUsers ?: users.count { it.resolvedAccountType() == AccountType.BUSINESS }
    val activeUsers = metrics?.activeUsers ?: users.count { it.resolvedAccountStatus() == AccountStatus.ACTIVE }
    val restrictedUsers = metrics?.restrictedUsers ?: users.count { it.resolvedAccountStatus() != AccountStatus.ACTIVE }
    val vaUsers = metrics?.virtualAccountUsers ?: users.count { !it.virtualAccountNumber.isNullOrBlank() }
    val totalWalletLiability = metrics?.totalWalletLiabilitiesKobo ?: users.sumOf { it.walletBalanceKobo }

    val fallbackSuccessTxs = remember(transactions) {
        transactions.filter { it.resolvedStatus() == TransactionStatus.SUCCESSFUL }
    }
    val fallbackRefundTxs = remember(transactions) {
        transactions.filter { it.resolvedStatus() == TransactionStatus.REVERSED }
    }
    val allTimeVolume = metrics?.allTimeVolumeKobo ?: fallbackSuccessTxs.sumOf { it.totalAmountKobo }
    val allTimeProfit = metrics?.allTimeProfitKobo ?: fallbackSuccessTxs.sumOf { it.grossMarginKobo }
    val allTimeTxCount = metrics?.allTimeTxCount ?: fallbackSuccessTxs.size

    val activePeriodObj = when (selectedPeriod) {
        0 -> metrics?.dailyMetrics
        1 -> metrics?.weeklyMetrics
        2 -> metrics?.monthlyMetrics
        else -> metrics?.allTimeMetrics
    }

    val periodLabel = when (selectedPeriod) {
        0 -> "Daily (Today)"
        1 -> "Weekly (7d)"
        2 -> "Monthly (30d)"
        else -> "All Time"
    }

    val periodSales = activePeriodObj?.totalSalesKobo ?: when (selectedPeriod) {
        0 -> metrics?.todayVolumeKobo ?: 0L
        1 -> metrics?.last7dVolumeKobo ?: 0L
        2 -> metrics?.last30dVolumeKobo ?: allTimeVolume
        else -> allTimeVolume
    }
    val periodProviderCosts = activePeriodObj?.providerCostsKobo ?: fallbackSuccessTxs.sumOf { it.providerCostKobo }
    val periodProfit = activePeriodObj?.grossProfitKobo ?: when (selectedPeriod) {
        0 -> metrics?.todayProfitKobo ?: 0L
        1 -> metrics?.last7dProfitKobo ?: 0L
        2 -> metrics?.last30dProfitKobo ?: allTimeProfit
        else -> allTimeProfit
    }
    val periodPaymentFees = activePeriodObj?.paymentFeesKobo ?: fallbackSuccessTxs.sumOf { it.feeKobo }
    val periodRefundsKobo = activePeriodObj?.refundsKobo ?: fallbackRefundTxs.sumOf { it.totalAmountKobo }
    val periodRefundCount = activePeriodObj?.refundCount ?: fallbackRefundTxs.size
    val periodTxCount = activePeriodObj?.txCount ?: when (selectedPeriod) {
        0 -> metrics?.todayTxCount ?: 0
        1 -> metrics?.last7dTxCount ?: 0
        2 -> metrics?.last30dTxCount ?: allTimeTxCount
        else -> allTimeTxCount
    }
    val periodBreakdown = activePeriodObj?.serviceBreakdown
        ?: metrics?.serviceBreakdown.orEmpty()
    val effectiveMarginPct = if (periodSales > 0L) (periodProfit * 100.0) / periodSales else 0.0

    val successCount = metrics?.successfulCount ?: fallbackSuccessTxs.size
    val pendingCount = metrics?.pendingCount ?: transactions.count {
        it.resolvedStatus() == TransactionStatus.PENDING || it.resolvedStatus() == TransactionStatus.PROCESSING
    }
    val failedCount = metrics?.failedCount ?: transactions.count { it.resolvedStatus() == TransactionStatus.FAILED }
    val reversedCount = metrics?.reversedCount ?: transactions.count { it.resolvedStatus() == TransactionStatus.REVERSED }
    val openTicketsCount = metrics?.openTicketsCount ?: tickets.count {
        it.resolvedStatus() == TicketStatus.OPEN || it.resolvedStatus() == TicketStatus.IN_PROGRESS
    }
    val successRate = metrics?.successRatePercent ?: run {
        val terminal = successCount + failedCount + reversedCount
        if (terminal > 0) (successCount * 100.0) / terminal else 100.0
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live Provider Float & Solvency Command Card
        item {
            val heroBrush = Brush.linearGradient(
                colors = listOf(Color(0xFF0A2540), Color(0xFF103E4E), Color(0xFF0B5D4B))
            )
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(heroBrush)
                        .padding(18.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "LIVE TREASURY & SOLVENCY",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF8EEAD0),
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = if (metrics != null) {
                                        CurrencyFormatter.formatKobo(metrics.totalProviderFloatKobo)
                                    } else {
                                        "Tap Refresh for Live Float"
                                    },
                                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = JetBrainsMono),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Surface(
                                color = when {
                                    metrics?.solvencyAlert == true -> Color(0xFFD93025)
                                    metrics?.lowClubKonnectFloatWarning == true -> Color(0xFFE37400)
                                    else -> Color(0xFF137333)
                                },
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = when {
                                        metrics == null -> "SYNCING"
                                        metrics.solvencyAlert -> "FLOAT LOW"
                                        else -> "${String.format("%.0f", metrics.floatCoveragePercent)}% COVERED"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                color = Color.White.copy(alpha = 0.11f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "Flutterwave Available",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFD6E4F0)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (metrics?.flutterwaveReachable == true) {
                                            CurrencyFormatter.formatKobo(metrics.flutterwaveAvailableKobo)
                                        } else {
                                            "—"
                                        },
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (metrics?.flutterwaveReachable == true) {
                                        Text(
                                            "Ledger: ${CurrencyFormatter.formatKobo(metrics.flutterwaveLedgerKobo)}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Color(0xFFA9C2D6)
                                        )
                                    }
                                }
                            }
                            Surface(
                                color = Color.White.copy(alpha = 0.11f),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        "ClubKonnect Float",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFD6E4F0)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = if (metrics?.clubKonnectReachable == true) {
                                            CurrencyFormatter.formatKobo(metrics.clubKonnectBalanceKobo)
                                        } else {
                                            "—"
                                        },
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                        color = if (metrics?.lowClubKonnectFloatWarning == true) Color(0xFFFFD166) else Color(0xFF7CF5D1),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = if (metrics?.clubKonnectEnabled == false) {
                                            "OFF • Paused"
                                        } else if (metrics?.lowClubKonnectFloatWarning == true) {
                                            "Low vending float"
                                        } else {
                                            "ON • Vending float"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (metrics?.clubKonnectEnabled == false) Color(0xFFFFB4AB) else Color(0xFFA9C2D6)
                                    )
                                }
                            }
                        }

                        // AlrahuzData Live Float Tile
                        Surface(
                            color = Color.White.copy(alpha = 0.09f),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        "AlrahuzData Live Float",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFFD6E4F0)
                                    )
                                    Text(
                                        text = when {
                                            metrics?.alrahuzDataEnabled == false -> "Provider OFF (Blocked)"
                                            metrics?.alrahuzDataReachable == true -> "ON • Connected"
                                            else -> "Set ALRAHUZDATA_API_TOKEN to activate"
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (metrics?.alrahuzDataEnabled == false) Color(0xFFFFB4AB) else Color(0xFFA9C2D6)
                                    )
                                }
                                Text(
                                    text = if (metrics?.alrahuzDataReachable == true) {
                                        CurrencyFormatter.formatKobo(metrics.alrahuzDataBalanceKobo)
                                    } else {
                                        "—"
                                    },
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                    color = if (metrics?.alrahuzDataReachable == true) Color(0xFF7CF5D1) else Color(0xFFD6E4F0),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onRefreshMetrics,
                                enabled = !isBusy,
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.45f)),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_overview_refresh_button")
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Refresh Live")
                            }
                            Button(
                                onClick = onOpenTreasuryTab,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981),
                                    contentColor = Color(0xFF04261E)
                                ),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("admin_overview_transfer_shortcut")
                            ) {
                                Icon(Icons.Filled.SwapHoriz, contentDescription = "Transfer", modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Move Float", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }

        // 2. Smart Risk Alerts (Low ClubKonnect Float / Solvency / Pending Queue)
        if (metrics?.solvencyAlert == true || metrics?.lowClubKonnectFloatWarning == true) {
            item {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = "Float alert",
                            tint = MaterialTheme.colorScheme.error
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (metrics.solvencyAlert) {
                                    "Solvency Alert: Float Below Liabilities"
                                } else {
                                    "Low ClubKonnect Vending Balance (< ₦10,000)"
                                },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = "Use the Treasury tab to send a live bank transfer from Flutterwave Main Balance to your ClubKonnect Funding Account so customer purchases never fail.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        // 3. Period Selector for Profit Dashboard (Daily | Weekly | Monthly | All Time)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Profit & Financial Dashboard",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("Daily", "Weekly", "Monthly", "All Time").forEachIndexed { idx, label ->
                        FilterChip(
                            selected = selectedPeriod == idx,
                            onClick = { selectedPeriod = idx },
                            label = { Text(label) },
                            modifier = Modifier.testTag("admin_overview_period_${label.lowercase().replace(" ", "_")}")
                        )
                    }
                }
            }
        }

        // 4. Financial Profit KPI Grid (Sales, Provider Costs, Gross Profit, Payment Fees, Refunds)
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OverviewMetricCard(
                    title = "Total Sales",
                    value = CurrencyFormatter.formatKobo(periodSales),
                    subtitle = "$periodLabel • $periodTxCount Orders",
                    badgeText = "${String.format("%.1f", successRate)}% Success",
                    badgeColor = StatusSuccess,
                    modifier = Modifier.weight(1f)
                )
                OverviewMetricCard(
                    title = "Provider Costs",
                    value = CurrencyFormatter.formatKobo(periodProviderCosts),
                    subtitle = "Wholesale API Cost",
                    badgeText = "Settled Outflow",
                    badgeColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OverviewMetricCard(
                    title = "Actual Gross Profit",
                    value = CurrencyFormatter.formatKobo(periodProfit),
                    subtitle = "Sales − Provider Cost",
                    badgeText = "${String.format("%.2f", effectiveMarginPct)}% Net Margin",
                    badgeColor = if (periodProfit >= 0) StatusSuccess else StatusFailed,
                    modifier = Modifier.weight(1f)
                )
                OverviewMetricCard(
                    title = "Payment Fees",
                    value = CurrencyFormatter.formatKobo(periodPaymentFees),
                    subtitle = "Collected Service Fees",
                    badgeText = periodLabel,
                    badgeColor = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                OverviewMetricCard(
                    title = "Refunds & Reversals",
                    value = CurrencyFormatter.formatKobo(periodRefundsKobo),
                    subtitle = "$periodRefundCount Reversed Order(s)",
                    badgeText = if (periodRefundCount > 0) "Auto-Refunded" else "Zero Refunds",
                    badgeColor = if (periodRefundCount > 0) StatusPending else StatusSuccess,
                    modifier = Modifier.weight(1f)
                )
                OverviewMetricCard(
                    title = "Wallet Liabilities",
                    value = CurrencyFormatter.formatKobo(totalWalletLiability),
                    subtitle = "$totalUsers Users ($individualUsers Ind • $businessUsers Biz)",
                    badgeText = if (restrictedUsers > 0) "$restrictedUsers Restricted" else "$vaUsers VA Active",
                    badgeColor = if (openTicketsCount > 0) StatusPending else StatusSuccess,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 5. Transaction Health & One-Click Operational Controls
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Transaction Health & Controls",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                text = "${String.format("%.1f", successRate)}% Rate",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatusCounter("Success", successCount, StatusSuccess)
                        StatusCounter("Pending", pendingCount, StatusPending)
                        StatusCounter("Failed", failedCount, StatusFailed)
                        StatusCounter("Reversed", reversedCount, MaterialTheme.colorScheme.tertiary)
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onReconcileAllPending,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("admin_batch_reconcile_button")
                        ) {
                            Icon(Icons.Filled.SyncProblem, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reconcile ($pendingCount)", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = onSyncPastDeposits,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("admin_overview_sync_deposits_button")
                        ) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Sync Deposits")
                        }
                    }
                }
            }
        }

        // 6. Earnings by Service Category (Period-Filtered Sales, Provider Cost & Profit)
        item {
            val breakdown = periodBreakdown
            val maxVol = breakdown.maxOfOrNull { it.volumeKobo }?.coerceAtLeast(1L) ?: 1L

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.Filled.TrendingUp,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    "Earnings by Service Category",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "$periodLabel • Sales vs Provider Cost vs Profit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        OutlinedButton(
                            onClick = onOpenPricingTab,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Edit Margins", style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    if (breakdown.isEmpty()) {
                        Text(
                            text = "No settled transactions in $periodLabel yet. Switch period filter or tap Refresh Live.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        breakdown.forEach { row ->
                            val ratio = (row.volumeKobo.toFloat() / maxVol.toFloat()).coerceIn(0.04f, 1f)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${row.label} (${row.txCount})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${if (row.profitKobo >= 0) "+" else ""}${CurrencyFormatter.formatKobo(row.profitKobo)} profit",
                                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                        color = if (row.profitKobo >= 0) StatusSuccess else StatusFailed,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Sales: ${CurrencyFormatter.formatKobo(row.volumeKobo)}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "Cost: ${CurrencyFormatter.formatKobo(row.providerCostKobo)} • Fees: ${CurrencyFormatter.formatKobo(row.feesKobo)}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                LinearProgressIndicator(
                                    progress = { ratio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(50)),
                                    color = MaterialTheme.colorScheme.secondary,
                                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 7. Recommended Executive Monitoring Checklist Card
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.AccountBalance,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Automated Backend Protection Active",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "• Multi-Provider Live Routing (ClubKonnect + AlrahuzData) with instant ON/OFF kill switches\n" +
                            "• Server-side price validation & below-cost override guard\n" +
                            "• Duplicate transaction idempotency locks & double-entry ledger accounting\n" +
                            "• Real-time Solvency Ratio monitoring (Provider Float vs Customer Liabilities)\n" +
                            "• 1-Click Batch Pending Reconciler across all enabled providers",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun OverviewMetricCard(
    title: String,
    value: String,
    subtitle: String,
    badgeText: String,
    badgeColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                value,
                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono),
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = badgeText,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatusCounter(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
