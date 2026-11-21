package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.HeadsetMic
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
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
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val greeting = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 0..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    val shortcuts = listOf(
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

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Greeting & Action Icons Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
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
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isAdmin) {
                        IconButton(
                            onClick = onAdminClick,
                            modifier = Modifier.testTag("home_admin_button")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AdminPanelSettings,
                                contentDescription = "Admin Console",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                    IconButton(
                        onClick = onSupportClick,
                        modifier = Modifier.testTag("home_support_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.HeadsetMic,
                            contentDescription = "Customer Support",
                            tint = MaterialTheme.colorScheme.onBackground
                        )
                    }
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
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    }
                }
            }
        }

        // 2. Premium Deep Navy Naira Wallet Card
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = NavyDeep),
                elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_wallet_texture_1795279636150),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        alpha = 0.55f,
                        modifier = Modifier.matchParentSize()
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Available NGN Balance",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = PureWhite.copy(alpha = 0.82f)
                                )
                                IconButton(
                                    onClick = onToggleBalanceVisibility,
                                    modifier = Modifier
                                        .size(36.dp)
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
                                color = EmeraldBright.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50)
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
                            modifier = Modifier.testTag("wallet_balance_text")
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (!profile.virtualAccountNumber.isNullOrBlank()) {
                                Surface(
                                    color = PureWhite.copy(alpha = 0.14f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable {
                                            clipboard.setText(AnnotatedString(profile.virtualAccountNumber))
                                            Toast.makeText(context, "Account number copied", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.AccountBalance,
                                            contentDescription = "Dedicated virtual account",
                                            tint = EmeraldBright,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = profile.virtualBankName ?: "Dedicated Bank",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = PureWhite.copy(alpha = 0.72f),
                                                maxLines = 1
                                            )
                                            Text(
                                                text = profile.virtualAccountNumber,
                                                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                                                color = PureWhite,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                        Icon(
                                            imageVector = Icons.Filled.ContentCopy,
                                            contentDescription = "Copy account number",
                                            tint = PureWhite.copy(alpha = 0.8f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                            }

                            Button(
                                onClick = onFundWalletClick,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = EmeraldBright,
                                    contentColor = NavyDeep
                                ),
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                                modifier = Modifier
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
                        }
                    }
                }
            }
        }

        // 3. Backend Infrastructure Readiness Banner (transparent production status)
        if (!isBackendConfigured) {
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
                                text = "Trusted Backend Endpoint Pending Configuration",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Deploy /backend with ClubKonnect & Flutterwave keys and configure BACKEND_BASE_URL to enable live wallet funding and utility vending. Wallet balances are never modified client-side.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                            )
                        }
                    }
                }
            }
        }

        // 4. Quick Utility Services Grid (3 x 2)
        item {
            SectionHeader(title = "Utility Services")
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shortcuts.chunked(3).forEach { rowItems ->
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
                    }
                }
            }
        }

        // 5. Recent Transactions Section
        item {
            SectionHeader(
                title = "Recent Transactions",
                actionLabel = if (recentTransactions.isNotEmpty()) "See All" else null,
                onActionClick = onViewAllTransactions
            )
        }

        if (recentTransactions.isEmpty()) {
            item {
                EmptyStateCard(
                    icon = Icons.Filled.ReceiptLong,
                    title = "No transactions yet",
                    subtitle = "Fund your Naira wallet and purchase airtime, data, electricity, cable TV, or exam PINs. Every verified transaction appears here with a downloadable receipt."
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 14.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(shortcut.tint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = shortcut.icon,
                    contentDescription = shortcut.label,
                    tint = shortcut.tint,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
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
