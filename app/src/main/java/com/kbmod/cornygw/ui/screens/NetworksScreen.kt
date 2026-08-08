package com.kbmod.cornygw.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kbmod.cornygw.data.ApSighting
import com.kbmod.cornygw.data.NetworkGroup
import com.kbmod.cornygw.data.SignalQuality
import com.kbmod.cornygw.ui.HuntUiState
import com.kbmod.cornygw.ui.ScanUiState
import com.kbmod.cornygw.ui.components.InfoCard
import com.kbmod.cornygw.ui.components.SignalBars
import com.kbmod.cornygw.ui.theme.SignalColors

@Composable
fun NetworksScreen(
    state: ScanUiState,
    huntState: HuntUiState,
    onQueryChange: (String) -> Unit,
    onSelect: (ApSighting) -> Unit,
    contentPadding: PaddingValues,
) {
    val filtered = remember(state.groups, state.query) {
        val query = state.query.trim()
        if (query.isEmpty()) {
            state.groups
        } else {
            state.groups.filter { group ->
                group.displaySsid.contains(query, ignoreCase = true) ||
                    group.radios.any { it.bssid.contains(query, ignoreCase = true) }
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                label = { Text("Filter by name or BSSID") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.query.isNotEmpty()) {
                        IconButton(onClick = { onQueryChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear filter")
                        }
                    }
                },
                singleLine = true,
            )
        }

        if (!state.wifiEnabled) {
            item {
                InfoCard(
                    title = "Wi-Fi is off",
                    body = "Scanning needs Wi-Fi enabled. You do not have to be connected to " +
                        "anything — the app only listens for beacons.",
                    container = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        if (state.staleScanCount >= 3) {
            item {
                InfoCard(
                    title = "Results are being throttled",
                    body = "Android is returning cached scans instead of new sweeps — the last " +
                        "${state.staleScanCount} came back stale. Readings will update slowly. " +
                        "Developer options → Wi-Fi scan throttling turns this off and makes the " +
                        "hunt dramatically more responsive.",
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        if (state.groups.isEmpty()) {
            item {
                InfoCard(
                    title = "Nothing found yet",
                    body = "Waiting for the first scan. If this stays empty, check that " +
                        "location services are on — Android gates Wi-Fi scan results behind " +
                        "them even when you only want signal strengths.",
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        items(filtered, key = { it.ssid + it.radios.first().bssid }) { group ->
            NetworkCard(
                group = group,
                selectedBssid = huntState.targetBssid,
                onSelect = onSelect,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun NetworkCard(
    group: NetworkGroup,
    selectedBssid: String?,
    onSelect: (ApSighting) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val strongest = group.strongest
    val isSelected = group.radios.any { it.bssid == selectedBssid }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
    ) {
        Column(
            Modifier
                .clickable {
                    // One radio means there is nothing to disambiguate: selecting
                    // it directly saves a pointless tap.
                    if (group.radios.size == 1) onSelect(strongest) else expanded = !expanded
                }
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = group.displaySsid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = buildString {
                            append(strongest.band.label)
                            append(" · ch ${strongest.channel}")
                            append(" · ${strongest.security}")
                            if (group.radios.size > 1) append(" · ${group.radios.size} radios")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "${group.bestRssi} dBm",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = SignalColors.forRssi(group.bestRssi),
                    )
                    Spacer(Modifier.height(4.dp))
                    SignalBars(group.bestRssi)
                }

                if (group.radios.size > 1) {
                    Icon(
                        imageVector = if (expanded) Icons.Filled.KeyboardArrowUp
                        else Icons.Filled.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        modifier = Modifier.padding(start = 6.dp),
                    )
                }
            }

            AnimatedVisibility(visible = expanded && group.radios.size > 1) {
                Column {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "This name is broadcast by ${group.radios.size} radios. Pick one " +
                            "— averaging them together would point at the middle of a building " +
                            "rather than at hardware.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    group.radios.forEach { radio ->
                        RadioRow(
                            radio = radio,
                            isSelected = radio.bssid == selectedBssid,
                            onSelect = { onSelect(radio) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioRow(
    radio: ApSighting,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
            )
            .clickable(onClick = onSelect)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = radio.bssid,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = "${radio.band.label} · ch ${radio.channel} · " +
                    SignalQuality.of(radio.rssi).label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = "${radio.rssi}",
            style = MaterialTheme.typography.titleSmall,
            fontFamily = FontFamily.Monospace,
            color = SignalColors.forRssi(radio.rssi),
        )
        Box(
            Modifier
                .padding(start = 8.dp)
                .size(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(SignalColors.forRssi(radio.rssi)),
        )
    }
}
