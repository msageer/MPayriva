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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.remote.ProviderControlsResponse
import com.example.data.remote.ProviderStatusSummaryDto
import com.example.ui.theme.JetBrainsMono
import com.example.ui.theme.StatusFailed
import com.example.ui.theme.StatusPending
import com.example.ui.theme.StatusSuccess

private val ROUTABLE_SERVICES = listOf(
    "DATA" to "Mobile Data Plans",
    "AIRTIME" to "Airtime VTU",
    "CABLE_TV" to "Cable TV",
    "ELECTRICITY" to "Electricity Bills",
    "RECHARGE_CARD" to "Recharge e-PINs",
    "WAEC_PIN" to "WAEC PINs",
    "JAMB_PIN" to "JAMB PINs"
)

private val ROUTING_MODES = listOf(
    "AUTO_CHEAPEST" to "Auto (Best Price)",
    "CLUBKONNECT" to "ClubKonnect",
    "ALRAHUZDATA" to "AlrahuzData"
)

@Composable
fun AdminProvidersControlCard(
    controls: ProviderControlsResponse?,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onToggleProvider: (providerId: String, enabled: Boolean) -> Unit,
    onUpdateServiceRouting: (serviceType: String, routingMode: String) -> Unit
) {
    val providers = controls?.providers?.takeIf { it.isNotEmpty() } ?: listOf(
        ProviderStatusSummaryDto(id = "CLUBKONNECT", name = "ClubKonnect", configured = true, enabled = true),
        ProviderStatusSummaryDto(id = "ALRAHUZDATA", name = "AlrahuzData", configured = false, enabled = true)
    )
    val routing = controls?.serviceRouting.orEmpty()
    val allDisabled = providers.none { it.enabled }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // 1. Master ON/OFF Switches for Each API Provider
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(
                                "API Provider ON/OFF Controls",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "Turn any API provider ON or OFF live. Disabled providers never receive new transactions.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = onRefresh, enabled = !isBusy) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh providers")
                    }
                }

                if (allDisabled) {
                    Surface(
                        color = StatusFailed.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Filled.Warning, contentDescription = null, tint = StatusFailed, modifier = Modifier.size(18.dp))
                            Text(
                                "Warning: All API providers are currently OFF. Customer utility purchases will be blocked until at least one provider is enabled.",
                                style = MaterialTheme.typography.bodySmall,
                                color = StatusFailed,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                providers.forEach { prov ->
                    val statusTint = when {
                        !prov.enabled -> StatusFailed
                        !prov.configured -> StatusPending
                        prov.reachable -> StatusSuccess
                        else -> StatusPending
                    }
                    val statusText = when {
                        !prov.enabled -> "OFF • BLOCKED"
                        !prov.configured -> "TOKEN NOT SET"
                        prov.reachable -> "ONLINE • ACTIVE"
                        else -> "CONFIGURED"
                    }

                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (prov.enabled) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                        } else {
                            StatusFailed.copy(alpha = 0.06f)
                        },
                        border = BorderStroke(
                            1.dp,
                            if (prov.enabled) MaterialTheme.colorScheme.outlineVariant else StatusFailed.copy(alpha = 0.35f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = prov.name,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Surface(color = statusTint.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (prov.enabled) Icons.Filled.CheckCircle else Icons.Filled.Block,
                                                    contentDescription = null,
                                                    tint = statusTint,
                                                    modifier = Modifier.size(12.dp)
                                                )
                                                Text(
                                                    text = statusText,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = statusTint,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = buildString {
                                            append("${prov.livePlanCount} live plans")
                                            if (!prov.balanceNaira.isNullOrBlank()) {
                                                append(" • Float: ₦${prov.balanceNaira}")
                                            }
                                        },
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMono),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = prov.enabled,
                                    enabled = !isBusy,
                                    onCheckedChange = { next -> onToggleProvider(prov.id, next) },
                                    modifier = Modifier.testTag("admin_provider_switch_${prov.id.lowercase()}")
                                )
                            }

                            if (!prov.enabled) {
                                Text(
                                    text = "Paused — excluded from storefront & strictly blocked from receiving new orders.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = StatusFailed
                                )
                            } else if (!prov.configured && prov.id == "ALRAHUZDATA") {
                                Text(
                                    text = "Add ALRAHUZDATA_API_TOKEN to your Render backend environment to stream live AlrahuzData plans & balances.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // 2. Per-Service Routing Rules
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.AltRoute, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            "Service Provider Routing",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Choose Auto (lowest cost enabled provider) or pin a service to ClubKonnect or AlrahuzData.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ROUTABLE_SERVICES.forEachIndexed { idx, (svcKey, label) ->
                    val currentRoute = (routing[svcKey] ?: "AUTO_CHEAPEST").uppercase()
                    if (idx > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            ROUTING_MODES.forEach { (modeKey, modeLabel) ->
                                FilterChip(
                                    selected = currentRoute == modeKey,
                                    enabled = !isBusy,
                                    onClick = {
                                        if (currentRoute != modeKey) {
                                            onUpdateServiceRouting(svcKey, modeKey)
                                        }
                                    },
                                    label = { Text(modeLabel, style = MaterialTheme.typography.labelSmall) },
                                    modifier = Modifier.testTag("admin_route_${svcKey.lowercase()}_${modeKey.lowercase()}")
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
