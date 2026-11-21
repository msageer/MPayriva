package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.remote.LiveCatalogPlanDto

internal object CatalogCategoryClassifier {
    val dataCategoryOrder = listOf(
        "SME",
        "Corporate / CG",
        "Daily",
        "Weekly",
        "Monthly",
        "Awoof / Promo",
        "Mega / Multi-Month",
        "Direct"
    )

    val broadbandCategoryOrder = listOf(
        "Daily & Flexi",
        "Weekly",
        "Monthly",
        "Unlimited",
        "Multi-Month & Annual",
        "Voice & Airtime",
        "Standard"
    )

    private val knownDataCategories = dataCategoryOrder.toSet()
    private val knownBroadbandCategories = broadbandCategoryOrder.toSet()

    fun resolveDataCategory(plan: LiveCatalogPlanDto): String {
        val raw = plan.categoryGroup.trim()
        if (raw in knownDataCategories) return raw

        val text = "${plan.productName} ${plan.validity}".lowercase()
        return when {
            Regex("\\bsme\\b|\\bsme\\s*2\\b").containsMatchIn(text) -> "SME"
            Regex("\\bcorporate\\b|\\bcg\\b|\\bc\\.g\\b|\\bgifting\\b").containsMatchIn(text) -> "Corporate / CG"
            Regex("\\bawoof\\b|\\bpromo\\b|\\bbonus\\b|\\bsocial\\b|\\bwhatsapp\\b|\\btiktok\\b|\\binstagram\\b|\\byoutube\\b|\\bnight\\b|\\bweekend\\b|\\bopera\\b|\\bstream\\b|\\bcampus\\b|\\bxtra\\b").containsMatchIn(text) -> "Awoof / Promo"
            Regex("\\b(60|90|120|180|365)\\s*day|\\b(2|3|4|6|12)\\s*month|\\b1\\s*year|\\byearly\\b|\\bannual\\b|\\bmega\\b|\\brouter\\b|\\bmifi\\b|\\b5g\\b|\\bbroadband\\b|\\bhome\\b").containsMatchIn(text) -> "Mega / Multi-Month"
            Regex("\\b(1|2|3|4|5)\\s*day|\\bdaily\\b|\\b24\\s*h|\\b48\\s*h").containsMatchIn(text) -> "Daily"
            Regex("\\b(7|14)\\s*day|\\bweekly\\b|\\b(1|2)\\s*week").containsMatchIn(text) -> "Weekly"
            Regex("\\b(28|30|31)\\s*day|\\bmonthly\\b|\\b1\\s*month").containsMatchIn(text) -> "Monthly"
            else -> "Direct"
        }
    }

    fun resolveBroadbandCategory(plan: LiveCatalogPlanDto): String {
        val raw = plan.categoryGroup.trim()
        if (raw in knownBroadbandCategories) return raw

        val text = "${plan.productName} ${plan.validity}".lowercase()
        return when {
            Regex("\\bvoice\\b|\\btalk\\b|\\bcall\\b|\\bairtime\\b|\\bmin(ute)?s?\\b").containsMatchIn(text) -> "Voice & Airtime"
            Regex("\\bunlimited\\b|\\balways\\s*on\\b|\\bfreedom\\b|\\bwild\\b|\\bsuper\\b").containsMatchIn(text) -> "Unlimited"
            Regex("\\b(60|90|120|180|365)\\s*day|\\b(2|3|4|6|12)\\s*month|\\b1\\s*year|\\bannual\\b|\\byearly\\b|\\bjumbo\\b").containsMatchIn(text) -> "Multi-Month & Annual"
            Regex("\\b(1|2|3|4|5)\\s*day|\\bdaily\\b|\\bflexi\\b|\\blite\\b|\\bmini\\b|\\bweekend\\b|\\bnight\\b|\\bmidnite\\b|\\bmidnight\\b").containsMatchIn(text) -> "Daily & Flexi"
            Regex("\\b(7|14)\\s*day|\\bweekly\\b|\\b(1|2)\\s*week").containsMatchIn(text) -> "Weekly"
            Regex("\\b(28|30|31)\\s*day|\\bmonthly\\b|\\b1\\s*month|\\banytime\\b|\\bbigga\\b|\\bbuma\\b|\\bvalue\\b|\\bclassic\\b").containsMatchIn(text) -> "Monthly"
            else -> "Standard"
        }
    }

    fun groupAndSort(
        plans: List<LiveCatalogPlanDto>,
        order: List<String>,
        classifier: (LiveCatalogPlanDto) -> String
    ): List<Pair<String, List<LiveCatalogPlanDto>>> {
        val grouped = plans.groupBy(classifier)
        return grouped.entries
            .sortedWith(
                compareBy<Map.Entry<String, List<LiveCatalogPlanDto>>> { entry ->
                    val idx = order.indexOf(entry.key)
                    if (idx >= 0) idx else order.size
                }.thenBy { it.key }
            )
            .map { (cat, items) ->
                cat to items.sortedBy { it.sellingPriceKobo + it.feeKobo }
            }
    }
}

@Composable
internal fun PlanCategoryFilterStrip(
    categories: List<Pair<String, Int>>,
    totalCount: Int,
    selectedCategory: String,
    onSelectCategory: (String) -> Unit,
    testTagPrefix: String
) {
    if (categories.size <= 1) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedCategory == "All",
            onClick = { onSelectCategory("All") },
            label = { Text("All ($totalCount)", maxLines = 1, softWrap = false) },
            modifier = Modifier.testTag("${testTagPrefix}_cat_all")
        )
        categories.forEach { (category, count) ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onSelectCategory(category) },
                label = { Text("$category ($count)", maxLines = 1, softWrap = false) },
                modifier = Modifier.testTag("${testTagPrefix}_cat_${category.lowercase().replace(Regex("[^a-z0-9]+"), "_")}")
            )
        }
    }
}

@Composable
internal fun PlanCategorySectionHeader(
    categoryName: String,
    planCount: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                color = MaterialTheme.colorScheme.secondary,
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .width(4.dp)
                    .height(16.dp)
            ) {}
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = categoryName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Text(
                text = "$planCount ${if (planCount == 1) "plan" else "plans"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}
