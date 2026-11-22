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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.model.AccountType
import com.example.data.model.PlatformConfig
import com.example.data.model.ServiceType
import com.example.data.model.TransactionRecord
import com.example.data.model.UserProfile
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.SectionHeader
import com.example.ui.components.TransactionListRow
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.PureWhite
import com.example.ui.theme.TintAirtime
import com.example.ui.theme.TintCable
import com.example.ui.theme.TintData
import com.example.ui.theme.TintEducation
import com.example.ui.theme.TintElectricity
import com.example.ui.theme.TintRechargeCard
import java.util.Calendar

private data class QuickServiceShortcut(
    val label: String,
    val type: ServiceType,
    val icon: ImageVector,
    val tint: Color
)

@Composable
fun HomeDashboardScreen(
    profile: UserProfile,
    platformConfig: PlatformConfig,
    balanceVisible: Boolean,
    isAdmin: Boolean,
    isDirectApiMode: Boolean = false,
    directApiBalanceNaira: String? = null,
    isBackendConfigured: Boolean,
    unreadNotificationCount: Int,
    recentTransactions: List<TransactionRecord>,
    onToggleBalanceVisibility: () -> Unit,
    onFundWalletClick: () -> Unit,
    onServiceClick: (ServiceType) -> Unit,
    onViewAllTransactions: () -> Unit,
    onTransactionClick: (TransactionRecord) -> Unit,
    onNotificationsClick: () -> Unit,
    onSupportClick: () -> Unit,
    onAdminClick: () -> Unit
) {
    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    val allShortcuts = listOf(
        QuickServiceShortcut("Airtime", ServiceType.AIRTIME, Icons.Filled.PhoneAndroid, TintAirtime),
        QuickServiceShortcut("Data", ServiceType.DATA, Icons.Filled.Wifi, TintData),
        QuickServiceShortcut("Recharge PIN", ServiceType.RECHARGE_CARD, Icons.Filled.ConfirmationNumber, TintRechargeCard),
        QuickServiceShortcut("Data Card", ServiceType.DATA_CARD, Icons.Filled.SimCard, TintData),
        QuickServiceShortcut("Electricity", ServiceType.ELECTRICITY, Icons.Filled.Bolt, TintElectricity),
        QuickServiceShortcut("Cable TV", ServiceType.CABLE_TV, Icons.Filled.Tv, TintCable),
        QuickServiceShortcut("Education", ServiceType.WAEC_PIN, Icons.Filled.School, TintEducation),
        QuickServiceShortcut("Broadband", ServiceType.SMILE, Icons.Filled.Router, TintAirtime),
        QuickServiceShortcut("Betting", ServiceType.BETTING, Icons.Filled.SportsEsports, TintElectricity)
    )

    val enabledShortcuts = if (isDirectApiMode) {
        allShortcuts
    } else {
        allShortcuts.filter { item ->
            when (item.type) {
                ServiceType.WAEC_PIN -> platformConfig.isEducationEnabled()
                ServiceType.SMILE -> platformConfig.isBroadbandEnabled()
                else -> platformConfig.isServiceEnabled(item.type)
            }
        }
    }

    val printableCount = recentTransactions.count { it.hasPrintableVouchers() }
    val avatarInitials = profile.displayTitle()
        .trim()
        .split(Regex("\\s+"))
        .take(2)
        .mapNotNull { it.firstOrNull()?.uppercaseChar() }
        .joinToString("")
        .ifBlank { "M" }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // 1. Refined Greeting Header with Avatar Monogram & Action Pills
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    colors = listOf(NavyDeep, NavyContainer)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = avatarInitials,
                            style = MaterialTheme.typography.titleSmall,
                            color = EmeraldBright,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = greeting,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(50)
                            ) {
                                Text(
                                    text = if (profile.resolvedAccountType() == AccountType.BUSINESS) "Business" else "Personal",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = profile.displayTitle(),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onBackground,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    if (isAdmin) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.size(40.dp)
                        ) {
                            IconButton(
                                onClick = onAdminClick,
                                modifier = Modifier.testTag("home_admin_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AdminPanelSettings,
                                    contentDescription = "Admin Console",
                                    tint = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = onSupportClick,
                            modifier = Modifier.testTag("home_support_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.HeadsetMic,
                                contentDescription = "Customer Support",
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.size(40.dp)
                    ) {
                        IconButton(
                            onClick = onNotificationsClick,
                            modifier = Modifier.testTag("home_notifications_button")
                        ) {
                            BadgedBox(
                                badge = {
                                    if (unreadNotificationCount > 0) {
                                        Badge { Text(unreadNotificationCount.coerceAtMost(99).toString()) }
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Notifications,
                                    contentDescription = "Notifications",
                                    tint = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Elevated Gradient Naira Wallet Hero Card (Clean — Fund Wallet opens dedicated bank & card checkout)
        item {
            Card(
                shape = RoundedCornerShape(26.dp),
                colors = CardDefaults.cardColors(containerColor = NavyDeep),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    NavyDeep,
                                    NavyContainer,
                                    Color(0xFF0D2E4D)
                                )
                            )
                        )
                ) {
                    // Subtle geometric emerald glow in top-right corner
                    Box(
                        modifier = Modifier
                            .size(160.dp)
                            .align(Alignment.TopEnd)
                            .offset(x = 42.dp, y = (-42).dp)
                            .clip(CircleShape)
                            .background(EmeraldBright.copy(alpha = 0.12f))
                    )
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .align(Alignment.BottomStart)
                            .offset(x = (-34).dp, y = 36.dp)
                            .clip(CircleShape)
                            .background(PureWhite.copy(alpha = 0.05f))
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(22.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Available Balance",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PureWhite.copy(alpha = 0.78f)
                                )
                                IconButton(
                                    onClick = onToggleBalanceVisibility,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("toggle_balance_visibility")
                                ) {
                                    Icon(
                                        imageVector = if (balanceVisible) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                        contentDescription = if (balanceVisible) "Hide balance" else "Show balance",
                                        tint = PureWhite.copy(alpha = 0.85f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                            Surface(
                                color = EmeraldBright.copy(alpha = 0.18f),
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, EmeraldBright.copy(alpha = 0.35f))
                            ) {
                                Text(
                                    text = platformConfig.brandName,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldBright,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (balanceVisible) CurrencyFormatter.formatKobo(profile.walletBalanceKobo) else "₦ •••••••",
                            style = MaterialTheme.typography.displaySmall.copy(fontFamily = JetBrainsMono),
                            color = PureWhite,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("wallet_balance_text")
                        )

                        if (isDirectApiMode) {
                            val formattedApiBal = directApiBalanceNaira
                                ?.replace(",", "")
                                ?.trim()
                                ?.toDoubleOrNull()
                                ?.let { CurrencyFormatter.formatKobo(Math.round(it * 100.0)) }
                            Spacer(modifier = Modifier.height(10.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = EmeraldBright.copy(alpha = 0.14f),
                                border = BorderStroke(1.dp, EmeraldBright.copy(alpha = 0.35f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("home_direct_api_pill")
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Direct API Mode Active",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = EmeraldBright,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            text = "Wholesale API rates • 0% markup • ₦0 fee",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = PureWhite.copy(alpha = 0.78f)
                                        )
                                    }
                                    if (formattedApiBal != null) {
                                        Text(
                                            text = if (balanceVisible) formattedApiBal else "₦ ••••",
                                            style = MaterialTheme.typography.labelLarge.copy(fontFamily = JetBrainsMono),
                                            color = PureWhite,
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onFundWalletClick,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldBright,
                                    contentColor = NavyDeep
                                ),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                modifier = Modifier
                                    .weight(1.35f)
                                    .height(48.dp)
                                    .testTag("home_fund_wallet_button")
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Fund Wallet",
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Fund Wallet",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Surface(
                                onClick = onViewAllTransactions,
                                color = PureWhite.copy(alpha = 0.12f),
                                shape = RoundedCornerShape(14.dp),
                                border = BorderStroke(1.dp, PureWhite.copy(alpha = 0.18f)),
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp)
                                    .testTag("home_history_button")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.History,
                                        contentDescription = "History",
                                        tint = PureWhite,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "History",
                                        style = MaterialTheme.typography.labelLarge,
                                        color = PureWhite,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Printable E-PIN Vault Quick Strip (Shown only when user has printable vouchers)
        if (printableCount > 0) {
            item {
                Surface(
                    onClick = onViewAllTransactions,
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(16.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.secondary.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Print,
                                contentDescription = "Printable e-PINs",
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$printableCount Printable Voucher${if (printableCount > 1) "s" else ""} Saved",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "Tap to view, print, or save e-PINs as PDF in History",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }
        }

        // 4. Admin-only Backend Readiness Notice (never shown to regular customers)
        if (isAdmin && !isBackendConfigured) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = "Backend setup note",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Backend URL Pending Configuration",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Open Admin Operations → API & Providers and enter your Render HTTPS URL to activate live funding and utility vending.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }

        // 5. Quick Utility Services Grid (Admin-Controlled & Balanced 3-Column Layout)
        if (enabledShortcuts.isNotEmpty()) {
            item {
                SectionHeader(title = "Quick Services")
                Spacer(modifier = Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    enabledShortcuts.chunked(3).forEach { rowItems ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            rowItems.forEach { item ->
                                QuickServiceTile(
                                    shortcut = item,
                                    onClick = { onServiceClick(item.type) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            repeat(3 - rowItems.size) {
                                Spacer(modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 6. Recent Activity Section
        item {
            SectionHeader(
                title = "Recent Activity",
                actionLabel = if (recentTransactions.isNotEmpty()) "See All" else null,
                onActionClick = onViewAllTransactions
            )
        }

        if (recentTransactions.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.ReceiptLong,
                    title = "No transactions yet",
                    subtitle = "Tap Fund Wallet to view your bank transfer details or pay instantly, then enjoy fast utility payments with downloadable receipts."
                )
            }
        } else {
            items(recentTransactions.take(6), key = { it.id.ifBlank { it.reference } }) { tx ->
                TransactionListRow(
                    transaction = tx,
                    onClick = { onTransactionClick(tx) }
                )
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@Composable
private fun QuickServiceTile(
    shortcut: QuickServiceShortcut,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .testTag("shortcut_${shortcut.type.name.lowercase()}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 15.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(shortcut.tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = shortcut.icon,
                    contentDescription = shortcut.label,
                    tint = shortcut.tint,
                    modifier = Modifier.size(23.dp)
                )
            }
            Spacer(modifier = Modifier.height(9.dp))
            Text(
                text = shortcut.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
