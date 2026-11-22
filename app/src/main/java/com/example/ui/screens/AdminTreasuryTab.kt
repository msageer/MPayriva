package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.remote.ClubKonnectFundingAccountDto
import com.example.data.remote.NigerianBankDto
import com.example.data.remote.ProviderHealthResponse
import com.example.data.remote.TreasuryTransferDto
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusSuccess

@Composable
fun AdminTreasuryTab(
    providerHealth: ProviderHealthResponse?,
    nigerianBanks: List<NigerianBankDto>,
    resolvedAccountName: String?,
    savedFundingAccount: ClubKonnectFundingAccountDto?,
    recentTransfers: List<TreasuryTransferDto>,
    isBusy: Boolean,
    onLoadTreasuryWorkspace: () -> Unit,
    onResolveBankAccount: (bankCode: String, accountNumber: String) -> Unit,
    onClearResolvedAccount: () -> Unit,
    onSaveClubKonnectAccount: (bankCode: String, bankName: String, accountNumber: String, accountName: String) -> Unit,
    onSendLiveFlutterwaveToClubKonnect: (
        amountKobo: Long,
        bankCode: String,
        bankName: String,
        accountNumber: String,
        accountName: String,
        narration: String
    ) -> Unit,
    onCheckTransferStatus: (Long) -> Unit,
    onFundAdminFromMain: (Long) -> Unit,
    onFundAdminFromClubKonnect: (Long) -> Unit,
    onRecordClubKonnectToMainSweep: (Long) -> Unit,
    onSyncPastDeposits: () -> Unit
) {
    LaunchedEffect(Unit) {
        onLoadTreasuryWorkspace()
    }

    var selectedBankCode by remember(savedFundingAccount) { mutableStateOf(savedFundingAccount?.bankCode.orEmpty()) }
    var selectedBankName by remember(savedFundingAccount) { mutableStateOf(savedFundingAccount?.bankName.orEmpty()) }
    var accountNumber by remember(savedFundingAccount) { mutableStateOf(savedFundingAccount?.accountNumber.orEmpty()) }
    var accountName by remember(savedFundingAccount, resolvedAccountName) {
        mutableStateOf(resolvedAccountName ?: savedFundingAccount?.accountName.orEmpty())
    }
    var transferAmountNaira by remember { mutableStateOf("5000") }
    var transferNarration by remember { mutableStateOf("M-Sageer Pay ClubKonnect Float Top-Up") }
    var showBankPicker by remember { mutableStateOf(false) }
    var showConfirmPayoutDialog by remember { mutableStateOf(false) }

    var ckAmountNaira by remember { mutableStateOf("1000") }
    var adminMainFundNaira by remember { mutableStateOf("5000") }

    val parsedCkNaira = providerHealth?.clubKonnectBalanceNaira
        ?.replace(Regex("[^0-9.]"), "")
        ?.toDoubleOrNull()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Live Provider Balances Header
        item {
            val heroBrush = Brush.linearGradient(
                colors = listOf(Color(0xFF0A2540), Color(0xFF153B5C))
            )
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(heroBrush)
                        .padding(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "LIVE PROVIDER TREASURY",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF8EEAD0),
                                fontWeight = FontWeight.Bold
                            )
                            TextButton(
                                onClick = onLoadTreasuryWorkspace,
                                enabled = !isBusy,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh", tint = Color.White, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Refresh", color = Color.White)
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Flutterwave Main NGN", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCFE2F3))
                                    Text(
                                        text = "₦${providerHealth?.flutterwaveAvailableBalanceNaira ?: "—"}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Ledger: ₦${providerHealth?.flutterwaveLedgerBalanceNaira ?: "—"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF9EB9D4)
                                    )
                                }
                            }
                            Surface(
                                color = Color.White.copy(alpha = 0.10f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("ClubKonnect Live Float", style = MaterialTheme.typography.labelSmall, color = Color(0xFFCFE2F3))
                                    Text(
                                        text = "₦${providerHealth?.clubKonnectBalanceNaira ?: "—"}",
                                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                        color = Color(0xFF7CF5D1),
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Env: ${providerHealth?.environment ?: "LIVE"}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF9EB9D4)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 2. REAL LIVE TRANSFER: FLUTTERWAVE MAIN BALANCE -> CLUBKONNECT FUNDING ACCOUNT
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
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                Icons.AutoMirrored.Filled.Send,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Flutterwave → ClubKonnect",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Surface(
                            color = StatusSuccess.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Text(
                                "LIVE BANK API",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusSuccess,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }

                    Text(
                        text = "Executes a real live NGN bank transfer from your Flutterwave Main Balance (POST /v3/transfers) directly into your ClubKonnect Funding Bank Account (Wema, Providus, Sterling, Moniepoint, etc.). ClubKonnect credits automatically upon bank receipt.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Bank picker button
                    OutlinedButton(
                        onClick = { showBankPicker = true },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_select_ck_bank_button")
                    ) {
                        Icon(Icons.Filled.AccountBalance, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (selectedBankName.isNotBlank()) {
                                "$selectedBankName ($selectedBankCode)"
                            } else {
                                "Select ClubKonnect Funding Bank (${nigerianBanks.size} NG Banks)"
                            },
                            maxLines = 1
                        )
                    }

                    // 10-digit account number + live NUBAN verify button
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = accountNumber,
                            onValueChange = { raw ->
                                val digits = raw.filter { it.isDigit() }.take(10)
                                accountNumber = digits
                                if (digits != savedFundingAccount?.accountNumber) {
                                    onClearResolvedAccount()
                                }
                            },
                            label = { Text("ClubKonnect 10-Digit Account") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("admin_ck_bank_account_input")
                        )
                        Button(
                            onClick = { onResolveBankAccount(selectedBankCode, accountNumber) },
                            enabled = !isBusy && selectedBankCode.isNotBlank() && accountNumber.length == 10,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("admin_resolve_account_button")
                        ) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = "Verify", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Verify")
                        }
                    }

                    if (accountName.isNotBlank()) {
                        Surface(
                            color = StatusSuccess.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text("Verified Beneficiary", style = MaterialTheme.typography.labelSmall, color = StatusSuccess)
                                        Text(accountName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                    }
                                }
                                TextButton(
                                    onClick = {
                                        onSaveClubKonnectAccount(selectedBankCode, selectedBankName, accountNumber, accountName)
                                    },
                                    enabled = !isBusy
                                ) {
                                    Text("Save Acct", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = transferAmountNaira,
                        onValueChange = { transferAmountNaira = it.filter { c -> c.isDigit() } },
                        label = { Text("Live Transfer Amount (₦)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_flw2ck_amount_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("5000", "10000", "25000", "50000").forEach { preset ->
                            FilterChip(
                                selected = transferAmountNaira == preset,
                                onClick = { transferAmountNaira = preset },
                                label = { Text("₦$preset") }
                            )
                        }
                    }

                    OutlinedTextField(
                        value = transferNarration,
                        onValueChange = { transferNarration = it.take(90) },
                        label = { Text("Transfer Narration") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = { showConfirmPayoutDialog = true },
                        enabled = !isBusy &&
                            selectedBankCode.isNotBlank() &&
                            accountNumber.length == 10 &&
                            accountName.isNotBlank() &&
                            (CurrencyFormatter.nairaToKobo(transferAmountNaira) ?: 0L) >= 10000L,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("admin_send_flw_to_ck_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Send Live Transfer to ClubKonnect", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 3. Recent Live Flutterwave Transfers Tracker
        if (recentTransfers.isNotEmpty()) {
            item {
                Text(
                    "Live Treasury Transfer History (${recentTransfers.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            items(recentTransfers, key = { it.reference.ifBlank { it.id } }) { tr ->
                val badgeColor = when (tr.status.uppercase()) {
                    "SUCCESSFUL" -> StatusSuccess
                    "FAILED" -> StatusFailed
                    else -> StatusPending
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = CurrencyFormatter.formatKobo(tr.amountKobo),
                                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                fontWeight = FontWeight.Bold
                            )
                            Surface(color = badgeColor.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                                Text(
                                    text = tr.status,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = badgeColor,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Text(
                            text = "${tr.bankName} • ${tr.accountNumber} (${tr.accountName})",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = "Ref: ${tr.reference} • Fee: ${CurrencyFormatter.formatKobo(tr.feeKobo)}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (tr.completeMessage.isNotBlank()) {
                            Text(
                                text = tr.completeMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (tr.flutterwaveTransferId > 0L) {
                            OutlinedButton(
                                onClick = { onCheckTransferStatus(tr.flutterwaveTransferId) },
                                enabled = !isBusy,
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) {
                                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Check Live Status (#${tr.flutterwaveTransferId})", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
            }
        }

        // 4. ClubKonnect -> Main Account / Super Admin Wallet (Honest API Explanation)
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "ClubKonnect → Main Account & Admin Wallet",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                text = "Important API Reality: ClubKonnect does not provide an outbound bank-withdrawal API endpoint. To move cash physically out of ClubKonnect into Flutterwave, initiate a manual bank withdrawal in your ClubKonnect web portal to your Flutterwave settlement account. Below, you can allocate live ClubKonnect float directly into your Super Admin wallet for spending or log an audited reconciliation sweep.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = ckAmountNaira,
                        onValueChange = { ckAmountNaira = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("ClubKonnect Amount (₦)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_ck_amount_input")
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("500", "1000", "2000").forEach { preset ->
                            FilterChip(
                                selected = ckAmountNaira == preset,
                                onClick = { ckAmountNaira = preset },
                                label = { Text("₦$preset") }
                            )
                        }
                        if (parsedCkNaira != null && parsedCkNaira >= 100.0) {
                            val fullStr = String.format("%.2f", parsedCkNaira)
                            FilterChip(
                                selected = ckAmountNaira == fullStr,
                                onClick = { ckAmountNaira = fullStr },
                                label = { Text("Use Full") }
                            )
                        }
                    }

                    Button(
                        onClick = {
                            val kobo = CurrencyFormatter.nairaToKobo(ckAmountNaira) ?: 0L
                            onFundAdminFromClubKonnect(kobo)
                        },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_use_ck_balance_button")
                    ) {
                        Text("Load Live ClubKonnect Float to Admin Wallet", fontWeight = FontWeight.Bold)
                    }

                    OutlinedButton(
                        onClick = {
                            val kobo = CurrencyFormatter.nairaToKobo(ckAmountNaira) ?: 0L
                            onRecordClubKonnectToMainSweep(kobo)
                        },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_transfer_ck_to_main_button")
                    ) {
                        Text("Log Audited Portal Withdrawal Sweep")
                    }
                }
            }
        }

        // 5. Super Admin Main Account Wallet Draw & Past Deposits Sync
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AccountBalanceWallet, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Super Admin Wallet & Deposit Sync", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = adminMainFundNaira,
                        onValueChange = { adminMainFundNaira = it.filter { c -> c.isDigit() } },
                        label = { Text("Draw From Flutterwave Main to Admin Wallet (₦)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_main_fund_amount_input")
                    )
                    Button(
                        onClick = {
                            val kobo = CurrencyFormatter.nairaToKobo(adminMainFundNaira) ?: 0L
                            onFundAdminFromMain(kobo)
                        },
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_fund_from_main_button")
                    ) {
                        Text("Credit Super Admin Wallet From Flutterwave Main", fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(
                        onClick = onSyncPastDeposits,
                        enabled = !isBusy,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("admin_sync_all_deposits_button")
                    ) {
                        Icon(Icons.Filled.CloudSync, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Sync All Past Settled Flutterwave Deposits")
                    }
                }
            }
        }
    }

    // Searchable Nigerian Bank Picker Dialog
    if (showBankPicker) {
        var searchQuery by remember { mutableStateOf("") }
        val filteredBanks = remember(nigerianBanks, searchQuery) {
            if (searchQuery.isBlank()) nigerianBanks
            else nigerianBanks.filter {
                it.name.contains(searchQuery, ignoreCase = true) || it.code.contains(searchQuery, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showBankPicker = false },
            title = { Text("Select Nigerian Bank") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search bank (e.g. Wema, Providus, Moniepoint)") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (filteredBanks.isEmpty()) {
                        Text(
                            text = if (nigerianBanks.isEmpty()) {
                                "Bank list not loaded yet. Ensure your backend is deployed and Flutterwave secret key is live, then tap Refresh."
                            } else {
                                "No banks match \"$searchQuery\"."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                            items(filteredBanks, key = { "${it.code}_${it.name}" }) { bank ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            selectedBankCode = bank.code
                                            selectedBankName = bank.name
                                            onClearResolvedAccount()
                                            showBankPicker = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp)
                                ) {
                                    Text(bank.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                    Text("Code: ${bank.code}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBankPicker = false }) { Text("Close") }
            }
        )
    }

    // Explicit Live Payout Confirmation Dialog
    if (showConfirmPayoutDialog) {
        val amountKobo = CurrencyFormatter.nairaToKobo(transferAmountNaira) ?: 0L
        AlertDialog(
            onDismissRequest = { showConfirmPayoutDialog = false },
            title = { Text("Confirm Live Bank Transfer") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "This will immediately debit your LIVE Flutterwave Main NGN Balance and wire real funds via NIBSS:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Amount: ${CurrencyFormatter.formatKobo(amountKobo)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono))
                    Text("Bank: $selectedBankName ($selectedBankCode)", style = MaterialTheme.typography.bodySmall)
                    Text("Account: $accountNumber", style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono))
                    Text("Beneficiary: $accountName", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text("Narration: $transferNarration", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmPayoutDialog = false
                        onSendLiveFlutterwaveToClubKonnect(
                            amountKobo,
                            selectedBankCode,
                            selectedBankName,
                            accountNumber,
                            accountName,
                            transferNarration
                        )
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Authorize Live Transfer", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmPayoutDialog = false }) { Text("Cancel") }
            }
        )
    }
}
