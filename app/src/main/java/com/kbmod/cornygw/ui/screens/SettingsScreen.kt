package com.kbmod.cornygw.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kbmod.cornygw.data.HuntSettings
import com.kbmod.cornygw.ui.components.InfoCard
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    settings: HuntSettings,
    onUpdate: ((HuntSettings) -> HuntSettings) -> Unit,
    contentPadding: PaddingValues,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Spacer(Modifier.height(2.dp))

        SettingCard(
            title = "Path loss exponent",
            value = String.format("%.1f", settings.pathLossExponent),
            explanation = "How fast signal fades with distance. 2.0 is open air, 3.0 suits a " +
                "typical suburban street with a wall or two in the way, 4.0+ means dense " +
                "construction. This is the single biggest lever on every distance the app " +
                "prints. If the fit consistently places the source too far away, raise it.",
        ) {
            Slider(
                value = settings.pathLossExponent.toFloat(),
                onValueChange = { value ->
                    onUpdate { it.copy(pathLossExponent = value.toDouble()) }
                },
                valueRange = 1.6f..5.0f,
                steps = 33,
            )
        }

        SettingCard(
            title = "Reference power at 1 m",
            value = "${settings.refRssiAt1m.roundToInt()} dBm",
            explanation = "What a typical router reads at one metre. Only affects the rough " +
                "distance shown on the Hunt screen — the survey solver fits this value from " +
                "your data instead of trusting it, which is why survey distances are the more " +
                "reliable of the two.",
        ) {
            Slider(
                value = settings.refRssiAt1m.toFloat(),
                onValueChange = { value ->
                    onUpdate { it.copy(refRssiAt1m = value.toDouble()) }
                },
                valueRange = -60f..-20f,
                steps = 39,
            )
        }

        SettingCard(
            title = "Scan interval",
            value = "${settings.scanIntervalMs / 1000} s",
            explanation = "How often to ask the system for a fresh sweep. Android throttles " +
                "foreground apps to roughly four scans per two minutes, so asking faster than " +
                "30 s mostly returns cached results — unless you turn Wi-Fi scan throttling " +
                "off in developer options, in which case a short interval makes both the hunt " +
                "and the survey far more responsive.",
        ) {
            Slider(
                value = (settings.scanIntervalMs / 1000).toFloat(),
                onValueChange = { value ->
                    onUpdate { it.copy(scanIntervalMs = value.toLong() * 1000) }
                },
                valueRange = 2f..60f,
                steps = 57,
            )
        }

        SettingCard(
            title = "Smoothing",
            value = String.format("%.2f", settings.smoothing),
            explanation = "Weight given to the newest reading. Lower is steadier but slower to " +
                "react; higher follows the raw signal, jitter included. Takes effect on the " +
                "next target you select.",
        ) {
            Slider(
                value = settings.smoothing.toFloat(),
                onValueChange = { value -> onUpdate { it.copy(smoothing = value.toDouble()) } },
                valueRange = 0.1f..0.9f,
                steps = 15,
            )
        }

        InfoCard(
            title = "Calibrating the exponent",
            body = "You cannot stand next to a neighbour's router, but you can calibrate on " +
                "your own: run a survey on your own access point, then adjust the exponent " +
                "until the estimate lands where the hardware actually is. The value that works " +
                "for your walls will work for the street.",
        )

        InfoCard(
            title = "What this app does and does not do",
            body = "It listens for the beacon frames every access point broadcasts publicly, " +
                "and records signal strength against GPS. It never connects to a network, " +
                "never reads traffic, and never tries a password. Everything stays on the " +
                "device unless you export a CSV yourself.\n\n" +
                "Locating a transmitter is a legitimate thing to do — it is how you resolve " +
                "interference, find your own misplaced hardware, or settle a curiosity about a " +
                "name in a list. Where it stops being fine is what you do with the answer: an " +
                "estimate pointing at a house is not a licence to make anyone uncomfortable. " +
                "Take the uncertainty circle seriously; it is usually wider than a house, and " +
                "confidently accusing the wrong neighbour is the most likely outcome of " +
                "ignoring it.",
            container = MaterialTheme.colorScheme.tertiaryContainer,
        )

        InfoCard(
            title = "Reading the numbers",
            body = "Residual is how well one single source position explains every reading; " +
                "under about 4 dB is a good fit. Variance explained says how much better that " +
                "beats assuming the signal is the same everywhere. Angular coverage is the " +
                "one people skip and shouldn't — a tight residual from a one-sided walk is " +
                "precise and wrong, because signal strength fixes distance, never direction.",
        )

        Text(
            text = "Corny GW · local-only Wi-Fi direction finding",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SettingCard(
    title: String,
    value: String,
    explanation: String,
    control: @Composable () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            control()
            Text(
                text = explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
