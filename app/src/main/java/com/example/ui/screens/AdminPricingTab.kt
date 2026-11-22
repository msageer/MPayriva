package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CompareArrows
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.example.data.model.PlatformConfig
import com.example.data.model.ServiceType
import com.example.data.remote.AdminPricingMatrixResponse
import com.example.data.remote.LiveCatalogPlanDto
import com.example.data.remote.ProviderComparisonRowDto
import com.example.data.remote.ProviderControlsResponse
import com.example.data.remote.SavePlanOverrideRequest
import com.example.data.remote.ServicePricingRuleDto
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusSuccess
import kotlin.math.ceil
import kotlin.math.roundToLong

private val SERVICE_DISPLAY_NAMES = mapOf(
    "AIRTIME" to "Airtime Top-Up",
    "DATA" to "Mobile Data Bundles",
    "ELECTRICITY" to "Electricity Bills",
    "CABLE_TV" to "Cable TV Subscriptions",
    "RECHARGE_CARD" to "Recharge Card e-PINs",
    "DATA_CARD" to "Data Card e-PINs",
    "WAEC_PIN" to "WAEC Result e-PIN",
    "JAMB_PIN" to "JAMB e-PIN",
    "SMILE" to "Smile 4G Broadband",
    "SPECTRANET" to "Spectranet Broadband",
    "BETTING" to "Betting Wallet Funding"
)

