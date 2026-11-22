package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.outlined.Storefront
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.core.CurrencyFormatter
import com.example.data.model.NigerianProviders
import com.example.data.model.PlatformConfig
import com.example.data.model.ServiceCatalogItem
import com.example.data.model.ServiceType
import com.example.data.model.UserProfile
import com.example.data.remote.CustomerVerificationResponse
import com.example.data.remote.LiveCatalogPlanDto
import com.example.data.remote.LiveStorefrontCatalogResponse
import com.example.ui.components.EmptyStateCard
import com.example.ui.components.InlineStatusBanner
import com.example.ui.components.PurchaseSummaryItem
import com.example.ui.components.TransactionPinConfirmationDialog
import com.example.viewmodel.UiState

private fun ServiceCatalogItem.toLivePlanDto(): LiveCatalogPlanDto = LiveCatalogPlanDto(
    id = id.ifBlank { "${serviceType}_${providerCode}_${productCode}" },
    serviceType = serviceType,
    providerCode = providerCode,
    providerName = providerName,
    productCode = productCode,
    productName = productName,
    validity = validity.orEmpty(),
    dataVolume = dataVolume.orEmpty(),
    providerCostKobo = providerCostKobo,
    sellingPriceKobo = sellingPriceKobo,
    feeKobo = feeKobo,
    discountPercent = discountPercent,
    commissionPercent = commissionPercent,
    available = enabled
)

