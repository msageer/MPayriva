package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.model.NigerianProviders
import com.example.data.model.ServiceType
import com.example.data.model.UserProfile
import com.example.data.remote.CustomerVerificationResponse
import com.example.data.remote.LiveCatalogPlanDto
import com.example.data.remote.LiveServiceAvailabilityGroupDto
import com.example.data.remote.LiveStorefrontCatalogResponse
import com.example.ui.components.PurchaseSummaryItem
import com.example.ui.theme.JetBrainsMono
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
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
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
                        text = "Fetching live ClubKonnect services & e-PIN stock…",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        is UiState.Success -> {
            val data = liveCatalogState.data
            Surface(
                color = if (data.clubKonnectConnected) StatusSuccessBg else StatusPendingBg,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (data.clubKonnectConnected) StatusSuccess.copy(alpha = 0.25f) else StatusPending.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (data.clubKonnectConnected) Icons.Filled.CloudDone else Icons.Filled.Warning,
                        contentDescription = "Live catalog status",
                        tint = if (data.clubKonnectConnected) StatusSuccess else StatusPending,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (data.clubKonnectConnected) {
                                "ClubKonnect Live • ${data.totalPlans} plans active"
                            } else {
                                "ClubKonnect Fallback Mode • ${data.totalPlans} plans"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (data.clubKonnectConnected) StatusSuccess else StatusPending,
                            fontWeight = FontWeight.Bold
                        )
                        val rcTag = if (data.rechargeCards.available) "Recharge PINs: Available" else "Recharge PINs: Unavailable"
                        val dcTag = if (data.dataCards.available) "Data Cards: Available" else "Data Cards: Unavailable"
                        Text(
                            text = "$rcTag • $dcTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(
                        onClick = onRefresh,
                        modifier = Modifier.testTag("refresh_live_catalog_button")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = "Refresh live ClubKonnect plans",
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
                        contentDescription = "Catalog offline",
                        tint = StatusFailed,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = liveCatalogState.message,
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
        ?: if (isLoadingLive) "Checking live ClubKonnect e-PIN inventory…"
        else "Live ClubKonnect recharge card inventory has not loaded yet. Tap Refresh to check."

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

    ServiceFormCard(title = "Recharge Card Printing (e-PIN)") {
        ServiceAvailabilityBanner(
            title = "ClubKonnect e-PINs",
            isLoading = isLoadingLive,
            available = isAvailable,
            statusMessage = statusMsg,
            onCheckAvailability = onRefreshAvailability
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text("Select Network", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            availableNetworks.forEach { net ->
                FilterChip(
                    selected = selectedNet.clubKonnectCode == net.clubKonnectCode,
                    onClick = { selectedNetCode = net.clubKonnectCode },
                    label = { Text(net.shortCode) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text("Denomination", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            denoms.forEach { denom ->
                FilterChip(
                    selected = selectedDenomNaira == denom,
                    onClick = { selectedDenomNaira = denom },
                    label = { Text("₦$denom") }
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text("Quantity: $quantity PINs", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(1, 5, 10, 20).forEach { q ->
                FilterChip(
                    selected = quantity == q,
                    onClick = { quantity = q },
                    label = { Text("$q") }
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        PricePreviewBar(
            label = "Total for $quantity × ₦$selectedDenomNaira e-PINs",
            amountKobo = totalKobo
        )
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
                .height(50.dp)
                .testTag("epin_continue_button")
        ) {
            Icon(Icons.Filled.Print, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (isAvailable) "Purchase & Generate Printable PDF" else "Currently Unavailable on ClubKonnect",
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
        ?: if (isLoadingLive) "Checking live ClubKonnect printable Data Card plans…"
        else "Live ClubKonnect Data Card availability has not loaded yet. Tap Refresh to check."

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
    }
    var selectedPlan by remember(networkPlans) { mutableStateOf(networkPlans.firstOrNull()) }
    var quantity by remember { mutableIntStateOf(1) }

    val unitKobo = (selectedPlan?.sellingPriceKobo ?: 0L) + (selectedPlan?.feeKobo ?: 0L)
    val totalKobo = unitKobo * quantity

    ServiceFormCard(title = "Data Card Printing (e-PIN)") {
        ServiceAvailabilityBanner(
            title = "ClubKonnect Data Cards",
            isLoading = isLoadingLive,
            available = isAvailable,
            statusMessage = statusMsg,
            onCheckAvailability = onRefreshAvailability
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (isAvailable && liveItems.isNotEmpty()) {
            Text("Select Network", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                availableNetworks.forEach { net ->
                    FilterChip(
                        selected = selectedNetCode == net.clubKonnectCode,
                        onClick = { selectedNetCode = net.clubKonnectCode },
                        label = { Text(net.shortCode) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text("Select Printable Data Card Plan", style = MaterialTheme.typography.labelLarge)
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
            Spacer(modifier = Modifier.height(10.dp))
            Text("Quantity: $quantity Data Card${if (quantity > 1) "s" else ""}", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 5, 10, 20).forEach { q ->
                    FilterChip(
                        selected = quantity == q,
                        onClick = { quantity = q },
                        label = { Text("$q") }
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
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
                    .height(50.dp)
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
                Text("Re-Check Data Card Stock on ClubKonnect")
            }
        }
    }
}

@Composable
internal fun BroadbandServiceForm(
    profile: UserProfile,
    broadbandPlans: List<LiveCatalogPlanDto>,
    verifiedCustomer: CustomerVerificationResponse?,
    isSubmitting: Boolean,
    onClearVerification: () -> Unit,
    onVerifySmile: (String) -> Unit,
    onValidationError: (String) -> Unit,
    onCreateDraft: (PendingPurchaseDraft) -> Unit
) {
    var provider by remember { mutableStateOf("SMILE") }
    var smileAccount by remember { mutableStateOf("") }
    var spectranetQty by remember { mutableIntStateOf(1) }

    val activePlans = remember(broadbandPlans, provider) {
        broadbandPlans.filter { it.serviceType.equals(provider, ignoreCase = true) }
    }
    var selectedPlan by remember(activePlans) { mutableStateOf(activePlans.firstOrNull()) }

    ServiceFormCard(title = "Broadband Internet (Smile & Spectranet)") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilterChip(
                selected = provider == "SMILE",
                onClick = {
                    provider = "SMILE"
                    onClearVerification()
                },
                label = { Text("Smile 4G Direct") }
            )
            FilterChip(
                selected = provider == "SPECTRANET",
                onClick = {
                    provider = "SPECTRANET"
                    onClearVerification()
                },
                label = { Text("Spectranet e-PIN") }
            )
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
            Text("Quantity: $spectranetQty PIN${if (spectranetQty > 1) "s" else ""}", style = MaterialTheme.typography.labelLarge)
            Spacer(modifier = Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 5).forEach { q ->
                    FilterChip(
                        selected = spectranetQty == q,
                        onClick = { spectranetQty = q },
                        label = { Text("$q") }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("Live ClubKonnect Packages (${activePlans.size})", style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(8.dp))
        if (activePlans.isEmpty()) {
            Text(
                text = "No active $provider packages returned by ClubKonnect right now. Tap Refresh at the top to retry.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                activePlans.forEach { plan ->
                    CatalogPlanCard(
                        title = plan.productName,
                        subtitle = plan.validity.ifBlank { if (provider == "SPECTRANET") "Printable Broadband PIN" else "Direct Smile Top-Up" },
                        priceKobo = plan.sellingPriceKobo + plan.feeKobo,
                        selected = selectedPlan?.id == plan.id,
                        onClick = { selectedPlan = plan }
                    )
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
                            amountKobo = amountKobo + feeKobo,
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
