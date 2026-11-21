package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.model.NigerianProviders
import com.example.data.model.ServiceType
import com.example.data.model.TelecomNetwork
import com.example.data.model.UserProfile
import com.example.data.remote.CustomerVerificationResponse
import com.example.data.remote.LiveCatalogPlanDto
import com.example.data.remote.LiveServiceAvailabilityGroupDto
import com.example.data.remote.LiveStorefrontCatalogResponse
import com.example.ui.components.PurchaseSummaryItem
import com.example.ui.theme.EmeraldBright
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.NavyContainer
import com.example.ui.theme.NavyDeep
import com.example.ui.theme.PureWhite
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusFailedBg
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusPendingBg
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessBg
import com.example.viewmodel.UiState
import java.util.UUID

internal data class PendingPurchaseDraft(
    val serviceType: String,
    val providerCode: String,
    val productCode: String,
    val productTitle: String,
    val recipient: String,
    val amountKobo: Long,
    val meterType: String? = null,
    val quantity: Int = 1,
    val summary: PurchaseSummaryItem,
    val idempotencyKey: String = "tx_${UUID.randomUUID()}"
)

@Composable
internal fun LiveCatalogStatusBanner(
    liveCatalogState: UiState<LiveStorefrontCatalogResponse>,
    onRefresh: () -> Unit
) {
    when (liveCatalogState) {
        UiState.Loading -> {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Updating live service rates & availability…",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        is UiState.Success -> {
            // Subtle, useful white-label refresh bar — never discloses internal API providers
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudDone,
                        contentDescription = "Rates updated",
                        tint = StatusSuccess,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Instant delivery • Verified live tariff rates",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_live_catalog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh service rates",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        is UiState.Error -> {
            Surface(
                color = StatusFailedBg,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, StatusFailed.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.CloudOff,
                        contentDescription = "Rates offline",
                        tint = StatusFailed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Live rates temporarily unavailable. Tap Retry to refresh.",
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusFailed,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("retry_live_catalog_button")
                    ) {
                        Text("Retry", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ServiceAvailabilityBanner(
    title: String,
    isLoading: Boolean,
    available: Boolean,
    statusMessage: String,
    onCheckAvailability: () -> Unit
) {
    val bg = when {
        isLoading -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
        available -> StatusSuccessBg
        else -> StatusFailedBg
    }
    val tint = when {
        isLoading -> MaterialTheme.colorScheme.primary
        available -> StatusSuccess
        else -> StatusFailed
    }
    val badge = when {
        isLoading -> "CHECKING"
        available -> "AVAILABLE"
        else -> "CURRENTLY UNAVAILABLE"
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.3f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("epin_availability_banner")
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (available) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                        contentDescription = badge,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$title: $badge",
                        style = MaterialTheme.typography.labelLarge,
                        color = tint,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(
                    onClick = onCheckAvailability,
                    modifier = Modifier.testTag("check_epin_availability_button")
                ) {
                    Text("Refresh", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (available) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Print,
                        contentDescription = "Printable PDF",
                        tint = StatusSuccess,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Instant PDF voucher sheet available immediately after purchase & anytime in History.",
                        style = MaterialTheme.typography.labelSmall,
                        color = StatusSuccess,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

private fun networkBrandAccent(code: String): Pair<Color, Color> = when (code) {
    "01" -> Color(0xFFD97706) to Color(0xFFFEF3C7) // MTN Amber/Gold
    "04" -> Color(0xFFDC2626) to Color(0xFFFEE2E2) // Airtel Crimson
    "02" -> Color(0xFF15803D) to Color(0xFFDCFCE7) // Glo Emerald
    "03" -> Color(0xFF4D7C0F) to Color(0xFFECFCCB) // 9mobile Lime
    else -> NavyDeep to Color(0xFFE9EEF7)
}

@Composable
private fun CompactStockStatusPill(
    title: String,
    isLoading: Boolean,
    available: Boolean,
    statusMessage: String,
    onRefresh: () -> Unit
) {
    val bg = when {
        isLoading -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
        available -> StatusSuccessBg
        else -> StatusFailedBg
    }
    val tint = when {
        isLoading -> MaterialTheme.colorScheme.primary
        available -> StatusSuccess
        else -> StatusFailed
    }
    val badge = when {
        isLoading -> "Checking stock…"
        available -> "In Stock • Printable PDF"
        else -> "Out of Stock"
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.25f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("epin_availability_banner")
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$title — $badge",
                    style = MaterialTheme.typography.labelLarge,
                    color = tint,
                    fontWeight = FontWeight.Bold
                )
                if (!available && !isLoading) {
                    Text(
                        text = statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            IconButton(
                onClick = onRefresh,
                modifier = Modifier.testTag("check_epin_availability_button")
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = "Refresh stock status",
                    tint = tint,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun NetworkTileSelector(
    networks: List<TelecomNetwork>,
    selectedCode: String,
    onSelect: (TelecomNetwork) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        networks.forEach { net ->
            val selected = net.clubKonnectCode == selectedCode
            val (brandFg, brandBg) = networkBrandAccent(net.clubKonnectCode)
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(net) }
                    .testTag("epin_net_${net.clubKonnectCode}")
            ) {
                Column(
                    modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(brandBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = net.shortCode.take(1).uppercase(),
                            style = MaterialTheme.typography.titleSmall,
                            color = brandFg,
                            fontWeight = FontWeight.ExtraBold
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = net.shortCode,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun QuantityStepperSection(
    label: String,
    quantity: Int,
    presets: List<Int>,
    onQuantityChange: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "Max 50 vouchers per batch",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { if (quantity > 1) onQuantityChange(quantity - 1) },
                        enabled = quantity > 1,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Remove, contentDescription = "Decrease quantity")
                    }
                    Text(
                        text = quantity.toString(),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                    IconButton(
                        onClick = { if (quantity < 50) onQuantityChange(quantity + 1) },
                        enabled = quantity < 50,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Increase quantity")
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { preset ->
                val active = quantity == preset
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (active) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clickable { onQuantityChange(preset) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "$preset PIN${if (preset > 1) "s" else ""}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (active) PureWhite else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun RechargeCardEpinForm(
    profile: UserProfile,
    isLoadingLive: Boolean,
    group: LiveServiceAvailabilityGroupDto?,
    onRefreshAvailability: () -> Unit,
    onCreateDraft: (PendingPurchaseDraft) -> Unit
) {
    val isAvailable = group?.available == true
    val statusMsg = group?.statusMessage?.takeIf { it.isNotBlank() }
        ?: if (isLoadingLive) "Checking live e-PIN stock…"
        else "Tap Refresh to check current recharge card e-PIN availability."

    val liveItems = group?.items.orEmpty()
    val availableNetworks = remember(liveItems) {
        if (liveItems.isEmpty()) NigerianProviders.telecomNetworks
        else {
            val codes = liveItems.map { it.providerCode }.toSet()
            NigerianProviders.telecomNetworks.filter { it.clubKonnectCode in codes }
                .ifEmpty { NigerianProviders.telecomNetworks }
        }
    }
    var selectedNetCode by remember(availableNetworks) { mutableStateOf(availableNetworks.first().clubKonnectCode) }
    val selectedNet = availableNetworks.firstOrNull { it.clubKonnectCode == selectedNetCode } ?: availableNetworks.first()

    val networkItems = remember(liveItems, selectedNetCode) {
        liveItems.filter { it.providerCode == selectedNetCode }
    }
    val denoms = remember(networkItems) {
        if (networkItems.isNotEmpty()) {
            networkItems.mapNotNull { it.productCode.toLongOrNull() }.distinct().sorted()
        } else {
            NigerianProviders.rechargeCardDenominationsNaira
        }
    }
    var selectedDenomNaira by remember(denoms) { mutableStateOf(denoms.firstOrNull() ?: 100L) }
    var quantity by remember { mutableIntStateOf(5) }

    val matchedItem = networkItems.firstOrNull { it.productCode == selectedDenomNaira.toString() }
    val unitSellingKobo = matchedItem?.sellingPriceKobo ?: (selectedDenomNaira * 100L)
    val totalKobo = unitSellingKobo * quantity

    ServiceFormCard(title = "Recharge Card Printing") {
        CompactStockStatusPill(
            title = "Voucher Stock",
            isLoading = isLoadingLive,
            available = isAvailable,
            statusMessage = statusMsg,
            onRefresh = onRefreshAvailability
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("1. Choose Mobile Network", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        NetworkTileSelector(
            networks = availableNetworks,
            selectedCode = selectedNet.clubKonnectCode,
            onSelect = { selectedNetCode = it.clubKonnectCode }
        )

        Spacer(modifier = Modifier.height(16.dp))
        Text("2. Select Voucher Denomination", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            denoms.chunked(2).forEach { rowDenoms ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowDenoms.forEach { denom ->
                        val selected = selectedDenomNaira == denom
                        val itemPriceKobo = networkItems
                            .firstOrNull { it.productCode == denom.toString() }
                            ?.sellingPriceKobo
                            ?: (denom * 100L)
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            border = BorderStroke(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedDenomNaira = denom }
                                .testTag("epin_denom_$denom")
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "₦$denom",
                                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono),
                                        fontWeight = FontWeight.ExtraBold,
                                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                    )
                                    if (selected) {
                                        Icon(
                                            imageVector = Icons.Filled.CheckCircle,
                                            contentDescription = "Selected",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "${selectedNet.shortCode} Recharge PIN",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Unit: ${CurrencyFormatter.formatKobo(itemPriceKobo)}",
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                    color = MaterialTheme.colorScheme.secondary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    if (rowDenoms.size == 1) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        QuantityStepperSection(
            label = "3. Number of e-PINs",
            quantity = quantity,
            presets = listOf(1, 5, 10, 20),
            onQuantityChange = { quantity = it }
        )

        Spacer(modifier = Modifier.height(16.dp))
        // Voucher Print Preview Summary Card
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = NavyDeep),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.linearGradient(listOf(NavyDeep, NavyContainer)))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ConfirmationNumber,
                            contentDescription = null,
                            tint = EmeraldBright,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${selectedNet.shortCode} ₦$selectedDenomNaira × $quantity PIN${if (quantity > 1) "s" else ""}",
                            style = MaterialTheme.typography.titleSmall,
                            color = PureWhite,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Surface(
                        color = EmeraldBright.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = "PDF Ready",
                            style = MaterialTheme.typography.labelSmall,
                            color = EmeraldBright,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
                Text(
                    text = "Dial *311*PIN# to load • Printable A4 PDF & thermal voucher sheet saved in History",
                    style = MaterialTheme.typography.bodySmall,
                    color = PureWhite.copy(alpha = 0.78f)
                )
                HorizontalDivider(color = PureWhite.copy(alpha = 0.14f))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Total Payable",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PureWhite.copy(alpha = 0.85f)
                    )
                    Text(
                        text = CurrencyFormatter.formatKobo(totalKobo),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = JetBrainsMono),
                        color = PureWhite,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = {
                val title = "${selectedNet.shortCode} ₦$selectedDenomNaira Recharge e-PIN (x$quantity)"
                onCreateDraft(
                    PendingPurchaseDraft(
                        serviceType = ServiceType.RECHARGE_CARD.name,
                        providerCode = selectedNet.clubKonnectCode,
                        productCode = selectedDenomNaira.toString(),
                        productTitle = title,
                        recipient = profile.displayTitle(),
                        amountKobo = totalKobo,
                        quantity = quantity,
                        summary = PurchaseSummaryItem(
                            title = title,
                            recipient = profile.displayTitle(),
                            providerName = selectedNet.name,
                            sellingPriceKobo = totalKobo,
                            feeKobo = 0L,
                            walletBalanceKobo = profile.walletBalanceKobo
                        )
                    )
                )
            },
            enabled = isAvailable && !isLoadingLive,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .testTag("epin_continue_button")
        ) {
            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAvailable) "Purchase & Print e-PINs (${CurrencyFormatter.formatKobo(totalKobo)})" else "Currently Out of Stock",
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
internal fun DataCardEpinForm(
    profile: UserProfile,
    isLoadingLive: Boolean,
    group: LiveServiceAvailabilityGroupDto?,
    onRefreshAvailability: () -> Unit,
    onCreateDraft: (PendingPurchaseDraft) -> Unit
) {
    val isAvailable = group?.available == true
    val statusMsg = group?.statusMessage?.takeIf { it.isNotBlank() }
        ?: if (isLoadingLive) "Checking live printable Data Card stock…"
        else "Tap Refresh to check current printable Data Card availability."

    val liveItems = group?.items.orEmpty()
    val availableNetworks = remember(liveItems) {
        if (liveItems.isEmpty()) NigerianProviders.telecomNetworks
        else {
            val codes = liveItems.map { it.providerCode }.toSet()
            NigerianProviders.telecomNetworks.filter { it.clubKonnectCode in codes }
                .ifEmpty { NigerianProviders.telecomNetworks }
        }
    }
    var selectedNetCode by remember(availableNetworks) { mutableStateOf(availableNetworks.first().clubKonnectCode) }
    val networkPlans = remember(liveItems, selectedNetCode) {
        liveItems.filter { it.providerCode == selectedNetCode }
            .sortedBy { it.sellingPriceKobo + it.feeKobo }
    }
    var selectedPlan by remember(networkPlans) { mutableStateOf(networkPlans.firstOrNull()) }
    var quantity by remember { mutableIntStateOf(1) }

    val unitKobo = (selectedPlan?.sellingPriceKobo ?: 0L) + (selectedPlan?.feeKobo ?: 0L)
    val totalKobo = unitKobo * quantity

    ServiceFormCard(title = "Data Card Printing (e-PIN)") {
        CompactStockStatusPill(
            title = "Data Card Stock",
            isLoading = isLoadingLive,
            available = isAvailable,
            statusMessage = statusMsg,
            onRefresh = onRefreshAvailability
        )
        Spacer(modifier = Modifier.height(14.dp))

        if (isAvailable && liveItems.isNotEmpty()) {
            Text("1. Choose Mobile Network", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            NetworkTileSelector(
                networks = availableNetworks,
                selectedCode = selectedNetCode,
                onSelect = { selectedNetCode = it.clubKonnectCode }
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text("2. Select Printable Data Card Plan", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                networkPlans.forEach { plan ->
                    CatalogPlanCard(
                        title = plan.productName,
                        subtitle = listOf(plan.dataVolume, plan.validity).filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "Printable Data PIN" },
                        priceKobo = plan.sellingPriceKobo + plan.feeKobo,
                        selected = selectedPlan?.id == plan.id,
                        onClick = { selectedPlan = plan }
                    )
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            QuantityStepperSection(
                label = "3. Number of Data Cards",
                quantity = quantity,
                presets = listOf(1, 5, 10, 20),
                onQuantityChange = { quantity = it }
            )
            Spacer(modifier = Modifier.height(14.dp))
            PricePreviewBar("Total for $quantity Data Card e-PINs", totalKobo)
            Spacer(modifier = Modifier.height(14.dp))
            Button(
                onClick = {
                    val plan = selectedPlan ?: return@Button
                    val title = "${plan.productName} (x$quantity)"
                    onCreateDraft(
                        PendingPurchaseDraft(
                            serviceType = ServiceType.DATA_CARD.name,
                            providerCode = plan.providerCode,
                            productCode = plan.productCode,
                            productTitle = title,
                            recipient = profile.displayTitle(),
                            amountKobo = totalKobo,
                            quantity = quantity,
                            summary = PurchaseSummaryItem(
                                title = title,
                                recipient = profile.displayTitle(),
                                providerName = plan.providerName,
                                sellingPriceKobo = plan.sellingPriceKobo * quantity,
                                feeKobo = plan.feeKobo * quantity,
                                walletBalanceKobo = profile.walletBalanceKobo
                            )
                        )
                    )
                },
                enabled = selectedPlan != null && !isLoadingLive,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("datacard_continue_button")
            ) {
                Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Purchase & Print Data Card PDF", fontWeight = FontWeight.Bold)
            }
        } else {
            OutlinedButton(
                onClick = onRefreshAvailability,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("datacard_refresh_button")
            ) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Check Data Card Availability Again")
            }
        }
    }
}

@Composable
internal fun BroadbandServiceForm(
    profile: UserProfile,
    broadbandPlans: List<LiveCatalogPlanDto>,
    smileEnabled: Boolean = true,
    spectranetEnabled: Boolean = true,
    verifiedCustomer: CustomerVerificationResponse?,
    isSubmitting: Boolean,
    onClearVerification: () -> Unit,
    onVerifySmile: (String) -> Unit,
    onValidationError: (String) -> Unit,
    onCreateDraft: (PendingPurchaseDraft) -> Unit
) {
    var provider by remember(smileEnabled, spectranetEnabled) {
        mutableStateOf(if (smileEnabled) "SMILE" else "SPECTRANET")
    }
    var smileAccount by remember { mutableStateOf("") }
    var spectranetQty by remember { mutableIntStateOf(1) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }

    val activePlans = remember(broadbandPlans, provider) {
        broadbandPlans.filter { it.serviceType.equals(provider, ignoreCase = true) }
    }
    val groupedAllPlans = remember(activePlans) {
        CatalogCategoryClassifier.groupAndSort(
            plans = activePlans,
            order = CatalogCategoryClassifier.broadbandCategoryOrder,
            classifier = CatalogCategoryClassifier::resolveBroadbandCategory
        )
    }
    val categoryCounts = remember(groupedAllPlans) {
        groupedAllPlans.map { (cat, list) -> cat to list.size }
    }
    val effectiveCategory = remember(selectedCategory, categoryCounts) {
        if (selectedCategory == "All" || categoryCounts.any { it.first == selectedCategory }) {
            selectedCategory
        } else {
            "All"
        }
    }
    val filteredGroupedPlans = remember(groupedAllPlans, effectiveCategory, searchQuery) {
        val q = searchQuery.trim()
        groupedAllPlans
            .filter { (cat, _) -> effectiveCategory == "All" || cat == effectiveCategory }
            .mapNotNull { (cat, items) ->
                val matching = if (q.isEmpty()) items else items.filter {
                    it.productName.contains(q, ignoreCase = true) ||
                        it.validity.contains(q, ignoreCase = true) ||
                        it.dataVolume.contains(q, ignoreCase = true) ||
                        cat.contains(q, ignoreCase = true)
                }
                if (matching.isEmpty()) null else cat to matching
            }
    }
    val visibleCount = filteredGroupedPlans.sumOf { it.second.size }
    var selectedPlan by remember(activePlans) { mutableStateOf(activePlans.firstOrNull()) }

    ServiceFormCard(title = "Broadband Internet (${activePlans.size} Packages)") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (smileEnabled) {
                FilterChip(
                    selected = provider == "SMILE",
                    onClick = {
                        provider = "SMILE"
                        selectedCategory = "All"
                        searchQuery = ""
                        onClearVerification()
                    },
                    label = { Text("Smile 4G Direct") }
                )
            }
            if (spectranetEnabled) {
                FilterChip(
                    selected = provider == "SPECTRANET",
                    onClick = {
                        provider = "SPECTRANET"
                        selectedCategory = "All"
                        searchQuery = ""
                        onClearVerification()
                    },
                    label = { Text("Spectranet e-PIN") }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        if (provider == "SMILE") {
            OutlinedTextField(
                value = smileAccount,
                onValueChange = { smileAccount = it.filter { c -> c.isDigit() } },
                label = { Text("Smile Account / Phone Number") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    if (smileAccount.length < 8) {
                        onValidationError("Enter a valid Smile account or phone number.")
                    } else {
                        onVerifySmile(smileAccount)
                    }
                },
                enabled = !isSubmitting,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) {
                Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Verify Smile Account")
            }
            if (verifiedCustomer?.verified == true) {
                Spacer(modifier = Modifier.height(8.dp))
                VerifiedCustomerBox(verifiedCustomer)
            }
        } else {
            QuantityStepperSection(
                label = "Spectranet PIN Quantity",
                quantity = spectranetQty,
                presets = listOf(1, 2, 5, 10),
                onQuantityChange = { spectranetQty = it }
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = {
                Text(
                    if (provider == "SMILE") "Search Smile packages (e.g. Unlimited, 10GB, Monthly)"
                    else "Search Spectranet packages (e.g. Unified, Unlimited)"
                )
            },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search packages") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("broadband_search_input")
        )

        if (categoryCounts.size > 1) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "Package Category",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            PlanCategoryFilterStrip(
                categories = categoryCounts,
                totalCount = activePlans.size,
                selectedCategory = effectiveCategory,
                onSelectCategory = { selectedCategory = it },
                testTagPrefix = "broadband"
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (effectiveCategory == "All") {
                "Showing $visibleCount packages grouped by category"
            } else {
                "Showing $visibleCount $effectiveCategory packages (lowest to highest)"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (filteredGroupedPlans.isEmpty()) {
            Text(
                text = if (activePlans.isEmpty()) "No active $provider packages available right now. Tap Refresh at the top to retry."
                else "No packages match your filter. Select All or clear search.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredGroupedPlans.forEach { (categoryName, categoryPlans) ->
                    if (effectiveCategory == "All" && filteredGroupedPlans.size > 1) {
                        PlanCategorySectionHeader(
                            categoryName = categoryName,
                            planCount = categoryPlans.size
                        )
                    }
                    categoryPlans.forEach { plan ->
                        CatalogPlanCard(
                            title = plan.productName,
                            subtitle = listOf(
                                categoryName,
                                plan.dataVolume,
                                plan.validity.ifBlank { if (provider == "SPECTRANET") "Printable Broadband PIN" else "Direct Smile Top-Up" }
                            ).filter { it.isNotBlank() }.distinct().joinToString(" • "),
                            priceKobo = plan.sellingPriceKobo + plan.feeKobo,
                            selected = selectedPlan?.id == plan.id,
                            onClick = { selectedPlan = plan }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = {
                val plan = selectedPlan
                if (plan == null) {
                    onValidationError("Select a broadband package first.")
                } else if (provider == "SMILE" && smileAccount.length < 8) {
                    onValidationError("Enter a valid Smile account or phone number.")
                } else {
                    val qty = if (provider == "SPECTRANET") spectranetQty else 1
                    val total = (plan.sellingPriceKobo + plan.feeKobo) * qty
                    val recipient = if (provider == "SMILE") smileAccount else profile.displayTitle()
                    onCreateDraft(
                        PendingPurchaseDraft(
                            serviceType = provider,
                            providerCode = plan.providerCode,
                            productCode = plan.productCode,
                            productTitle = plan.productName,
                            recipient = recipient,
                            amountKobo = total,
                            quantity = qty,
                            summary = PurchaseSummaryItem(
                                title = plan.productName,
                                recipient = verifiedCustomer?.customerName?.let { "$recipient ($it)" } ?: recipient,
                                providerName = plan.providerName,
                                sellingPriceKobo = plan.sellingPriceKobo * qty,
                                feeKobo = plan.feeKobo * qty,
                                walletBalanceKobo = profile.walletBalanceKobo
                            )
                        )
                    )
                }
            },
            enabled = selectedPlan != null,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("broadband_continue_button")
        ) {
            Text("Review & Pay", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun BettingServiceForm(
    profile: UserProfile,
    bettingProviders: List<LiveCatalogPlanDto>,
    verifiedCustomer: CustomerVerificationResponse?,
    isSubmitting: Boolean,
    onClearVerification: () -> Unit,
    onVerifyBetting: (String, String) -> Unit,
    onValidationError: (String) -> Unit,
    onCreateDraft: (PendingPurchaseDraft) -> Unit
) {
    val providers = remember(bettingProviders) {
        bettingProviders.ifEmpty {
            listOf(
                LiveCatalogPlanDto(id = "bet_bet9ja", serviceType = "BETTING", providerCode = "Bet9ja", providerName = "Bet9ja", productCode = "Bet9ja", productName = "Bet9ja Wallet"),
                LiveCatalogPlanDto(id = "bet_sportybet", serviceType = "BETTING", providerCode = "SportyBet", providerName = "SportyBet", productCode = "SportyBet", productName = "SportyBet Wallet"),
                LiveCatalogPlanDto(id = "bet_1xbet", serviceType = "BETTING", providerCode = "1xBet", providerName = "1xBet", productCode = "1xBet", productName = "1xBet Wallet"),
                LiveCatalogPlanDto(id = "bet_betking", serviceType = "BETTING", providerCode = "BetKing", providerName = "BetKing", productCode = "BetKing", productName = "BetKing Wallet")
            )
        }
    }
    var selectedProvider by remember(providers) { mutableStateOf(providers.first()) }
    var customerId by remember { mutableStateOf("") }
    var amountNaira by remember { mutableStateOf("1000") }

    val amountKobo = CurrencyFormatter.nairaToKobo(amountNaira) ?: 0L
    val feeKobo = selectedProvider.feeKobo

    ServiceFormCard(title = "Betting Wallet Funding") {
        Text("Select Betting Platform (${providers.size})", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            providers.forEach { company ->
                FilterChip(
                    selected = selectedProvider.providerCode.equals(company.providerCode, ignoreCase = true),
                    onClick = {
                        selectedProvider = company
                        onClearVerification()
                    },
                    label = { Text(company.providerName) }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = customerId,
            onValueChange = { customerId = it.trim() },
            label = { Text("Customer / User ID") },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = {
                if (customerId.length < 3) {
                    onValidationError("Enter a valid betting customer ID.")
                } else {
                    onVerifyBetting(selectedProvider.providerCode, customerId)
                }
            },
            enabled = !isSubmitting,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().height(46.dp)
        ) {
            Icon(Icons.Filled.Verified, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Verify ${selectedProvider.providerName} ID")
        }
        if (verifiedCustomer?.verified == true) {
            Spacer(modifier = Modifier.height(8.dp))
            VerifiedCustomerBox(verifiedCustomer)
        }
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedTextField(
            value = amountNaira,
            onValueChange = { amountNaira = it.filter { c -> c.isDigit() } },
            label = { Text("Amount (₦) — Min ₦100") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        PricePreviewBar("Total Payable", amountKobo + feeKobo)
        Spacer(modifier = Modifier.height(14.dp))
        Button(
            onClick = {
                if (customerId.length < 3) {
                    onValidationError("Enter a valid betting customer ID.")
                } else if (amountKobo < 10000L) {
                    onValidationError("Minimum betting wallet funding is ₦100.00.")
                } else {
                    val title = "${selectedProvider.providerName} Wallet Funding"
                    onCreateDraft(
                        PendingPurchaseDraft(
                            serviceType = ServiceType.BETTING.name,
                            providerCode = selectedProvider.providerCode,
                            productCode = selectedProvider.productCode,
                            productTitle = title,
                            recipient = customerId,
                            amountKobo = amountKobo,
                            summary = PurchaseSummaryItem(
                                title = title,
                                recipient = verifiedCustomer?.customerName?.let { "$customerId ($it)" } ?: customerId,
                                providerName = selectedProvider.providerName,
                                sellingPriceKobo = amountKobo,
                                feeKobo = feeKobo,
                                walletBalanceKobo = profile.walletBalanceKobo
                            )
                        )
                    )
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .testTag("betting_continue_button")
        ) {
            Text("Review & Pay", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun ServiceFormCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
internal fun CatalogPlanCard(
    title: String,
    subtitle: String,
    priceKobo: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = CurrencyFormatter.formatKobo(priceKobo),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
internal fun VerifiedCustomerBox(verified: CustomerVerificationResponse) {
    Surface(
        color = StatusSuccessBg,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.CheckCircle, contentDescription = "Verified", tint = StatusSuccess)
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = verified.customerName ?: "Customer Verified",
                    style = MaterialTheme.typography.titleSmall,
                    color = StatusSuccess,
                    fontWeight = FontWeight.Bold
                )
                if (!verified.customerAddress.isNullOrBlank()) {
                    Text(
                        text = verified.customerAddress,
                        style = MaterialTheme.typography.bodySmall,
                        color = StatusSuccess
                    )
                }
            }
        }
    }
}

@Composable
internal fun PricePreviewBar(label: String, amountKobo: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = CurrencyFormatter.formatKobo(amountKobo),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}