@Composable
fun AdminPricingTab(
    pricingMatrix: AdminPricingMatrixResponse?,
    isLoadingPricing: Boolean,
    platformConfig: PlatformConfig,
    providerControls: ProviderControlsResponse? = null,
    isBusy: Boolean,
    onLoadPricingMatrix: (refresh: Boolean) -> Unit,
    onSavePricingRules: (List<ServicePricingRuleDto>) -> Unit,
    onSavePlanOverride: (SavePlanOverrideRequest) -> Unit,
    onToggleUtilityService: (keys: List<String>, enabled: Boolean, label: String) -> Unit,
    onRefreshProviderControls: () -> Unit = {},
    onToggleApiProvider: (providerId: String, enabled: Boolean) -> Unit = { _, _ -> },
    onUpdateServiceRouting: (serviceType: String, routingMode: String) -> Unit = { _, _ -> }
) {
    LaunchedEffect(Unit) {
        if (pricingMatrix == null) {
            onLoadPricingMatrix(false)
        }
        if (providerControls == null) {
            onRefreshProviderControls()
        }
    }

    // 0 = Margin Rules, 1 = All Live Plans, 2 = Compare Providers, 3 = Providers ON/OFF, 4 = Services ON/OFF
    var subMode by remember { mutableIntStateOf(0) }
    var selectedServiceFilter by remember { mutableStateOf("ALL") }
    var selectedProviderFilter by remember { mutableStateOf("ALL") }
    var searchQuery by remember { mutableStateOf("") }

    var editingRule by remember { mutableStateOf<ServicePricingRuleDto?>(null) }
    var editingPlan by remember { mutableStateOf<LiveCatalogPlanDto?>(null) }

    val allLivePlans = remember(pricingMatrix) {
        val cat = pricingMatrix?.catalog ?: return@remember emptyList()
        buildList {
            addAll(cat.airtime)
            addAll(cat.data)
            addAll(cat.electricity)
            addAll(cat.cableTv)
            addAll(cat.rechargeCards.items)
            addAll(cat.dataCards.items)
            addAll(cat.waec)
            addAll(cat.jamb)
            addAll(cat.broadband)
            addAll(cat.betting)
        }
    }

    val comparisonRows = remember(pricingMatrix) {
        pricingMatrix?.providerComparison.orEmpty()
    }

    val filteredPlans = remember(allLivePlans, selectedServiceFilter, selectedProviderFilter, searchQuery) {
        allLivePlans.filter { plan ->
            val matchesService = when (selectedServiceFilter) {
                "ALL" -> true
                "BROADBAND" -> plan.serviceType == "SMILE" || plan.serviceType == "SPECTRANET"
                else -> plan.serviceType.equals(selectedServiceFilter, ignoreCase = true)
            }
            val matchesProvider = when (selectedProviderFilter) {
                "ALL" -> true
                else -> plan.apiProvider.equals(selectedProviderFilter, ignoreCase = true)
            }
            val matchesQuery = searchQuery.isBlank() ||
                plan.productName.contains(searchQuery, ignoreCase = true) ||
                plan.providerName.contains(searchQuery, ignoreCase = true) ||
                plan.apiProviderName.contains(searchQuery, ignoreCase = true) ||
                plan.categoryGroup.contains(searchQuery, ignoreCase = true) ||
                plan.productCode.contains(searchQuery, ignoreCase = true)
            matchesService && matchesProvider && matchesQuery
        }
    }

    val filteredComparisonRows = remember(comparisonRows, selectedServiceFilter, selectedProviderFilter, searchQuery) {
        comparisonRows.filter { row ->
            val matchesService = when (selectedServiceFilter) {
                "ALL" -> true
                "BROADBAND" -> row.serviceType == "SMILE" || row.serviceType == "SPECTRANET"
                else -> row.serviceType.equals(selectedServiceFilter, ignoreCase = true)
            }
            val matchesProvider = when (selectedProviderFilter) {
                "ALL" -> true
                else -> row.apiProvider.equals(selectedProviderFilter, ignoreCase = true)
            }
            val matchesQuery = searchQuery.isBlank() ||
                row.productName.contains(searchQuery, ignoreCase = true) ||
                row.providerName.contains(searchQuery, ignoreCase = true) ||
                row.apiProviderName.contains(searchQuery, ignoreCase = true) ||
                row.categoryGroup.contains(searchQuery, ignoreCase = true) ||
                row.productCode.contains(searchQuery, ignoreCase = true)
            matchesService && matchesProvider && matchesQuery
        }
    }

    val toggleableServices = remember(platformConfig) {
        listOf(
            Triple("Airtime Top-Up", listOf(ServiceType.AIRTIME.name), platformConfig.isServiceEnabled(ServiceType.AIRTIME)),
            Triple("Mobile Data Bundles", listOf(ServiceType.DATA.name), platformConfig.isServiceEnabled(ServiceType.DATA)),
            Triple("Recharge Card Printing (e-PIN)", listOf(ServiceType.RECHARGE_CARD.name), platformConfig.isServiceEnabled(ServiceType.RECHARGE_CARD)),
            Triple("Data Card Printing (e-PIN)", listOf(ServiceType.DATA_CARD.name), platformConfig.isServiceEnabled(ServiceType.DATA_CARD)),
            Triple("Electricity Bills", listOf(ServiceType.ELECTRICITY.name), platformConfig.isServiceEnabled(ServiceType.ELECTRICITY)),
            Triple("Cable TV Subscriptions", listOf(ServiceType.CABLE_TV.name), platformConfig.isServiceEnabled(ServiceType.CABLE_TV)),
            Triple("WAEC Result PIN", listOf(ServiceType.WAEC_PIN.name), platformConfig.isServiceEnabled(ServiceType.WAEC_PIN)),
            Triple("JAMB e-PIN", listOf(ServiceType.JAMB_PIN.name), platformConfig.isServiceEnabled(ServiceType.JAMB_PIN)),
            Triple("Broadband (Smile & Spectranet)", listOf(ServiceType.SMILE.name, ServiceType.SPECTRANET.name), platformConfig.isBroadbandEnabled()),
            Triple("Betting Wallet Funding", listOf(ServiceType.BETTING.name), platformConfig.isServiceEnabled(ServiceType.BETTING))
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Bar + Sub-mode Switcher
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Pricing, Providers & Profit Engine",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = pricingMatrix?.let {
                                    val ckTag = if (it.clubKonnectConnected) "ClubKonnect Live" else "ClubKonnect Offline"
                                    val alrTag = if (it.alrahuzDataConnected) "AlrahuzData Live" else "AlrahuzData Idle"
                                    "${it.totalPlans} routed plans • $ckTag • $alrTag"
                                } ?: "Compare Provider Cost vs Selling Price vs Profit across ClubKonnect & AlrahuzData.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = {
                                onLoadPricingMatrix(true)
                                onRefreshProviderControls()
                            },
                            enabled = !isLoadingPricing && !isBusy,
                            modifier = Modifier.testTag("admin_pricing_refresh_button")
                        ) {
                            if (isLoadingPricing) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "Refresh live provider prices")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = subMode == 0,
                            onClick = { subMode = 0 },
                            label = { Text("Margin Rules (${pricingMatrix?.rules?.size ?: 11})") },
                            modifier = Modifier.testTag("admin_pricing_mode_rules")
                        )
                        FilterChip(
                            selected = subMode == 1,
                            onClick = { subMode = 1 },
                            label = { Text("All Plans (${allLivePlans.size})") },
                            modifier = Modifier.testTag("admin_pricing_mode_plans")
                        )
                        FilterChip(
                            selected = subMode == 2,
                            onClick = { subMode = 2 },
                            label = { Text("Compare (${comparisonRows.size})") },
                            modifier = Modifier.testTag("admin_pricing_mode_compare")
                        )
                        FilterChip(
                            selected = subMode == 3,
                            onClick = { subMode = 3 },
                            label = { Text("Providers ON/OFF") },
                            modifier = Modifier.testTag("admin_pricing_mode_providers")
                        )
                        FilterChip(
                            selected = subMode == 4,
                            onClick = { subMode = 4 },
                            label = { Text("Services ON/OFF") },
                            modifier = Modifier.testTag("admin_pricing_mode_toggles")
                        )
                    }
                }
            }
        }

        // MODE 0: SERVICE-LEVEL MARKUP / DISCOUNT / FEE RULES
        if (subMode == 0) {
            val rules = pricingMatrix?.rules.orEmpty()
            if (rules.isEmpty() && !isLoadingPricing) {
                item {
                    OutlinedButton(
                        onClick = { onLoadPricingMatrix(true) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Fetch Live ClubKonnect Rates & Rules")
                    }
                }
            } else {
                items(rules, key = { it.serviceType }) { rule ->
                    val servicePlans = remember(allLivePlans, rule.serviceType) {
                        allLivePlans.filter { it.serviceType.equals(rule.serviceType, ignoreCase = true) }
                    }
                    val samplePlan = servicePlans.firstOrNull { it.providerCostKobo > 0L || it.commissionPercent > 0.0 }
                        ?: servicePlans.firstOrNull()
                    val isVariable = rule.serviceType in setOf("AIRTIME", "ELECTRICITY", "BETTING")

                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = SERVICE_DISPLAY_NAMES[rule.serviceType] ?: rule.serviceType,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "${servicePlans.size} live plan(s) • ${
                                            if (isVariable) {
                                                "Cust Disc: ${rule.customerDiscountPercent}% • Fee: ${CurrencyFormatter.formatKobo(rule.feeKobo)}"
                                            } else {
                                                "Markup: ${if (rule.markupPercent >= 0) "+" else ""}${rule.markupPercent}% + ${CurrencyFormatter.formatKobo(rule.markupFlatKobo)} • Fee: ${CurrencyFormatter.formatKobo(rule.feeKobo)}"
                                            }
                                        }",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                OutlinedButton(
                                    onClick = { editingRule = rule },
                                    enabled = !isBusy,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.testTag("edit_rule_${rule.serviceType.lowercase()}")
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit rule", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Edit")
                                }
                            }

                            if (samplePlan != null) {
                                Surface(
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        if (isVariable) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Provider Cost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    "${samplePlan.commissionPercent}% off face",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("My Selling Price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    "${samplePlan.discountPercent}% off + ${CurrencyFormatter.formatKobo(samplePlan.feeKobo)}",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("My Profit / ₦1k", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    text = "${if (samplePlan.netProfitKobo >= 0) "+" else ""}${CurrencyFormatter.formatKobo(samplePlan.netProfitKobo)}",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                                    color = if (samplePlan.netProfitKobo >= 0) StatusSuccess else StatusFailed,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        } else {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("Provider Cost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    CurrencyFormatter.formatKobo(samplePlan.providerCostKobo),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text("My Selling Price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    CurrencyFormatter.formatKobo(samplePlan.sellingPriceKobo + samplePlan.feeKobo),
                                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text("My Profit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Text(
                                                    text = "${if (samplePlan.netProfitKobo >= 0) "+" else ""}${CurrencyFormatter.formatKobo(samplePlan.netProfitKobo)} (${samplePlan.profitMarginPercent}%)",
                                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                                                    color = if (samplePlan.netProfitKobo >= 0) StatusSuccess else StatusFailed,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // MODE 1: ALL LIVE PLANS EXPLORER (SIDE-BY-SIDE COST | SELLING PRICE | PROFIT)
        if (subMode == 1) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search any plan (e.g. MTN 1GB, DStv, EKEDC, WAEC)") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_pricing_plan_search")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ALL" to "All Providers", "CLUBKONNECT" to "ClubKonnect", "ALRAHUZDATA" to "AlrahuzData").forEach { (key, label) ->
                            FilterChip(
                                selected = selectedProviderFilter == key,
                                onClick = { selectedProviderFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val filters = listOf(
                            "ALL" to "All",
                            "AIRTIME" to "Airtime",
                            "DATA" to "Data",
                            "CABLE_TV" to "Cable TV",
                            "ELECTRICITY" to "Electricity",
                            "RECHARGE_CARD" to "Recharge e-PIN",
                            "DATA_CARD" to "Data Card",
                            "WAEC_PIN" to "WAEC",
                            "JAMB_PIN" to "JAMB",
                            "BROADBAND" to "Broadband",
                            "BETTING" to "Betting"
                        )
                        filters.forEach { (key, label) ->
                            FilterChip(
                                selected = selectedServiceFilter == key,
                                onClick = { selectedServiceFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text(
                        text = "Showing ${filteredPlans.size} live plan(s). Side-by-side Provider Cost, Selling Price & Profit — tap Edit to override.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(filteredPlans.take(150), key = { "${it.apiProvider}_${it.serviceType}_${it.providerCode}_${it.productCode}_${it.id}" }) { plan ->
                val isVariable = plan.serviceType in setOf("AIRTIME", "ELECTRICITY", "BETTING")
                val profitColor = when {
                    plan.netProfitKobo > 0L -> StatusSuccess
                    plan.netProfitKobo < 0L -> StatusFailed
                    else -> StatusPending
                }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "${plan.providerName} • ${plan.productName}",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    Surface(
                                        color = if (plan.apiProvider == "ALRAHUZDATA") {
                                            MaterialTheme.colorScheme.tertiaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.secondaryContainer
                                        },
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = plan.apiProviderName,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Surface(
                                        color = if (plan.hasCustomOverride) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                                        shape = RoundedCornerShape(50)
                                    ) {
                                        Text(
                                            text = if (plan.hasCustomOverride) "CUSTOM" else "RULE",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "${plan.serviceType} • ${plan.categoryGroup.ifBlank { plan.productCode }}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { editingPlan = plan }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit plan pricing")
                            }
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Provider Cost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (isVariable) {
                                        "${plan.commissionPercent}% API comm"
                                    } else {
                                        CurrencyFormatter.formatKobo(plan.providerCostKobo)
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text("My Selling Price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (isVariable) {
                                        "${plan.discountPercent}% disc + ${CurrencyFormatter.formatKobo(plan.feeKobo)}"
                                    } else {
                                        CurrencyFormatter.formatKobo(plan.sellingPriceKobo + plan.feeKobo)
                                    },
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(if (isVariable) "My Profit / ₦1k" else "My Profit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = "${if (plan.netProfitKobo >= 0) "+" else ""}${CurrencyFormatter.formatKobo(plan.netProfitKobo)} (${plan.profitMarginPercent}%)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                                    color = profitColor,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // MODE 2: MULTI-PROVIDER COMPARISON (PROVIDER | PRODUCT | AVAILABILITY | COST | SELL | EXPECTED PROFIT)
        if (subMode == 2) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.CompareArrows, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Text(
                                text = "Compare live Provider Cost, Selling Price, Availability, and Expected Profit side by side across ClubKonnect & AlrahuzData.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Compare products (e.g. MTN 1GB, Airtel 2GB, DStv)") },
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("admin_compare_search")
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("ALL" to "All Services", "DATA" to "Data", "AIRTIME" to "Airtime", "CABLE_TV" to "Cable TV", "ELECTRICITY" to "Electricity", "RECHARGE_CARD" to "Recharge e-PIN", "WAEC_PIN" to "WAEC", "JAMB_PIN" to "JAMB").forEach { (key, label) ->
                            FilterChip(
                                selected = selectedServiceFilter == key,
                                onClick = { selectedServiceFilter = key },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text(
                        text = "Showing ${filteredComparisonRows.size} provider offering(s) sorted by product & lowest wholesale cost.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (filteredComparisonRows.isEmpty()) {
                item {
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "No comparison rows match your current filter. Tap Refresh above to fetch live plans from all configured API providers.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            } else {
                items(filteredComparisonRows.take(150), key = { "${it.apiProvider}_${it.id}" }) { row ->
                    ProviderComparisonCard(row = row)
                }
            }
        }

        // MODE 3: API PROVIDERS ON/OFF & ROUTING CONTROLS
        if (subMode == 3) {
            item {
                AdminProvidersControlCard(
                    controls = providerControls,
                    isBusy = isBusy,
                    onRefresh = {
                        onRefreshProviderControls()
                        onLoadPricingMatrix(true)
                    },
                    onToggleProvider = onToggleApiProvider,
                    onUpdateServiceRouting = onUpdateServiceRouting
                )
            }
        }

        // MODE 4: UTILITY SERVICE ON/OFF TOGGLES
        if (subMode == 4) {
            item {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Utility Service Controls (ON / OFF)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        }
                        Text(
                            "Instantly show or hide any utility service on the customer storefront and block disabled purchases server-side.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        toggleableServices.forEach { (label, keys, isOn) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Switch(
                                    checked = isOn,
                                    enabled = !isBusy,
                                    onCheckedChange = { checked ->
                                        onToggleUtilityService(keys, checked, label)
                                    },
                                    modifier = Modifier.testTag("toggle_service_${keys.first().lowercase()}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // DIALOG 1: EDIT GLOBAL SERVICE PRICING RULE WITH LIVE PROFIT PREVIEW
    editingRule?.let { rule ->
        val isVariable = rule.serviceType in setOf("AIRTIME", "ELECTRICITY", "BETTING")
        var markupPctText by remember(rule.serviceType) { mutableStateOf(rule.markupPercent.toString()) }
        var markupFlatNairaText by remember(rule.serviceType) { mutableStateOf((rule.markupFlatKobo / 100.0).toString()) }
        var custDiscText by remember(rule.serviceType) { mutableStateOf(rule.customerDiscountPercent.toString()) }
        var feeNairaText by remember(rule.serviceType) { mutableStateOf((rule.feeKobo / 100.0).toString()) }

        val previewMarkupPct = markupPctText.toDoubleOrNull() ?: 0.0
        val previewMarkupFlatKobo = CurrencyFormatter.nairaToKobo(markupFlatNairaText) ?: 0L
        val previewDisc = custDiscText.toDoubleOrNull() ?: 0.0
        val previewFeeKobo = CurrencyFormatter.nairaToKobo(feeNairaText) ?: 0L

        // Sample preview calculation on ₦1,000 wholesale / face value
        val sampleProfitText = if (isVariable) {
            val sampleCkComm = if (rule.serviceType == "AIRTIME") 3.0 else if (rule.serviceType == "ELECTRICITY") 1.0 else 0.0
            val ckCost = (100000.0 * (1.0 - sampleCkComm / 100.0)).roundToLong()
            val custPays = (100000.0 * (1.0 - previewDisc / 100.0)).roundToLong() + previewFeeKobo
            val gain = custPays - ckCost
            "Example on ₦1,000 face (est. $sampleCkComm% CK comm):\nCustomer pays ${CurrencyFormatter.formatKobo(custPays)} → Est. Gain ${if (gain >= 0) "+" else ""}${CurrencyFormatter.formatKobo(gain)}"
        } else {
            val sampleCost = 100000L // ₦1,000 wholesale
            val rawSell = sampleCost * (1.0 + previewMarkupPct / 100.0) + previewMarkupFlatKobo
            val sellKobo = (ceil(rawSell / 100.0) * 100.0).roundToLong().coerceAtLeast(100L)
            val custPays = sellKobo + previewFeeKobo
            val gain = custPays - sampleCost
            val pct = (gain * 100.0) / sampleCost
            "Example on ₦1,000 ClubKonnect wholesale plan:\nCustomer pays ${CurrencyFormatter.formatKobo(custPays)} → Your Gain ${if (gain >= 0) "+" else ""}${CurrencyFormatter.formatKobo(gain)} (${String.format("%.1f", pct)}%)"
        }

        AlertDialog(
            onDismissRequest = { editingRule = null },
            title = { Text("Pricing Rule: ${SERVICE_DISPLAY_NAMES[rule.serviceType] ?: rule.serviceType}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isVariable) {
                        OutlinedTextField(
                            value = markupPctText,
                            onValueChange = { markupPctText = it },
                            label = { Text("Markup Percentage (% on ClubKonnect Cost)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = markupFlatNairaText,
                            onValueChange = { markupFlatNairaText = it },
                            label = { Text("Flat Markup Amount (₦ added per plan)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    if (isVariable || rule.serviceType == "RECHARGE_CARD") {
                        OutlinedTextField(
                            value = custDiscText,
                            onValueChange = { custDiscText = it },
                            label = { Text("Customer Discount (% off Face Value)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = feeNairaText,
                        onValueChange = { feeNairaText = it },
                        label = { Text("Service / Convenience Fee (₦)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = sampleProfitText,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updatedRule = ServicePricingRuleDto(
                            serviceType = rule.serviceType,
                            markupPercent = previewMarkupPct,
                            markupFlatKobo = previewMarkupFlatKobo,
                            customerDiscountPercent = previewDisc,
                            feeKobo = previewFeeKobo
                        )
                        onSavePricingRules(listOf(updatedRule))
                        editingRule = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Save & Recalculate", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingRule = null }) { Text("Cancel") }
            }
        )
    }

    // DIALOG 2: PER-PLAN CUSTOM PRICE OVERRIDE WITH LIVE PROFIT CALCULATION
    editingPlan?.let { plan ->
        val isVariable = plan.serviceType in setOf("AIRTIME", "ELECTRICITY", "BETTING")
        var sellNairaText by remember(plan.id) { mutableStateOf((plan.sellingPriceKobo / 100.0).toString()) }
        var feeNairaText by remember(plan.id) { mutableStateOf((plan.feeKobo / 100.0).toString()) }
        var discText by remember(plan.id) { mutableStateOf(plan.discountPercent.toString()) }
        var enabled by remember(plan.id) { mutableStateOf(plan.available) }
        var allowBelowCost by remember(plan.id) { mutableStateOf(false) }

        val previewSell = CurrencyFormatter.nairaToKobo(sellNairaText) ?: plan.sellingPriceKobo
        val previewFee = CurrencyFormatter.nairaToKobo(feeNairaText) ?: plan.feeKobo
        val previewDisc = discText.toDoubleOrNull() ?: plan.discountPercent

        val liveProfitKobo = if (isVariable) {
            val ckCost = (100000.0 * (1.0 - plan.commissionPercent / 100.0)).roundToLong()
            val custPays = (100000.0 * (1.0 - previewDisc / 100.0)).roundToLong() + previewFee
            custPays - ckCost
        } else {
            previewSell + previewFee - plan.providerCostKobo
        }
        val isBelowCost = !isVariable && plan.providerCostKobo > 0L && (previewSell + previewFee) < plan.providerCostKobo

        AlertDialog(
            onDismissRequest = { editingPlan = null },
            title = { Text("Override (${plan.apiProviderName}): ${plan.productName}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = if (isVariable) {
                            "${plan.apiProviderName} Live Commission: ${plan.commissionPercent}%"
                        } else {
                            "${plan.apiProviderName} Wholesale Cost: ${CurrencyFormatter.formatKobo(plan.providerCostKobo)}"
                        },
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    if (!isVariable) {
                        OutlinedTextField(
                            value = sellNairaText,
                            onValueChange = { sellNairaText = it },
                            label = { Text("My Custom Selling Price (₦)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    } else {
                        OutlinedTextField(
                            value = discText,
                            onValueChange = { discText = it },
                            label = { Text("Custom Customer Discount (%)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true
                        )
                    }
                    OutlinedTextField(
                        value = feeNairaText,
                        onValueChange = { feeNairaText = it },
                        label = { Text("Convenience Fee (₦)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Plan Visible on Storefront")
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Surface(
                        color = if (liveProfitKobo >= 0L) StatusSuccess.copy(alpha = 0.14f) else StatusFailed.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "${if (isVariable) "My Profit per ₦1,000" else "My Profit per Order"}: ${if (liveProfitKobo >= 0L) "+" else ""}${CurrencyFormatter.formatKobo(liveProfitKobo)}",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMono),
                            color = if (liveProfitKobo >= 0L) StatusSuccess else StatusFailed,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    if (isBelowCost) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowBelowCost, onCheckedChange = { allowBelowCost = it })
                            Text(
                                text = "Confirm intentional below-cost promo price",
                                style = MaterialTheme.typography.labelSmall,
                                color = StatusFailed
                            )
                        }
                    }
                    if (plan.hasCustomOverride) {
                        OutlinedButton(
                            onClick = {
                                onSavePlanOverride(
                                    SavePlanOverrideRequest(
                                        serviceType = plan.serviceType,
                                        apiProvider = plan.apiProvider,
                                        providerCode = plan.providerCode,
                                        providerName = plan.providerName,
                                        productCode = plan.productCode,
                                        productName = plan.productName,
                                        providerCostKobo = plan.providerCostKobo,
                                        sellingPriceKobo = plan.sellingPriceKobo,
                                        feeKobo = plan.feeKobo,
                                        discountPercent = plan.discountPercent,
                                        commissionPercent = plan.commissionPercent,
                                        enabled = true,
                                        clearOverride = true
                                    )
                                )
                                editingPlan = null
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Filled.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Reset to Global Service Rule")
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        onSavePlanOverride(
                            SavePlanOverrideRequest(
                                serviceType = plan.serviceType,
                                apiProvider = plan.apiProvider,
                                providerCode = plan.providerCode,
                                providerName = plan.providerName,
                                productCode = plan.productCode,
                                productName = plan.productName,
                                validity = plan.validity,
                                dataVolume = plan.dataVolume,
                                providerCostKobo = plan.providerCostKobo,
                                sellingPriceKobo = previewSell,
                                feeKobo = previewFee,
                                discountPercent = previewDisc,
                                commissionPercent = plan.commissionPercent,
                                enabled = enabled,
                                clearOverride = false,
                                allowBelowCost = allowBelowCost
                            )
                        )
                        editingPlan = null
                    },
                    enabled = !isBelowCost || allowBelowCost
                ) {
                    Text("Save Override", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { editingPlan = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ProviderComparisonCard(row: ProviderComparisonRowDto) {
    val isVariable = row.serviceType in setOf("AIRTIME", "ELECTRICITY", "BETTING")
    val profitColor = when {
        row.expectedProfitKobo > 0L -> StatusSuccess
        row.expectedProfitKobo < 0L -> StatusFailed
        else -> StatusPending
    }
    val availabilityColor = when {
        !row.providerEnabled -> StatusFailed
        row.available -> StatusSuccess
        else -> StatusPending
    }
    val availabilityLabel = when {
        !row.providerEnabled -> "PROVIDER OFF"
        row.available -> "AVAILABLE"
        else -> "UNAVAILABLE"
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(
            width = if (row.isBestValue) 1.5.dp else 1.dp,
            color = if (row.isBestValue) StatusSuccess else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        color = if (row.apiProvider == "ALRAHUZDATA") {
                            MaterialTheme.colorScheme.tertiaryContainer
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(
                            text = row.apiProviderName,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (row.isBestValue) {
                        Surface(
                            color = StatusSuccess.copy(alpha = 0.14f),
                            shape = RoundedCornerShape(50)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = StatusSuccess, modifier = Modifier.size(12.dp))
                                Text(
                                    text = "Best Margin",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusSuccess,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                Surface(color = availabilityColor.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                    Text(
                        text = availabilityLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = availabilityColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            Column {
                Text(
                    text = "${row.providerName} • ${row.productName}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${row.serviceType} • ${row.categoryGroup.ifBlank { row.productCode }}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Provider Cost", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (isVariable) "${row.commissionPercent}% comm" else CurrencyFormatter.formatKobo(row.providerCostKobo),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Selling Price", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = if (isVariable) {
                            "${row.discountPercent}% disc + ${CurrencyFormatter.formatKobo(row.feeKobo)}"
                        } else {
                            CurrencyFormatter.formatKobo(row.sellingPriceKobo + row.feeKobo)
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(if (isVariable) "Profit / ₦1k" else "Expected Profit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${if (row.expectedProfitKobo >= 0) "+" else ""}${CurrencyFormatter.formatKobo(row.expectedProfitKobo)} (${row.profitMarginPercent}%)",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMono),
                        color = profitColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
