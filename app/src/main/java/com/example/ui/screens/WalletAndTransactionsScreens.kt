package com.example.ui.screens

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ReportProblem
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.core.BrandingConfig
import com.example.core.CurrencyFormatter
import com.example.core.VoucherPdfGenerator
import com.example.core.VoucherSheetLayout
import com.example.data.model.LedgerEntry
import com.example.data.model.TransactionRecord
import com.example.data.model.TransactionStatus
import com.example.data.model.TransactionType
import com.example.data.model.UserProfile
import com.example.data.remote.CardFundingResponse
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.InAppPaymentCheckoutDialog
import com.example.ui.components.StatusPill
import com.example.ui.components.TransactionListRow
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusSuccess

@Composable
fun WalletScreen(
    profile: UserProfile,
    balanceVisible: Boolean,
    ledgerEntries: List<LedgerEntry>,
    lastCardCheckout: CardFundingResponse?,
    isSubmitting: Boolean,
    onRequestVirtualAccount: (bvn: String?) -> Unit,
    onSyncBankDeposits: () -> Unit = {},
    onInitiateCardFunding: (amountKobo: Long) -> Unit,
    onVerifyCardFunding: (reference: String) -> Unit,
    onClearCardCheckout: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var cardAmountNaira by remember { mutableStateOf("5000") }
    var bvnInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header + Balance Summary
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "Naira Wallet",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(10.dp))
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = NavyDeep),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(18.dp)) {
                    Text(
                        text = "Audited Ledger Balance (NGN)",
                        style = MaterialTheme.typography.labelMedium,
                        color = PureWhite.copy(alpha = 0.78f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (balanceVisible) CurrencyFormatter.formatKobo(profile.walletBalanceKobo) else "₦ •••••••",
                        style = MaterialTheme.typography.headlineLarge.copy(fontFamily = JetBrainsMono),
                        color = PureWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Backend-authoritative double-entry ledger • Flutterwave Verified Settlements",
                        style = MaterialTheme.typography.bodySmall,
                        color = EmeraldBright
                    )
                }
            }
        }

        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Fund Wallet") },
                modifier = Modifier.testTag("wallet_tab_fund")
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Double-Entry Ledger (${ledgerEntries.size})") },
                modifier = Modifier.testTag("wallet_tab_ledger")
            )
        }

        if (selectedTab == 0) {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                // Method A: Flutterwave Dedicated Virtual Bank Account
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.AccountBalance, contentDescription = "Bank transfer", tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Dedicated Virtual Bank Account",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Transfer from any Nigerian bank app or USSD. Flutterwave webhook verifies and credits your NGN wallet automatically once.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            if (!profile.virtualAccountNumber.isNullOrBlank()) {
                                val cleanAccountNo = profile.virtualAccountNumber.trim()
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = profile.virtualBankName?.trim().orEmpty().ifBlank { "Partner Bank" },
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                            Text(
                                                text = cleanAccountNo,
                                                style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono),
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Text(
                                                text = profile.virtualAccountName?.trim().orEmpty().ifBlank { profile.displayTitle() },
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        IconButton(onClick = {
                                            clipboard.setText(AnnotatedString(cleanAccountNo))
                                            Toast.makeText(context, "Account number copied", Toast.LENGTH_SHORT).show()
                                        }) {
                                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy account number")
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = onSyncBankDeposits,
                                    enabled = !isSubmitting,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("sync_bank_deposits_button")
                                ) {
                                    if (isSubmitting) {
                                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Filled.Refresh, contentDescription = "Sync bank transfers", modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("Sync Bank Transfers", fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            } else {
                                OutlinedTextField(
                                    value = bvnInput,
                                    onValueChange = { if (it.length <= 11 && it.all { c -> c.isDigit() }) bvnInput = it },
                                    label = { Text("BVN (Required by CBN for Permanent NUBAN)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = { onRequestVirtualAccount(bvnInput) },
                                    enabled = !isSubmitting,
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("request_virtual_account_button")
                                ) {
                                    if (isSubmitting) {
                                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = PureWhite, strokeWidth = 2.dp)
                                    } else {
                                        Text("Generate Dedicated Bank Account")
                                    }
                                }
                            }
                        }
                    }
                }

                // Method B: Flutterwave Card / Checkout Funding
                item {
                    Card(
                        shape = RoundedCornerShape(18.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.CreditCard, contentDescription = "Card funding", tint = MaterialTheme.colorScheme.secondary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Instant Card / Bank Checkout",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Secure Flutterwave v3 checkout session. Backend verifies transaction reference independently before crediting your wallet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = cardAmountNaira,
                                onValueChange = { cardAmountNaira = it.filter { c -> c.isDigit() } },
                                label = { Text("Funding Amount (₦)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("card_fund_amount_input")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("1000", "5000", "10000", "25000").forEach { preset ->
                                    FilterChip(
                                        selected = cardAmountNaira == preset,
                                        onClick = { cardAmountNaira = preset },
                                        label = { Text("₦$preset") }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val kobo = CurrencyFormatter.nairaToKobo(cardAmountNaira) ?: 0L
                                    onInitiateCardFunding(kobo)
                                },
                                enabled = !isSubmitting,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(48.dp)
                                    .testTag("initiate_card_funding_button")
                            ) {
                                if (isSubmitting) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = PureWhite, strokeWidth = 2.dp)
                                } else {
                                    Text("Pay Securely In-App", fontWeight = FontWeight.Bold)
                                }
                            }

                            if (!lastCardCheckout?.reference.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(10.dp))
                                OutlinedButton(
                                    onClick = { onVerifyCardFunding(lastCardCheckout!!.reference!!) },
                                    enabled = !isSubmitting,
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(46.dp)
                                        .testTag("verify_card_payment_button")
                                ) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "Verify payment", modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Verify Last Checkout Payment")
                                }
                            }
                        }
                    }
                }

                // Compliance Notice
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Filled.VerifiedUser, contentDescription = "Compliance", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Settlement accounts and stored-value operations are structured for licensed Nigerian financial institution custody and CBN/NDPA compliance.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            // Double-entry ledger list
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (ledgerEntries.isEmpty()) {
                    item {
                        EmptyStateCard(
                            icon = Icons.Filled.Book,
                            title = "No ledger movements yet",
                            subtitle = "Every verified wallet credit, debit, reservation, and reversal is recorded immutably here with exact before/after kobo balances."
                        )
                    }
                } else {
                    items(ledgerEntries, key = { it.id.ifBlank { "${it.reference}_${it.entryType}" } }) { entry ->
                        val isCredit = entry.entryType == "CREDIT" || entry.entryType == "REVERSAL" || entry.entryType == "RELEASE"
                        Card(
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "${entry.entryType} • ${entry.accountCode}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (isCredit) StatusSuccess else StatusFailed,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${if (isCredit) "+" else "-"}${CurrencyFormatter.formatKobo(entry.amountKobo)}",
                                        style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                                        color = if (isCredit) StatusSuccess else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(entry.narration, style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Before: ${CurrencyFormatter.formatKobo(entry.balanceBeforeKobo)} → After: ${CurrencyFormatter.formatKobo(entry.balanceAfterKobo)}",
                                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = "Ref: ${entry.reference} • ${CurrencyFormatter.formatDateTime(entry.createdAt?.toDate())}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // Automatic In-App Payment Gateway Overlay (with guaranteed Cancel & Back controls)
    val activeCheckoutUrl = lastCardCheckout?.checkoutUrl
    val activeCheckoutRef = lastCardCheckout?.reference
    if (!activeCheckoutUrl.isNullOrBlank() && !activeCheckoutRef.isNullOrBlank()) {
        InAppPaymentCheckoutDialog(
            checkoutUrl = activeCheckoutUrl,
            reference = activeCheckoutRef,
            onCancelAndClose = {
                onClearCardCheckout()
                Toast.makeText(context, "Checkout closed", Toast.LENGTH_SHORT).show()
            },
            onPaymentCompleted = { ref ->
                onClearCardCheckout()
                onVerifyCardFunding(ref)
            }
        )
    }
}

@Composable
fun TransactionsScreen(
    transactions: List<TransactionRecord>,
    onTransactionClick: (TransactionRecord) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }

    val filters = listOf(
        "ALL" to "All",
        "PRINTABLE" to "Printable PINs",
        "SUCCESSFUL" to "Successful",
        "PENDING" to "Pending",
        "FAILED" to "Failed",
        "WALLET_FUNDING" to "Wallet Funding",
        "AIRTIME" to "Airtime",
        "DATA" to "Data",
        "RECHARGE_CARD" to "Recharge Cards",
        "DATA_CARD" to "Data Cards",
        "ELECTRICITY" to "Electricity",
        "CABLE_TV" to "Cable TV",
        "WAEC_PIN" to "WAEC",
        "JAMB_PIN" to "JAMB",
        "SMILE" to "Broadband",
        "BETTING" to "Betting"
    )

    val filtered = remember(transactions, searchQuery, selectedFilter) {
        transactions.filter { tx ->
            val matchesFilter = when (selectedFilter) {
                "ALL" -> true
                "PRINTABLE" -> tx.hasPrintableVouchers()
                "SUCCESSFUL", "PENDING", "FAILED" -> tx.status.equals(selectedFilter, ignoreCase = true) ||
                    (selectedFilter == "PENDING" && tx.status.equals("PROCESSING", ignoreCase = true))
                "SMILE" -> tx.type.equals("SMILE", ignoreCase = true) || tx.type.equals("SPECTRANET", ignoreCase = true)
                else -> tx.type.equals(selectedFilter, ignoreCase = true)
            }
            val q = searchQuery.trim()
            val matchesSearch = q.isEmpty() ||
                tx.reference.contains(q, ignoreCase = true) ||
                tx.recipient.contains(q, ignoreCase = true) ||
                tx.productName.contains(q, ignoreCase = true)
            matchesFilter && matchesSearch
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(
            text = "Transactions",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(10.dp))

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search reference, phone, meter, or product") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("transactions_search_input")
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            filters.forEach { (key, label) ->
                FilterChip(
                    selected = selectedFilter == key,
                    onClick = { selectedFilter = key },
                    label = { Text(label) },
                    modifier = Modifier.testTag("tx_filter_${key.lowercase()}")
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            if (filtered.isEmpty()) {
                item {
                    EmptyStateCard(
                        icon = Icons.Filled.ReceiptLong,
                        title = "No matching transactions",
                        subtitle = "Adjust your search or filter, or complete a wallet funding or utility payment."
                    )
                }
            } else {
                items(filtered, key = { it.id.ifBlank { it.reference } }) { tx ->
                    TransactionListRow(transaction = tx, onClick = { onTransactionClick(tx) })
                }
            }
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
fun TransactionReceiptDialog(
    transaction: TransactionRecord,
    isSubmitting: Boolean,
    sheetHeader: String = BrandingConfig.Default.appDisplayName,
    onDismiss: () -> Unit,
    onRequeryStatus: (String) -> Unit,
    onReportIssue: (String) -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val status = transaction.resolvedStatus()
    val printableVouchers = remember(transaction) {
        if (transaction.hasPrintableVouchers()) VoucherPdfGenerator.extractPrintableVouchers(transaction) else emptyList()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = BrandingConfig.Default.appDisplayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Official Transaction Receipt",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    StatusPill(status = status)
                }

                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = CurrencyFormatter.formatKobo(transaction.totalAmountKobo),
                    style = MaterialTheme.typography.headlineLarge.copy(fontFamily = JetBrainsMono),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                ReceiptRow("Service", transaction.productName.ifBlank { transaction.resolvedType().label })
                ReceiptRow("Reference", transaction.reference, mono = true)
                ReceiptRow("Category", transaction.resolvedType().label)
                ReceiptRow("Recipient", transaction.recipient)
                if (!transaction.recipientName.isNullOrBlank()) {
                    ReceiptRow("Account Name", transaction.recipientName)
                }
                ReceiptRow("Price", CurrencyFormatter.formatKobo(transaction.sellingPriceKobo), mono = true)
                ReceiptRow("Service Fee", CurrencyFormatter.formatKobo(transaction.feeKobo), mono = true)
                ReceiptRow("Date", CurrencyFormatter.formatDateTime(transaction.createdAt?.toDate()))

                if (printableVouchers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Printable E-PINs & Tokens (${printableVouchers.size})",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        printableVouchers.forEachIndexed { idx, voucher ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        clipboard.setText(AnnotatedString(voucher.pin))
                                        Toast.makeText(context, "PIN #${idx + 1} copied", Toast.LENGTH_SHORT).show()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "VOUCHER #${idx + 1} • ${voucher.network.ifBlank { transaction.resolvedType().label }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = voucher.pin,
                                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                            fontWeight = FontWeight.Bold
                                        )
                                        if (voucher.serialNumber.isNotBlank()) {
                                            Text(
                                                text = "S/N: ${voucher.serialNumber}",
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono)
                                            )
                                        }
                                        if (voucher.batchNo.isNotBlank()) {
                                            Text(
                                                text = "Batch: ${voucher.batchNo}",
                                                style = MaterialTheme.typography.bodySmall
                                            )
                                        }
                                        Text(
                                            text = "Load: ${voucher.loadCode.ifBlank { "*311*PIN#" }}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        imageVector = Icons.Filled.ContentCopy,
                                        contentDescription = "Copy voucher PIN",
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }

                    val a4PageCount = VoucherSheetLayout.pageCountFor(printableVouchers.size)
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "A4 Sheet Layout: $a4PageCount page${if (a4PageCount > 1) "s" else ""} • Fixed ${VoucherSheetLayout.CARDS_PER_PAGE} e-PINs/A4 size (${VoucherSheetLayout.COLUMNS}×${VoucherSheetLayout.ROWS})",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            VoucherPdfGenerator.printOrSaveAsPdf(
                                context = context,
                                tx = transaction,
                                sheetHeader = sheetHeader
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = NavyDeep),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("print_voucher_pdf_button")
                    ) {
                        Icon(Icons.Filled.Print, contentDescription = "Print or save PDF", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Print / Save A4 Sheet (30/Page)", fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            VoucherPdfGenerator.shareVoucherPdf(
                                context = context,
                                tx = transaction,
                                sheetHeader = sheetHeader
                            )
                        },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("share_voucher_pdf_button")
                    ) {
                        Icon(Icons.Filled.PictureAsPdf, contentDescription = "Share PDF", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Export / Share A4 PDF Voucher Sheet", fontWeight = FontWeight.SemiBold)
                    }
                } else if (!transaction.tokenOrPin.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                clipboard.setText(AnnotatedString(transaction.tokenOrPin))
                                Toast.makeText(context, "Token / PIN copied", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("DELIVERED TOKEN / PIN", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                Text(
                                    text = transaction.tokenOrPin,
                                    style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                                    fontWeight = FontWeight.Bold
                                )
                                if (!transaction.serialNumber.isNullOrBlank()) {
                                    Text("Serial: ${transaction.serialNumber}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Copy token", tint = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }

                if (!transaction.failureReason.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Note: ${transaction.failureReason}",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusFailed
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (status == TransactionStatus.PENDING || status == TransactionStatus.PROCESSING || status == TransactionStatus.INITIATED) {
                    OutlinedButton(
                        onClick = { onRequeryStatus(transaction.reference) },
                        enabled = !isSubmitting,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(46.dp)
                            .testTag("requery_transaction_button")
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Requery status", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Verify Status with Provider")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onReportIssue(transaction.reference) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                    ) {
                        Icon(Icons.Filled.ReportProblem, contentDescription = "Report issue", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Dispute")
                    }
                    Button(
                        onClick = {
                            val receiptText = buildString {
                                appendLine("=== ${BrandingConfig.Default.appDisplayName} Receipt ===")
                                appendLine(BrandingConfig.Default.companyLegalName)
                                appendLine("Reference: ${transaction.reference}")
                                appendLine("Service: ${transaction.productName}")
                                appendLine("Recipient: ${transaction.recipient}")
                                appendLine("Amount: ${CurrencyFormatter.formatKobo(transaction.totalAmountKobo)}")
                                appendLine("Fee: ${CurrencyFormatter.formatKobo(transaction.feeKobo)}")
                                appendLine("Status: ${status.label}")
                                if (printableVouchers.isNotEmpty()) {
                                    appendLine("Vouchers:")
                                    printableVouchers.forEachIndexed { idx, v ->
                                        appendLine("#${idx + 1}: ${v.pin}${if (v.serialNumber.isNotBlank()) " (S/N: ${v.serialNumber})" else ""}")
                                    }
                                } else if (!transaction.tokenOrPin.isNullOrBlank()) {
                                    appendLine("Token/PIN: ${transaction.tokenOrPin}")
                                }
                                appendLine("Date: ${CurrencyFormatter.formatDateTime(transaction.createdAt?.toDate())}")
                            }
                            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_SUBJECT, "Transaction Receipt - ${transaction.reference}")
                                putExtra(Intent.EXTRA_TEXT, receiptText)
                            }
                            context.startActivity(Intent.createChooser(shareIntent, "Share Receipt"))
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier
                            .weight(1f)
                            .height(46.dp)
                            .testTag("share_receipt_button")
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Share receipt", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Share Text")
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Close")
                }
            }
        }
    }
}

@Composable
private fun ReceiptRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = value,
            style = if (mono) MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono) else MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
