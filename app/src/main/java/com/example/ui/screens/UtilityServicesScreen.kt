package com.example.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.model.NigerianProviders
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.ServiceType
import com.example.data.model.UserProfile
import com.example.data.remote.CustomerVerificationResponse
import com.example.ui.components.InlineStatusBanner
import com.example.ui.components.PurchaseSummaryItem
import com.example.ui.components.TransactionPinConfirmationDialog
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusSuccessBg
import java.util.UUID

private data class PendingPurchaseDraft(
    val serviceType: String,
    val providerCode: String,
    val productCode: String,
    val recipient: String,
    val amountKobo: Long,
    val meterType: String? = null,
    val quantity: Int = 1,
    val summary: PurchaseSummaryItem,
    val idempotencyKey: String = "tx_${UUID.randomUUID()}"
)

@Composable
fun UtilityServicesScreen(
    initialService: ServiceType,
    profile: UserProfile,
    catalog: List<ServiceCatalogItem>,
    verifiedCustomer: CustomerVerificationResponse?,
    isSubmitting: Boolean,
    onClearVerification: () -> Unit,
    onVerifyMeter: (electricCompany: String, meterNo: String, meterType: String) -> Unit,
    onVerifySmartCard: (cableTv: String, smartCardNo: String) -> Unit,
    onAuthorizePurchase: (
        serviceType: String,
        providerCode: String,
        productCode: String,
        recipient: String,
        amountKobo: Long,
        meterType: String?,
        quantity: Int,
        pin: String,
        idempotencyKey: String,
        onFinished: () -> Unit
    ) -> Unit
) {
    var selectedTab by remember(initialService) { mutableStateOf(initialService) }
    var pendingDraft by remember { mutableStateOf<PendingPurchaseDraft?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Form states
    var selectedNetwork by remember { mutableStateOf(NigerianProviders.telecomNetworks.first()) }
    var phoneInput by remember { mutableStateOf(CurrencyFormatter.toLocalElevenDigitPhone(profile.phoneNumber)) }
    var amountNairaInput by remember { mutableStateOf("500") }
    var selectedDataPlan by remember { mutableStateOf<ServiceCatalogItem?>(null) }

    var selectedDisco by remember { mutableStateOf(NigerianProviders.electricityDiscos.first()) }
    var meterType by remember { mutableStateOf("prepaid") }
    var meterNumber by remember { mutableStateOf("") }
    var powerAmountNaira by remember { mutableStateOf("5000") }

    var selectedCable by remember { mutableStateOf(NigerianProviders.cableProviders.first()) }
    var smartCardNo by remember { mutableStateOf("") }
    var selectedCablePkg by remember { mutableStateOf<ServiceCatalogItem?>(null) }

    var selectedExamType by remember { mutableStateOf(ServiceType.WAEC_PIN) }
    var jambProfileId by remember { mutableStateOf("") }
    var examQuantity by remember { mutableIntStateOf(1) }

    var rechargeDenomNaira by remember { mutableStateOf(100L) }
    var rechargeQty by remember { mutableIntStateOf(5) }

    LaunchedEffect(selectedTab) {
        validationError = null
        onClearVerification()
    }

    val serviceTabs = listOf(
        ServiceType.AIRTIME to Icons.Filled.PhoneAndroid,
        ServiceType.DATA to Icons.Filled.Wifi,
        ServiceType.ELECTRICITY to Icons.Filled.Bolt,
        ServiceType.CABLE_TV to Icons.Filled.Tv,
        ServiceType.RECHARGE_CARD to Icons.Filled.ConfirmationNumber,
        ServiceType.WAEC_PIN to Icons.Filled.School
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text(
                    text = "Utility Services",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Powered by ClubKonnect trusted backend • Live catalog pricing",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Category Selector Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                serviceTabs.forEach { (type, icon) ->
                    val label = if (type == ServiceType.WAEC_PIN) "WAEC / JAMB" else type.label
                    FilterChip(
                        selected = selectedTab == type || (type == ServiceType.WAEC_PIN && selectedTab == ServiceType.JAMB_PIN),
                        onClick = { selectedTab = type },
                        label = { Text(label) },
                        leadingIcon = {
                            Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(16.dp))
                        },
                        modifier = Modifier.testTag("service_tab_${type.name.lowercase()}")
                    )
                }
            }
        }

        if (validationError != null) {
            item {
                InlineStatusBanner(
                    message = validationError!!,
                    isError = true,
                    onDismiss = { validationError = null }
                )
            }
        }

        // Active Service Form
        item {
            when (selectedTab) {
                ServiceType.AIRTIME -> {
                    val airtimeCatalog = catalog.firstOrNull {
                        it.resolvedServiceType() == ServiceType.AIRTIME && it.providerCode == selectedNetwork.clubKonnectCode
                    }
                    val discountPct = airtimeCatalog?.discountPercent ?: 0.0
                    val feeKobo = airtimeCatalog?.feeKobo ?: 0L
                    val faceKobo = CurrencyFormatter.nairaToKobo(amountNairaInput) ?: 0L
                    val sellingKobo = Math.round(faceKobo * (1.0 - discountPct / 100.0))

                    ServiceFormCard(title = "Airtime Top-Up") {
                        Text("Select Mobile Network", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NigerianProviders.telecomNetworks.forEach { net ->
                                FilterChip(
                                    selected = selectedNetwork.clubKonnectCode == net.clubKonnectCode,
                                    onClick = { selectedNetwork = net },
                                    label = { Text(net.shortCode) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("Recipient Phone Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("airtime_phone_input")
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = amountNairaInput,
                            onValueChange = { amountNairaInput = it.filter { c -> c.isDigit() } },
                            label = { Text("Amount (₦)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("airtime_amount_input")
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf("100", "200", "500", "1000", "2000").forEach { preset ->
                                FilterChip(
                                    selected = amountNairaInput == preset,
                                    onClick = { amountNairaInput = preset },
                                    label = { Text("₦$preset", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        PricePreviewBar(
                            label = if (discountPct > 0) "Payable (${discountPct}% discount)" else "Payable Total",
                            amountKobo = sellingKobo + feeKobo
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                validationError = null
                                if (!CurrencyFormatter.isValidNigerianPhone(phoneInput)) {
                                    validationError = "Enter a valid Nigerian phone number (e.g. 08012345678)."
                                } else if (faceKobo < 5000L) {
                                    validationError = "Minimum airtime recharge is ₦50.00."
                                } else {
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = ServiceType.AIRTIME.name,
                                        providerCode = selectedNetwork.clubKonnectCode,
                                        productCode = "airtime",
                                        recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                        amountKobo = sellingKobo + feeKobo,
                                        summary = PurchaseSummaryItem(
                                            title = "${selectedNetwork.shortCode} Airtime (${CurrencyFormatter.formatKobo(faceKobo)})",
                                            recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                            providerName = selectedNetwork.name,
                                            sellingPriceKobo = sellingKobo,
                                            feeKobo = feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo
                                        )
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("airtime_continue_button")
                        ) {
                            Text("Review & Pay", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ServiceType.DATA -> {
                    val dataPlans = catalog.filter {
                        it.resolvedServiceType() == ServiceType.DATA && it.providerCode == selectedNetwork.clubKonnectCode
                    }
                    ServiceFormCard(title = "Mobile Data Bundles") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NigerianProviders.telecomNetworks.forEach { net ->
                                FilterChip(
                                    selected = selectedNetwork.clubKonnectCode == net.clubKonnectCode,
                                    onClick = {
                                        selectedNetwork = net
                                        selectedDataPlan = null
                                    },
                                    label = { Text(net.shortCode) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("Beneficiary Phone Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Text("Available Plans (${selectedNetwork.shortCode})", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(8.dp))

                        if (dataPlans.isEmpty()) {
                            Text(
                                text = "No active ${selectedNetwork.shortCode} data plans configured in Firestore yet. Platform Admin can seed or sync plans from the Admin Pricing panel.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                dataPlans.forEach { plan ->
                                    val chosen = selectedDataPlan?.id == plan.id
                                    CatalogPlanCard(
                                        title = plan.productName,
                                        subtitle = listOfNotNull(plan.dataVolume, plan.validity).joinToString(" • "),
                                        priceKobo = plan.sellingPriceKobo + plan.feeKobo,
                                        selected = chosen,
                                        onClick = { selectedDataPlan = plan }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                validationError = null
                                val plan = selectedDataPlan
                                if (!CurrencyFormatter.isValidNigerianPhone(phoneInput)) {
                                    validationError = "Enter a valid Nigerian phone number."
                                } else if (plan == null) {
                                    validationError = "Select a data bundle plan first."
                                } else {
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = ServiceType.DATA.name,
                                        providerCode = plan.providerCode,
                                        productCode = plan.productCode,
                                        recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                        amountKobo = plan.sellingPriceKobo + plan.feeKobo,
                                        summary = PurchaseSummaryItem(
                                            title = plan.productName,
                                            recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                            providerName = plan.providerName,
                                            sellingPriceKobo = plan.sellingPriceKobo,
                                            feeKobo = plan.feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo
                                        )
                                    )
                                }
                            },
                            enabled = selectedDataPlan != null,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("data_continue_button")
                        ) {
                            Text("Review & Pay", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ServiceType.ELECTRICITY -> {
                    val elecConfig = catalog.firstOrNull { it.resolvedServiceType() == ServiceType.ELECTRICITY }
                    val feeKobo = elecConfig?.feeKobo ?: 10000L
                    val powerKobo = CurrencyFormatter.nairaToKobo(powerAmountNaira) ?: 0L

                    ServiceFormCard(title = "Electricity Bill Payment") {
                        Text("Distribution Company (DisCo)", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NigerianProviders.electricityDiscos.forEach { disco ->
                                FilterChip(
                                    selected = selectedDisco.clubKonnectCode == disco.clubKonnectCode,
                                    onClick = {
                                        selectedDisco = disco
                                        onClearVerification()
                                    },
                                    label = { Text(disco.shortName) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = meterType == "prepaid",
                                onClick = { meterType = "prepaid" },
                                label = { Text("Prepaid Meter") }
                            )
                            FilterChip(
                                selected = meterType == "postpaid",
                                onClick = { meterType = "postpaid" },
                                label = { Text("Postpaid Meter") }
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = meterNumber,
                            onValueChange = { meterNumber = it.filter { c -> c.isDigit() } },
                            label = { Text("Meter Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (meterNumber.length < 8) {
                                    validationError = "Enter a valid meter number to verify."
                                } else {
                                    validationError = null
                                    onVerifyMeter(selectedDisco.clubKonnectCode, meterNumber, meterType)
                                }
                            },
                            enabled = !isSubmitting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("verify_meter_button")
                        ) {
                            Icon(Icons.Filled.Verified, contentDescription = "Verify Meter", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verify Meter with ${selectedDisco.shortName}")
                        }

                        if (verifiedCustomer?.verified == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            VerifiedCustomerBox(verifiedCustomer)
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = powerAmountNaira,
                            onValueChange = { powerAmountNaira = it.filter { c -> c.isDigit() } },
                            label = { Text("Amount (₦) — Min ₦1,000") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        PricePreviewBar(
                            label = "Total (incl. ${CurrencyFormatter.formatKobo(feeKobo)} fee)",
                            amountKobo = powerKobo + feeKobo
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                validationError = null
                                if (meterNumber.length < 8) {
                                    validationError = "Enter a valid meter number."
                                } else if (powerKobo < 100000L) {
                                    validationError = "Minimum electricity vend is ₦1,000.00."
                                } else {
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = ServiceType.ELECTRICITY.name,
                                        providerCode = selectedDisco.clubKonnectCode,
                                        productCode = meterType,
                                        recipient = meterNumber,
                                        amountKobo = powerKobo + feeKobo,
                                        meterType = meterType,
                                        summary = PurchaseSummaryItem(
                                            title = "${selectedDisco.shortName} ${meterType.replaceFirstChar { it.uppercase() }}",
                                            recipient = verifiedCustomer?.customerName?.let { "$meterNumber ($it)" } ?: meterNumber,
                                            providerName = selectedDisco.fullName,
                                            sellingPriceKobo = powerKobo,
                                            feeKobo = feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo
                                        )
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("electricity_continue_button")
                        ) {
                            Text("Review & Pay", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ServiceType.CABLE_TV -> {
                    val cablePackages = catalog.filter {
                        it.resolvedServiceType() == ServiceType.CABLE_TV &&
                            it.providerCode.equals(selectedCable.clubKonnectCode, ignoreCase = true)
                    }
                    ServiceFormCard(title = "Cable TV Subscriptions") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NigerianProviders.cableProviders.forEach { cable ->
                                FilterChip(
                                    selected = selectedCable.clubKonnectCode == cable.clubKonnectCode,
                                    onClick = {
                                        selectedCable = cable
                                        selectedCablePkg = null
                                        onClearVerification()
                                    },
                                    label = { Text(cable.name) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = smartCardNo,
                            onValueChange = { smartCardNo = it.filter { c -> c.isDigit() } },
                            label = { Text("SmartCard / IUC Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = {
                                if (smartCardNo.length < 8) {
                                    validationError = "Enter a valid SmartCard / IUC number."
                                } else {
                                    validationError = null
                                    onVerifySmartCard(selectedCable.clubKonnectCode, smartCardNo)
                                }
                            },
                            enabled = !isSubmitting,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                                .testTag("verify_smartcard_button")
                        ) {
                            Icon(Icons.Filled.Verified, contentDescription = "Verify SmartCard", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Verify ${selectedCable.name} Account")
                        }
                        if (verifiedCustomer?.verified == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            VerifiedCustomerBox(verifiedCustomer)
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text("Select Bouquet", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        if (cablePackages.isEmpty()) {
                            Text(
                                text = "No active ${selectedCable.name} packages configured yet. Admin can seed or sync bouquets in Admin Pricing.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                cablePackages.forEach { pkg ->
                                    CatalogPlanCard(
                                        title = pkg.productName,
                                        subtitle = pkg.validity ?: "Monthly subscription",
                                        priceKobo = pkg.sellingPriceKobo + pkg.feeKobo,
                                        selected = selectedCablePkg?.id == pkg.id,
                                        onClick = { selectedCablePkg = pkg }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                val pkg = selectedCablePkg
                                if (smartCardNo.length < 8) {
                                    validationError = "Enter a valid SmartCard / IUC number."
                                } else if (pkg == null) {
                                    validationError = "Select a cable bouquet first."
                                } else {
                                    validationError = null
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = ServiceType.CABLE_TV.name,
                                        providerCode = selectedCable.clubKonnectCode,
                                        productCode = pkg.productCode,
                                        recipient = smartCardNo,
                                        amountKobo = pkg.sellingPriceKobo + pkg.feeKobo,
                                        summary = PurchaseSummaryItem(
                                            title = pkg.productName,
                                            recipient = verifiedCustomer?.customerName?.let { "$smartCardNo ($it)" } ?: smartCardNo,
                                            providerName = selectedCable.name,
                                            sellingPriceKobo = pkg.sellingPriceKobo,
                                            feeKobo = pkg.feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo
                                        )
                                    )
                                }
                            },
                            enabled = selectedCablePkg != null,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("cable_continue_button")
                        ) {
                            Text("Review & Pay", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ServiceType.RECHARGE_CARD -> {
                    val unitKobo = rechargeDenomNaira * 100L
                    val epinItem = catalog.firstOrNull {
                        it.resolvedServiceType() == ServiceType.RECHARGE_CARD &&
                            it.providerCode == selectedNetwork.clubKonnectCode &&
                            it.productCode == rechargeDenomNaira.toString()
                    }
                    val unitSelling = epinItem?.sellingPriceKobo ?: unitKobo
                    val totalKobo = unitSelling * rechargeQty

                    ServiceFormCard(title = "Recharge Card Printing (e-PIN)") {
                        Text("Select Network", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NigerianProviders.telecomNetworks.forEach { net ->
                                FilterChip(
                                    selected = selectedNetwork.clubKonnectCode == net.clubKonnectCode,
                                    onClick = { selectedNetwork = net },
                                    label = { Text(net.shortCode) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Denomination", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NigerianProviders.rechargeCardDenominationsNaira.forEach { denom ->
                                FilterChip(
                                    selected = rechargeDenomNaira == denom,
                                    onClick = { rechargeDenomNaira = denom },
                                    label = { Text("₦$denom") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Quantity: $rechargeQty PINs", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 5, 10, 20).forEach { q ->
                                FilterChip(
                                    selected = rechargeQty == q,
                                    onClick = { rechargeQty = q },
                                    label = { Text("$q") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        PricePreviewBar("Total for $rechargeQty x ₦$rechargeDenomNaira PINs", totalKobo)
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                pendingDraft = PendingPurchaseDraft(
                                    serviceType = ServiceType.RECHARGE_CARD.name,
                                    providerCode = selectedNetwork.clubKonnectCode,
                                    productCode = rechargeDenomNaira.toString(),
                                    recipient = profile.displayTitle(),
                                    amountKobo = totalKobo,
                                    quantity = rechargeQty,
                                    summary = PurchaseSummaryItem(
                                        title = "${selectedNetwork.shortCode} ₦$rechargeDenomNaira e-PIN (x$rechargeQty)",
                                        recipient = profile.displayTitle(),
                                        providerName = selectedNetwork.name,
                                        sellingPriceKobo = totalKobo,
                                        feeKobo = 0L,
                                        walletBalanceKobo = profile.walletBalanceKobo
                                    )
                                )
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("epin_continue_button")
                        ) {
                            Text("Generate & Purchase PINs", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ServiceType.WAEC_PIN, ServiceType.JAMB_PIN -> {
                    val examCatalog = catalog.firstOrNull { it.resolvedServiceType() == selectedExamType }
                    val unitPrice = examCatalog?.sellingPriceKobo ?: if (selectedExamType == ServiceType.WAEC_PIN) 370000L else 750000L
                    val totalKobo = unitPrice * examQuantity

                    ServiceFormCard(title = "Education Services (WAEC & JAMB)") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            FilterChip(
                                selected = selectedExamType == ServiceType.WAEC_PIN,
                                onClick = { selectedExamType = ServiceType.WAEC_PIN },
                                label = { Text("WAEC Result PIN") }
                            )
                            FilterChip(
                                selected = selectedExamType == ServiceType.JAMB_PIN,
                                onClick = { selectedExamType = ServiceType.JAMB_PIN },
                                label = { Text("JAMB e-PIN") }
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        if (selectedExamType == ServiceType.JAMB_PIN) {
                            OutlinedTextField(
                                value = jambProfileId,
                                onValueChange = { jambProfileId = it },
                                label = { Text("Candidate JAMB Profile ID") },
                                singleLine = true,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                        }
                        OutlinedTextField(
                            value = phoneInput,
                            onValueChange = { phoneInput = it },
                            label = { Text("Delivery Phone Number") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(1, 2, 5).forEach { q ->
                                FilterChip(
                                    selected = examQuantity == q,
                                    onClick = { examQuantity = q },
                                    label = { Text("$q PIN${if (q > 1) "s" else ""}") }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        PricePreviewBar("Total (${examCatalog?.productName ?: selectedExamType.label})", totalKobo)
                        Spacer(modifier = Modifier.height(14.dp))
                        Button(
                            onClick = {
                                validationError = null
                                if (selectedExamType == ServiceType.JAMB_PIN && jambProfileId.trim().length < 6) {
                                    validationError = "Enter a valid JAMB Profile ID."
                                } else if (!CurrencyFormatter.isValidNigerianPhone(phoneInput)) {
                                    validationError = "Enter a valid Nigerian phone number."
                                } else {
                                    val rec = if (selectedExamType == ServiceType.JAMB_PIN) jambProfileId.trim()
                                    else CurrencyFormatter.toLocalElevenDigitPhone(phoneInput)
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = selectedExamType.name,
                                        providerCode = if (selectedExamType == ServiceType.WAEC_PIN) "waec" else "jamb",
                                        productCode = examCatalog?.productCode ?: if (selectedExamType == ServiceType.WAEC_PIN) "waecdirect" else "utme-mock",
                                        recipient = rec,
                                        amountKobo = totalKobo,
                                        quantity = examQuantity,
                                        summary = PurchaseSummaryItem(
                                            title = "${examCatalog?.productName ?: selectedExamType.label} (x$examQuantity)",
                                            recipient = rec,
                                            providerName = if (selectedExamType == ServiceType.WAEC_PIN) "WAEC" else "JAMB",
                                            sellingPriceKobo = totalKobo,
                                            feeKobo = 0L,
                                            walletBalanceKobo = profile.walletBalanceKobo
                                        )
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("education_continue_button")
                        ) {
                            Text("Review & Pay", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    // 4-Digit Transaction PIN Confirmation Dialog
    val draft = pendingDraft
    if (draft != null) {
        TransactionPinConfirmationDialog(
            summary = draft.summary,
            isSubmitting = isSubmitting,
            onDismiss = { pendingDraft = null },
            onConfirmPin = { enteredPin ->
                onAuthorizePurchase(
                    draft.serviceType,
                    draft.providerCode,
                    draft.productCode,
                    draft.recipient,
                    draft.amountKobo,
                    draft.meterType,
                    draft.quantity,
                    enteredPin,
                    draft.idempotencyKey
                ) {
                    pendingDraft = null
                }
            }
        )
    }
}

@Composable
private fun ServiceFormCard(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(14.dp))
            content()
        }
    }
}

@Composable
private fun PricePreviewBar(label: String, amountKobo: Long) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = CurrencyFormatter.formatKobo(amountKobo),
                style = MaterialTheme.typography.titleMedium.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun CatalogPlanCard(
    title: String,
    subtitle: String,
    priceKobo: Long,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
        border = BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(
                text = CurrencyFormatter.formatKobo(priceKobo),
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = JetBrainsMono),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun VerifiedCustomerBox(verified: CustomerVerificationResponse) {
    Surface(
        color = StatusSuccessBg,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, StatusSuccess.copy(alpha = 0.35f)),
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
                    text = verified.customerName ?: "Verified Customer",
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
