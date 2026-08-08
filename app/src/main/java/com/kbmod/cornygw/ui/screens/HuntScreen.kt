package com.kbmod.cornygw.ui.screens

import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kbmod.cornygw.data.Bands
import com.kbmod.cornygw.data.Geo
import com.kbmod.cornygw.data.HuntSettings
import com.kbmod.cornygw.data.PathLoss
import com.kbmod.cornygw.data.SignalQuality
import com.kbmod.cornygw.ui.HuntUiState
import com.kbmod.cornygw.ui.ScanUiState
import com.kbmod.cornygw.ui.Trend
import com.kbmod.cornygw.ui.components.InfoCard
import com.kbmod.cornygw.ui.components.Sparkline
import com.kbmod.cornygw.ui.components.StatRow
import com.kbmod.cornygw.ui.theme.SignalColors
import kotlin.math.roundToInt

@Composable
fun HuntScreen(
    huntState: HuntUiState,
    scanState: ScanUiState,
    settings: HuntSettings,
    onRescan: () -> Unit,
    onClearTarget: () -> Unit,
    onGoToNetworks: () -> Unit,
    contentPadding: PaddingValues,
) {
    val targetBssid = huntState.targetBssid
    if (targetBssid == null) {
        EmptyTarget(onGoToNetworks, contentPadding)
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        Text(
            text = huntState.targetSsid.ifBlank { "(hidden network)" },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = targetBssid,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SignalGauge(huntState)

        TrendCard(huntState)

        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    text = "Recent signal",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Sparkline(
                    points = huntState.history,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${huntState.history.size} readings · newest on the right",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        DetailsCard(huntState, settings)

        val current = huntState.current
        if (current != null && !Bands.isSharpBand(current.band)) {
            InfoCard(
                title = "2.4 GHz carries too well",
                body = "This radio is on 2.4 GHz, which passes through walls with little loss — " +
                    "half the street can read within a few dB. If this network also broadcasts " +
                    "on 5 or 6 GHz, hunt that radio instead: the faster fall-off is what lets " +
                    "you tell one house from the next.",
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }

        if (current == null) {
            InfoCard(
                title = "Target not in the last scan",
                body = "The radio dropped out of range or is between beacons. Signal history is " +
                    "kept, so walking back toward it will pick the trace up again.",
                container = MaterialTheme.colorScheme.errorContainer,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = onRescan, modifier = Modifier.weight(1f)) {
                Icon(Icons.Filled.Refresh, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Scan now")
            }
            OutlinedButton(onClick = onClearTarget, modifier = Modifier.weight(1f)) {
                Text("Change target")
            }
        }

        InfoCard(
            title = "How to use this screen",
            body = "Walk slowly and watch the trend, not the raw number — a stationary reading " +
                "swings several dB on its own. Hold the phone away from your body and keep it " +
                "in the same orientation: your own hand is worth 10 dB of attenuation, which " +
                "the model would otherwise read as distance. Sweep the whole area to find the " +
                "peak, then switch to the Survey tab to turn the walk into a position.",
        )

        if (scanState.staleScanCount >= 3) {
            InfoCard(
                title = "Readings are stale",
                body = "Android is serving cached scan results. The trace only advances on a " +
                    "genuinely fresh sweep, so it will look frozen until throttling lets up.",
                container = MaterialTheme.colorScheme.tertiaryContainer,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SignalGauge(state: HuntUiState) {
    val smoothed = state.smoothedRssi
    val color = smoothed?.let { SignalColors.forRssi(it) } ?: MaterialTheme.colorScheme.outline
    val fraction by animateFloatAsState(
        targetValue = smoothed?.let { SignalColors.fractionOf(it) } ?: 0f,
        label = "signalFraction",
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = smoothed?.let { "${it.roundToInt()}" } ?: "--",
                fontSize = 64.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
            Text(
                text = "dBm  ·  ${smoothed?.let { SignalQuality.of(it).label } ?: "no reading"}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .height(10.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    val radius = size.height / 2f
                    drawRoundRect(
                        color = Color.Gray.copy(alpha = 0.25f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                    )
                    if (fraction > 0f) {
                        drawRoundRect(
                            color = color,
                            size = androidx.compose.ui.geometry.Size(
                                width = size.width * fraction,
                                height = size.height,
                            ),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius),
                        )
                    }
                }
            }

            state.current?.let { current ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "raw ${current.rssi} dBm",
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TrendCard(state: HuntUiState) {
    val (icon, tint) = when (state.trend) {
        Trend.WARMER -> Icons.Filled.TrendingUp to MaterialTheme.colorScheme.primary
        Trend.COLDER -> Icons.Filled.TrendingDown to MaterialTheme.colorScheme.error
        Trend.STEADY -> Icons.Filled.TrendingFlat to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(32.dp))
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = state.trend.label,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = tint,
                )
                Text(
                    text = String.format(
                        "%+.1f dB per reading",
                        state.trendSlopeDbPerSample,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.peakRssi?.let { peak ->
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "best",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "$peak",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = SignalColors.forRssi(peak),
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(state: HuntUiState, settings: HuntSettings) {
    val current = state.current
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "Details",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))

            val reference = state.smoothedRssi ?: current?.rssi?.toDouble()
            if (reference != null) {
                val range = PathLoss.distanceRange(
                    rssi = reference.roundToInt(),
                    refRssiAt1m = settings.refRssiAt1m,
                    pathLossExponent = settings.pathLossExponent,
                )
                StatRow(
                    label = "Rough distance",
                    value = "${Geo.formatDistance(range.start)} – ${Geo.formatDistance(range.endInclusive)}",
                )
            }

            current?.let {
                StatRow("Band", it.band.label)
                StatRow("Channel", it.channel.toString())
                StatRow("Frequency", "${it.frequencyMhz} MHz")
                StatRow("Security", it.security)
            }
            StatRow("Path loss exponent", String.format("%.1f", settings.pathLossExponent))
            StatRow("Reference at 1 m", "${settings.refRssiAt1m.roundToInt()} dBm")

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Distance from signal strength alone is a rough bracket, not a " +
                    "measurement. It assumes an average amount of wall between you and the " +
                    "radio, which is the one thing that varies most between houses.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyTarget(onGoToNetworks: () -> Unit, contentPadding: PaddingValues) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.Wifi,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.outline,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "No target selected",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Pick the network you keep seeing from the Networks tab, then come back " +
                "here to track its signal as you move.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onGoToNetworks) {
            Text("Browse networks")
        }
    }
}