@Composable
fun UtilityServicesScreen(
    initialService: ServiceType,
    profile: UserProfile,
    platformConfig: PlatformConfig,
    catalog: List<ServiceCatalogItem>,
    liveCatalogState: UiState<LiveStorefrontCatalogResponse>,
    verifiedCustomer: CustomerVerificationResponse?,
    isSubmitting: Boolean,
    onRefreshLiveCatalog: () -> Unit,
    onClearVerification: () -> Unit,
    onVerifyMeter: (electricCompany: String, meterNo: String, meterType: String) -> Unit,
    onVerifySmartCard: (cableTv: String, smartCardNo: String) -> Unit,
    onVerifyBetting: (bettingCompany: String, customerId: String) -> Unit,
    onVerifySmile: (mobileNumber: String) -> Unit,
    onAuthorizePurchase: (
        serviceType: String,
        providerCode: String,
        productCode: String,
        productTitle: String,
        recipient: String,
        amountKobo: Long,
        meterType: String?,
        quantity: Int,
        pin: String,
        idempotencyKey: String,
        onFinished: () -> Unit
    ) -> Unit
) {
    val allServiceTabs = listOf(
        Triple(ServiceType.AIRTIME, "Airtime", Icons.Filled.PhoneAndroid),
        Triple(ServiceType.DATA, "Data Plans", Icons.Filled.Wifi),
        Triple(ServiceType.RECHARGE_CARD, "Recharge PINs", Icons.Filled.ConfirmationNumber),
        Triple(ServiceType.DATA_CARD, "Data Cards", Icons.Filled.CreditCard),
        Triple(ServiceType.ELECTRICITY, "Electricity", Icons.Filled.Bolt),
        Triple(ServiceType.CABLE_TV, "Cable TV", Icons.Filled.Tv),
        Triple(ServiceType.WAEC_PIN, "WAEC / JAMB", Icons.Filled.School),
        Triple(ServiceType.SMILE, "Broadband", Icons.Filled.Router),
        Triple(ServiceType.BETTING, "Betting", Icons.Filled.SportsSoccer)
    )

    val liveCatalog = (liveCatalogState as? UiState.Success)?.data
    val isLoadingLive = liveCatalogState is UiState.Loading
    val isDirectApiMode = liveCatalog?.directApiMode == true
    val directApiBalanceNaira = liveCatalog?.directApiBalanceNaira

    val serviceTabs = remember(platformConfig.enabledServices, isDirectApiMode) {
        if (isDirectApiMode) {
            allServiceTabs
        } else {
            allServiceTabs.filter { (type, _, _) ->
                when (type) {
                    ServiceType.WAEC_PIN -> platformConfig.isEducationEnabled()
                    ServiceType.SMILE -> platformConfig.isBroadbandEnabled()
                    else -> platformConfig.isServiceEnabled(type)
                }
            }
        }
    }

    var selectedTab by remember(initialService) {
        mutableStateOf(
            when (initialService) {
                ServiceType.JAMB_PIN -> ServiceType.WAEC_PIN
                ServiceType.SPECTRANET -> ServiceType.SMILE
                else -> initialService
            }
        )
    }

    LaunchedEffect(serviceTabs) {
        if (serviceTabs.isNotEmpty() && serviceTabs.none { it.first == selectedTab }) {
            selectedTab = serviceTabs.first().first
        }
    }

    var pendingDraft by remember { mutableStateOf<PendingPurchaseDraft?>(null) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Shared form states
    var selectedNetwork by remember { mutableStateOf(NigerianProviders.telecomNetworks.first()) }
    var phoneInput by remember { mutableStateOf(CurrencyFormatter.toLocalElevenDigitPhone(profile.phoneNumber)) }
    var amountNairaInput by remember { mutableStateOf("500") }
    var dataSearchQuery by remember { mutableStateOf("") }
    var selectedDataCategory by remember { mutableStateOf("All") }
    var selectedDataPlan by remember { mutableStateOf<LiveCatalogPlanDto?>(null) }

    var selectedDiscoCode by remember { mutableStateOf(NigerianProviders.electricityDiscos.first().clubKonnectCode) }
    var meterType by remember { mutableStateOf("prepaid") }
    var meterNumber by remember { mutableStateOf("") }
    var powerAmountNaira by remember { mutableStateOf("5000") }

    var selectedCableCode by remember { mutableStateOf(NigerianProviders.cableProviders.first().clubKonnectCode) }
    var cableSearchQuery by remember { mutableStateOf("") }
    var smartCardNo by remember { mutableStateOf("") }
    var selectedCablePkg by remember { mutableStateOf<LiveCatalogPlanDto?>(null) }

    val waecEnabled = isDirectApiMode || platformConfig.isServiceEnabled(ServiceType.WAEC_PIN)
    val jambEnabled = isDirectApiMode || platformConfig.isServiceEnabled(ServiceType.JAMB_PIN)
    var selectedExamType by remember(waecEnabled, jambEnabled) {
        mutableStateOf(if (waecEnabled) ServiceType.WAEC_PIN else ServiceType.JAMB_PIN)
    }
    var selectedExamPlan by remember { mutableStateOf<LiveCatalogPlanDto?>(null) }
    var jambProfileId by remember { mutableStateOf("") }
    var examQuantity by remember { mutableIntStateOf(1) }

    LaunchedEffect(selectedTab) {
        validationError = null
        onClearVerification()
    }

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
                    text = "Fast digital payments, instant delivery & printable e-PIN receipts",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            LiveCatalogStatusBanner(
                liveCatalogState = liveCatalogState,
                onRefresh = onRefreshLiveCatalog
            )
        }

        // Category Selector Strip
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                serviceTabs.forEach { (type, label, icon) ->
                    FilterChip(
                        selected = selectedTab == type,
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
            if (serviceTabs.isEmpty()) {
                EmptyStateCard(
                    icon = Icons.Outlined.Storefront,
                    title = "Services Temporarily Unavailable",
                    subtitle = "All utility services are currently paused by the administrator. Please check back shortly."
                )
            } else when (selectedTab) {
                ServiceType.AIRTIME -> {
                    val liveAirtime = liveCatalog?.airtime?.firstOrNull { it.providerCode == selectedNetwork.clubKonnectCode }
                    val fallbackAirtime = catalog.firstOrNull {
                        it.resolvedServiceType() == ServiceType.AIRTIME && it.providerCode == selectedNetwork.clubKonnectCode
                    }
                    val discountPct = liveAirtime?.discountPercent ?: fallbackAirtime?.discountPercent ?: 0.0
                    val feeKobo = liveAirtime?.feeKobo ?: fallbackAirtime?.feeKobo ?: 0L
                    val faceKobo = CurrencyFormatter.nairaToKobo(amountNairaInput) ?: 0L
                    val sellingKobo = Math.round(faceKobo * (1.0 - discountPct / 100.0))

                    ServiceFormCard(title = "Airtime Top-Up") {
                        Text("Select Mobile Network", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NigerianProviders.telecomNetworks.forEach { net ->
                                FilterChip(
                                    selected = selectedNetwork.clubKonnectCode == net.clubKonnectCode,
                                    onClick = { selectedNetwork = net },
                                    label = { Text(net.shortCode, maxLines = 1, softWrap = false) }
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
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
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
                            label = if (discountPct > 0) "Payable (${String.format("%.1f", discountPct)}% discount)" else "Payable Total",
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
                                    val title = "${selectedNetwork.shortCode} Airtime (${CurrencyFormatter.formatKobo(faceKobo)})"
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = ServiceType.AIRTIME.name,
                                        providerCode = selectedNetwork.clubKonnectCode,
                                        productCode = "airtime",
                                        productTitle = title,
                                        recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                        amountKobo = faceKobo,
                                        summary = PurchaseSummaryItem(
                                            title = title,
                                            recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                            providerName = selectedNetwork.name,
                                            sellingPriceKobo = sellingKobo,
                                            feeKobo = feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo,
                                            isDirectApiMode = isDirectApiMode,
                                            directApiBalanceNaira = directApiBalanceNaira
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
                    val liveDataPlans = liveCatalog?.data.orEmpty()
                        .filter { it.providerCode == selectedNetwork.clubKonnectCode }
                    val fallbackDataPlans = catalog.filter {
                        it.resolvedServiceType() == ServiceType.DATA && it.providerCode == selectedNetwork.clubKonnectCode
                    }.map { it.toLivePlanDto() }
                    val allNetworkPlans = liveDataPlans.ifEmpty { fallbackDataPlans }

                    val groupedAllPlans = remember(allNetworkPlans) {
                        CatalogCategoryClassifier.groupAndSort(
                            plans = allNetworkPlans,
                            order = CatalogCategoryClassifier.dataCategoryOrder,
                            classifier = CatalogCategoryClassifier::resolveDataCategory
                        )
                    }
                    val categoryCounts = remember(groupedAllPlans) {
                        groupedAllPlans.map { (cat, list) -> cat to list.size }
                    }
                    val effectiveCategory = remember(selectedDataCategory, categoryCounts) {
                        if (selectedDataCategory == "All" || categoryCounts.any { it.first == selectedDataCategory }) {
                            selectedDataCategory
                        } else {
                            "All"
                        }
                    }

                    val filteredGroupedPlans = remember(groupedAllPlans, effectiveCategory, dataSearchQuery) {
                        val q = dataSearchQuery.trim()
                        groupedAllPlans
                            .filter { (cat, _) -> effectiveCategory == "All" || cat == effectiveCategory }
                            .mapNotNull { (cat, items) ->
                                val matching = if (q.isEmpty()) items else items.filter {
                                    it.productName.contains(q, ignoreCase = true) ||
                                        it.dataVolume.contains(q, ignoreCase = true) ||
                                        it.validity.contains(q, ignoreCase = true) ||
                                        cat.contains(q, ignoreCase = true)
                                }
                                if (matching.isEmpty()) null else cat to matching
                            }
                    }
                    val visiblePlanCount = filteredGroupedPlans.sumOf { it.second.size }

                    ServiceFormCard(title = "Mobile Data Bundles (${allNetworkPlans.size} Plans)") {
                        Text("Select Mobile Network", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            NigerianProviders.telecomNetworks.forEach { net ->
                                FilterChip(
                                    selected = selectedNetwork.clubKonnectCode == net.clubKonnectCode,
                                    onClick = {
                                        selectedNetwork = net
                                        selectedDataPlan = null
                                        selectedDataCategory = "All"
                                        dataSearchQuery = ""
                                    },
                                    label = { Text(net.shortCode, maxLines = 1, softWrap = false) }
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
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = dataSearchQuery,
                            onValueChange = { dataSearchQuery = it },
                            placeholder = { Text("Search ${selectedNetwork.shortCode} plans (e.g. 1GB, SME, 30 days)") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search plans") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("data_plan_search_input")
                        )

                        if (categoryCounts.size > 1) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text(
                                text = "Plan Category",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            PlanCategoryFilterStrip(
                                categories = categoryCounts,
                                totalCount = allNetworkPlans.size,
                                selectedCategory = effectiveCategory,
                                onSelectCategory = { selectedDataCategory = it },
                                testTagPrefix = "data"
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (effectiveCategory == "All") {
                                "Showing $visiblePlanCount ${selectedNetwork.shortCode} plans grouped by category"
                            } else {
                                "Showing $visiblePlanCount ${selectedNetwork.shortCode} $effectiveCategory plans (lowest to highest)"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (filteredGroupedPlans.isEmpty()) {
                            Text(
                                text = if (isLoadingLive) "Loading ${selectedNetwork.shortCode} data bundles…"
                                else "No matching ${selectedNetwork.shortCode} data plans found. Try another category or clear search.",
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
                                            subtitle = listOf(categoryName, plan.dataVolume, plan.validity)
                                                .filter { it.isNotBlank() }
                                                .distinct()
                                                .joinToString(" • "),
                                            priceKobo = plan.sellingPriceKobo + plan.feeKobo,
                                            selected = selectedDataPlan?.id == plan.id,
                                            onClick = { selectedDataPlan = plan }
                                        )
                                    }
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
                                        productTitle = plan.productName,
                                        recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                        amountKobo = plan.sellingPriceKobo + plan.feeKobo,
                                        summary = PurchaseSummaryItem(
                                            title = plan.productName,
                                            recipient = CurrencyFormatter.toLocalElevenDigitPhone(phoneInput),
                                            providerName = plan.providerName,
                                            sellingPriceKobo = plan.sellingPriceKobo,
                                            feeKobo = plan.feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo,
                                            isDirectApiMode = isDirectApiMode,
                                            directApiBalanceNaira = directApiBalanceNaira
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

                ServiceType.RECHARGE_CARD -> {
                    RechargeCardEpinForm(
                        profile = profile,
                        isLoadingLive = isLoadingLive,
                        group = liveCatalog?.rechargeCards,
                        isDirectApiMode = isDirectApiMode,
                        directApiBalanceNaira = directApiBalanceNaira,
                        onRefreshAvailability = onRefreshLiveCatalog,
                        onCreateDraft = { pendingDraft = it }
                    )
                }

                ServiceType.DATA_CARD -> {
                    DataCardEpinForm(
                        profile = profile,
                        isLoadingLive = isLoadingLive,
                        group = liveCatalog?.dataCards,
                        isDirectApiMode = isDirectApiMode,
                        directApiBalanceNaira = directApiBalanceNaira,
                        onRefreshAvailability = onRefreshLiveCatalog,
                        onCreateDraft = { pendingDraft = it }
                    )
                }

                ServiceType.ELECTRICITY -> {
                    val liveDiscos = liveCatalog?.electricity.orEmpty()
                    val discoList = remember(liveDiscos) {
                        if (liveDiscos.isNotEmpty()) {
                            liveDiscos.map { Triple(it.providerCode, it.providerName, it.feeKobo) }
                        } else {
                            NigerianProviders.electricityDiscos.map { Triple(it.clubKonnectCode, it.shortName, 10000L) }
                        }
                    }
                    val chosenDisco = discoList.firstOrNull { it.first == selectedDiscoCode } ?: discoList.first()
                    val matchedLiveDisco = liveDiscos.firstOrNull { it.providerCode == chosenDisco.first }
                    val feeKobo = if (isDirectApiMode) 0L else chosenDisco.third
                    val powerDiscountPct = if (isDirectApiMode) (matchedLiveDisco?.discountPercent ?: 0.0) else 0.0
                    val powerKobo = CurrencyFormatter.nairaToKobo(powerAmountNaira) ?: 0L
                    val effectivePowerPayableKobo = Math.round(powerKobo * (1.0 - powerDiscountPct / 100.0))

                    ServiceFormCard(title = "Electricity Bill Payment (${discoList.size} DisCos)") {
                        Text("Distribution Company (DisCo)", style = MaterialTheme.typography.labelLarge)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            discoList.forEach { (code, name, _) ->
                                FilterChip(
                                    selected = chosenDisco.first == code,
                                    onClick = {
                                        selectedDiscoCode = code
                                        onClearVerification()
                                    },
                                    label = { Text(name) }
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
                                    onVerifyMeter(chosenDisco.first, meterNumber, meterType)
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
                            Text("Verify Meter with ${chosenDisco.second}")
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
                            label = if (isDirectApiMode) {
                                if (powerDiscountPct > 0) "Wholesale API (${String.format("%.1f", powerDiscountPct)}% off • ₦0 fee)"
                                else "Wholesale API Total (₦0 fee)"
                            } else {
                                "Total (incl. ${CurrencyFormatter.formatKobo(feeKobo)} fee)"
                            },
                            amountKobo = effectivePowerPayableKobo + feeKobo
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
                                    val title = "${chosenDisco.second} ${meterType.replaceFirstChar { it.uppercase() }}"
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = ServiceType.ELECTRICITY.name,
                                        providerCode = chosenDisco.first,
                                        productCode = meterType,
                                        productTitle = title,
                                        recipient = meterNumber,
                                        amountKobo = powerKobo,
                                        meterType = meterType,
                                        summary = PurchaseSummaryItem(
                                            title = title,
                                            recipient = verifiedCustomer?.customerName?.let { "$meterNumber ($it)" } ?: meterNumber,
                                            providerName = chosenDisco.second,
                                            sellingPriceKobo = effectivePowerPayableKobo,
                                            feeKobo = feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo,
                                            isDirectApiMode = isDirectApiMode,
                                            directApiBalanceNaira = directApiBalanceNaira
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
                    val liveCableAll = liveCatalog?.cableTv.orEmpty()
                    val cableProviderList = remember(liveCableAll) {
                        val dynamic = liveCableAll.map { it.providerCode to it.providerName }.distinctBy { it.first.lowercase() }
                        if (dynamic.isNotEmpty()) dynamic
                        else NigerianProviders.cableProviders.map { it.clubKonnectCode to it.name }
                    }
                    val chosenCable = cableProviderList.firstOrNull { it.first.equals(selectedCableCode, ignoreCase = true) }
                        ?: cableProviderList.first()

                    val providerPackages = liveCableAll.filter {
                        it.providerCode.equals(chosenCable.first, ignoreCase = true)
                    }.ifEmpty {
                        catalog.filter {
                            it.resolvedServiceType() == ServiceType.CABLE_TV &&
                                it.providerCode.equals(chosenCable.first, ignoreCase = true)
                        }.map { it.toLivePlanDto() }
                    }

                    val filteredCablePkgs = remember(providerPackages, cableSearchQuery) {
                        val q = cableSearchQuery.trim()
                        if (q.isEmpty()) providerPackages
                        else providerPackages.filter { it.productName.contains(q, ignoreCase = true) }
                    }

                    ServiceFormCard(title = "Cable TV Subscriptions (${providerPackages.size} Bouquets)") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            cableProviderList.forEach { (code, name) ->
                                FilterChip(
                                    selected = chosenCable.first.equals(code, ignoreCase = true),
                                    onClick = {
                                        selectedCableCode = code
                                        selectedCablePkg = null
                                        cableSearchQuery = ""
                                        onClearVerification()
                                    },
                                    label = { Text(name) }
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
                                    onVerifySmartCard(chosenCable.first, smartCardNo)
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
                            Text("Verify ${chosenCable.second} Account")
                        }
                        if (verifiedCustomer?.verified == true) {
                            Spacer(modifier = Modifier.height(8.dp))
                            VerifiedCustomerBox(verifiedCustomer)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        OutlinedTextField(
                            value = cableSearchQuery,
                            onValueChange = { cableSearchQuery = it },
                            placeholder = { Text("Search ${chosenCable.second} bouquets…") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search bouquets") },
                            singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        if (filteredCablePkgs.isEmpty()) {
                            Text(
                                text = if (isLoadingLive) "Loading ${chosenCable.second} bouquets…"
                                else "No matching ${chosenCable.second} bouquets found.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                filteredCablePkgs.forEach { pkg ->
                                    CatalogPlanCard(
                                        title = pkg.productName,
                                        subtitle = pkg.validity.ifBlank { "Monthly subscription" },
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
                                        providerCode = chosenCable.first,
                                        productCode = pkg.productCode,
                                        productTitle = pkg.productName,
                                        recipient = smartCardNo,
                                        amountKobo = pkg.sellingPriceKobo + pkg.feeKobo,
                                        summary = PurchaseSummaryItem(
                                            title = pkg.productName,
                                            recipient = verifiedCustomer?.customerName?.let { "$smartCardNo ($it)" } ?: smartCardNo,
                                            providerName = chosenCable.second,
                                            sellingPriceKobo = pkg.sellingPriceKobo,
                                            feeKobo = pkg.feeKobo,
                                            walletBalanceKobo = profile.walletBalanceKobo,
                                            isDirectApiMode = isDirectApiMode,
                                            directApiBalanceNaira = directApiBalanceNaira
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

                ServiceType.WAEC_PIN, ServiceType.JAMB_PIN -> {
                    val liveExamList = if (selectedExamType == ServiceType.WAEC_PIN) {
                        liveCatalog?.waec.orEmpty()
                    } else {
                        liveCatalog?.jamb.orEmpty()
                    }
                    val fallbackExamList = catalog.filter { it.resolvedServiceType() == selectedExamType }.map { it.toLivePlanDto() }
                    val examPlans = liveExamList.ifEmpty { fallbackExamList }
                    val activeExamPlan = selectedExamPlan?.takeIf { p -> examPlans.any { it.id == p.id } } ?: examPlans.firstOrNull()
                    val unitSelling = activeExamPlan?.sellingPriceKobo
                        ?: if (selectedExamType == ServiceType.WAEC_PIN) 370000L else 750000L
                    val unitFee = activeExamPlan?.feeKobo ?: 0L
                    val totalKobo = (unitSelling + unitFee) * examQuantity

                    ServiceFormCard(title = "Education Services (WAEC & JAMB)") {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (waecEnabled) {
                                FilterChip(
                                    selected = selectedExamType == ServiceType.WAEC_PIN,
                                    onClick = {
                                        selectedExamType = ServiceType.WAEC_PIN
                                        selectedExamPlan = null
                                    },
                                    label = { Text("WAEC Result PIN") }
                                )
                            }
                            if (jambEnabled) {
                                FilterChip(
                                    selected = selectedExamType == ServiceType.JAMB_PIN,
                                    onClick = {
                                        selectedExamType = ServiceType.JAMB_PIN
                                        selectedExamPlan = null
                                    },
                                    label = { Text("JAMB e-PIN") }
                                )
                            }
                        }
                        if (examPlans.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Select Exam Product", style = MaterialTheme.typography.labelLarge)
                            Spacer(modifier = Modifier.height(6.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                examPlans.forEach { plan ->
                                    CatalogPlanCard(
                                        title = plan.productName,
                                        subtitle = "Printable Exam e-PIN",
                                        priceKobo = plan.sellingPriceKobo + plan.feeKobo,
                                        selected = activeExamPlan?.id == plan.id,
                                        onClick = { selectedExamPlan = plan }
                                    )
                                }
                            }
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
                        PricePreviewBar("Total (${activeExamPlan?.productName ?: selectedExamType.label})", totalKobo)
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
                                    val title = "${activeExamPlan?.productName ?: selectedExamType.label} (x$examQuantity)"
                                    pendingDraft = PendingPurchaseDraft(
                                        serviceType = selectedExamType.name,
                                        providerCode = if (selectedExamType == ServiceType.WAEC_PIN) "waec" else "jamb",
                                        productCode = activeExamPlan?.productCode
                                            ?: if (selectedExamType == ServiceType.WAEC_PIN) "waecdirect" else "utme-mock",
                                        productTitle = title,
                                        recipient = rec,
                                        amountKobo = totalKobo,
                                        quantity = examQuantity,
                                        summary = PurchaseSummaryItem(
                                            title = title,
                                            recipient = rec,
                                            providerName = if (selectedExamType == ServiceType.WAEC_PIN) "WAEC" else "JAMB",
                                            sellingPriceKobo = unitSelling * examQuantity,
                                            feeKobo = unitFee * examQuantity,
                                            walletBalanceKobo = profile.walletBalanceKobo,
                                            isDirectApiMode = isDirectApiMode,
                                            directApiBalanceNaira = directApiBalanceNaira
                                        )
                                    )
                                }
                            },
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                                .testTag("exam_continue_button")
                        ) {
                            Text("Review & Pay", fontWeight = FontWeight.Bold)
                        }
                    }
                }

                ServiceType.SMILE, ServiceType.SPECTRANET -> {
                    BroadbandServiceForm(
                        profile = profile,
                        broadbandPlans = liveCatalog?.broadband.orEmpty(),
                        smileEnabled = isDirectApiMode || platformConfig.isServiceEnabled(ServiceType.SMILE),
                        spectranetEnabled = isDirectApiMode || platformConfig.isServiceEnabled(ServiceType.SPECTRANET),
                        isDirectApiMode = isDirectApiMode,
                        directApiBalanceNaira = directApiBalanceNaira,
                        verifiedCustomer = verifiedCustomer,
                        isSubmitting = isSubmitting,
                        onClearVerification = onClearVerification,
                        onVerifySmile = onVerifySmile,
                        onValidationError = { validationError = it },
                        onCreateDraft = { pendingDraft = it }
                    )
                }

                ServiceType.BETTING -> {
                    BettingServiceForm(
                        profile = profile,
                        bettingProviders = liveCatalog?.betting.orEmpty(),
                        isDirectApiMode = isDirectApiMode,
                        directApiBalanceNaira = directApiBalanceNaira,
                        verifiedCustomer = verifiedCustomer,
                        isSubmitting = isSubmitting,
                        onClearVerification = onClearVerification,
                        onVerifyBetting = onVerifyBetting,
                        onValidationError = { validationError = it },
                        onCreateDraft = { pendingDraft = it }
                    )
                }
            }
        }

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    pendingDraft?.let { draft ->
        TransactionPinConfirmationDialog(
            summary = draft.summary,
            isSubmitting = isSubmitting,
            onDismiss = { if (!isSubmitting) pendingDraft = null },
            onConfirmPin = { enteredPin ->
                onAuthorizePurchase(
                    draft.serviceType,
                    draft.providerCode,
                    draft.productCode,
                    draft.productTitle,
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
