package com.kbmod.cornygw.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kbmod.cornygw.data.Estimator
import com.kbmod.cornygw.data.Geo
import com.kbmod.cornygw.data.SavedSurvey
import com.kbmod.cornygw.ui.HuntUiState
import com.kbmod.cornygw.ui.SurveyUiState
import com.kbmod.cornygw.ui.components.DirectionArrow
import com.kbmod.cornygw.ui.components.InfoCard
import com.kbmod.cornygw.ui.components.StatRow
import com.kbmod.cornygw.ui.components.SurveyMap
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SurveyScreen(
    surveyState: SurveyUiState,
    huntState: HuntUiState,
    onToggleRecording: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onRecompute: () -> Unit,
    onLoadSurvey: (SavedSurvey) -> Unit,
    onDeleteSurvey: (SavedSurvey) -> Unit,
    onShareSurvey: (SavedSurvey) -> Intent,
    onDismissMessage: () -> Unit,
    onGoToNetworks: () -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val view = LocalView.current

    // A survey is a walk of several minutes; letting the screen sleep would
    // stop the scan pump and silently end the recording.
    DisposableEffect(surveyState.isRecording) {
        view.keepScreenOn = surveyState.isRecording
        onDispose { view.keepScreenOn = false }
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

        val targetBssid = huntState.targetBssid
        if (targetBssid == null) {
            InfoCard(
                title = "Pick a target first",
                body = "A survey records the signal of one specific radio against your GPS " +
                    "position. Choose which network to locate from the Networks tab.",
            )
            Button(onClick = onGoToNetworks) { Text("Browse networks") }
            SavedSurveysSection(
                surveys = surveyState.savedSurveys,
                onLoad = onLoadSurvey,
                onDelete = onDeleteSurvey,
                onShare = { context.startActivity(Intent.createChooser(onShareSurvey(it), "Share survey")) },
            )
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        Text(
            text = "Locating ${huntState.targetSsid.ifBlank { "(hidden network)" }}",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = targetBssid,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        surveyState.message?.let { message ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = onDismissMessage) { Text("OK") }
                }
            }
        }

        RecordingCard(surveyState, onToggleRecording)

        SurveyMap(
            samples = surveyState.samples,
            fit = surveyState.fit,
            currentLocation = surveyState.location,
            headingDegrees = surveyState.headingDegrees,
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
        )

        if (surveyState.isComputing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }

        val fit = surveyState.fit
        if (fit != null) {
            ResultCard(fit = fit, surveyState = surveyState, ssid = huntState.targetSsid)
        } else if (surveyState.samples.isNotEmpty()) {
            InfoCard(
                title = "Not enough to solve yet",
                body = "Keep walking. The fit needs readings from at least five well-separated " +
                    "places, and it only becomes trustworthy once they surround the target.",
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = surveyState.samples.isNotEmpty(),
            ) {
                Icon(Icons.Filled.Save, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Save")
            }
            OutlinedButton(
                onClick = onRecompute,
                modifier = Modifier.weight(1f),
                enabled = surveyState.samples.size >= 6,
            ) {
                Text("Recompute")
            }
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
                enabled = surveyState.samples.isNotEmpty(),
            ) {
                Text("Clear")
            }
        }

        InfoCard(
            title = "How to walk a good survey",
            body = "Cover the target from as many sides as the street allows — front, back, " +
                "both ends. Signal strength alone only fixes how far away something is; it is " +
                "the change in that distance as you move around it that fixes where. Keep " +
                "moving at a steady walk, hold the phone away from your body, and do not stop " +
                "in one spot to collect samples: readings taken from the same place add " +
                "confidence without adding information.",
        )

        SavedSurveysSection(
            surveys = surveyState.savedSurveys,
            onLoad = onLoadSurvey,
            onDelete = onDeleteSurvey,
            onShare = { context.startActivity(Intent.createChooser(onShareSurvey(it), "Share survey")) },
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RecordingCard(state: SurveyUiState, onToggle: () -> Unit) {
    val fix = state.location
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (state.isRecording) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = onToggle,
                    colors = if (state.isRecording) {
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        ButtonDefaults.buttonColors()
                    },
                ) {
                    Icon(
                        imageVector = if (state.isRecording) Icons.Filled.Stop
                        else Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(if (state.isRecording) "Stop" else "Start survey")
                }

                Spacer(Modifier.size(14.dp))

                Column {
                    Text(
                        text = "${state.samples.size} samples",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = when {
                            fix == null -> "waiting for GPS"
                            else -> "GPS ±${fix.accuracy.roundToInt()} m"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (fix != null && fix.accuracy <= 15f) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            if (state.isRecording) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Recording. Only genuinely fresh scans are stored — if Android " +
                        "throttles scanning, samples arrive slowly rather than repeating a " +
                        "stale reading at a new position.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ResultCard(
    fit: Estimator.Fit,
    surveyState: SurveyUiState,
    ssid: String,
) {
    val context = LocalContext.current
    val confidenceColor = when (fit.confidence) {
        Estimator.Confidence.HIGH -> MaterialTheme.colorScheme.primary
        Estimator.Confidence.MEDIUM -> MaterialTheme.colorScheme.tertiary
        Estimator.Confidence.LOW -> MaterialTheme.colorScheme.error
    }

    val youPoint = surveyState.location?.let { fix ->
        fit.frame.toLocal(fix.latitude, fix.longitude)
    }
    val distanceToTarget = youPoint?.distanceTo(fit.local)
    val bearingToTarget = youPoint?.let { Geo.bearingDegrees(it, fit.local) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Estimated source",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "${fit.confidence.label} confidence",
                    style = MaterialTheme.typography.labelLarge,
                    color = confidenceColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            Spacer(Modifier.height(12.dp))

            if (distanceToTarget != null && bearingToTarget != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DirectionArrow(
                        rotationDegrees = (
                            bearingToTarget - (surveyState.headingDegrees ?: 0.0)
                            ).toFloat(),
                        color = confidenceColor,
                        modifier = Modifier.size(84.dp),
                    )
                    Spacer(Modifier.size(14.dp))
                    Column {
                        Text(
                            text = Geo.formatDistance(distanceToTarget),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "${Geo.compassPoint(bearingToTarget)} " +
                                "(${bearingToTarget.roundToInt()}°) from you",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (surveyState.headingDegrees == null) {
                            Text(
                                text = "no compass — arrow shows true bearing",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            StatRow(
                label = "Uncertainty",
                value = (if (fit.radiusIsLowerBound) "> " else "± ") +
                    Geo.formatDistance(fit.uncertaintyRadiusM),
                valueColor = confidenceColor,
            )
            StatRow("Coordinates", Geo.formatLatLon(fit.latitude, fit.longitude))
            fit.mirrorCandidate?.let { mirror ->
                val (mirrorLat, mirrorLon) = fit.frame.toLatLon(mirror)
                StatRow(
                    label = "Equally good alternative",
                    value = Geo.formatLatLon(mirrorLat, mirrorLon),
                    valueColor = MaterialTheme.colorScheme.error,
                )
            }
            StatRow("Residual", String.format("%.1f dB", fit.residualRmsDb))
            StatRow("Variance explained", "${(fit.fitQuality * 100).roundToInt()}%")
            StatRow(
                "Angular coverage",
                "${(fit.angularCoverage * 8).roundToInt()}/8 sectors",
            )
            StatRow("Signal spread", "${fit.rssiSpreadDb} dB")
            StatRow(
                "Samples",
                "${fit.binnedSampleCount} places (${fit.rawSampleCount} readings)",
            )
            StatRow("Fitted power at 1 m", "${fit.refRssiAt1m.roundToInt()} dBm")

            Spacer(Modifier.height(10.dp))
            Text(
                text = fit.advice,
                style = MaterialTheme.typography.bodySmall,
                color = if (fit.isMirrorAmbiguous) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (fit.isMirrorAmbiguous) FontWeight.Medium else FontWeight.Normal,
            )

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    val label = Uri.encode(ssid.ifBlank { "Estimated AP" })
                    val uri = Uri.parse(
                        "geo:${fit.latitude},${fit.longitude}" +
                            "?q=${fit.latitude},${fit.longitude}($label)",
                    )
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.Place, contentDescription = null, Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Open estimate in Maps")
            }

            if (fit.confidence != Estimator.Confidence.HIGH) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Treat this as a direction to investigate, not an address. The " +
                        "uncertainty circle is usually wider than a house.",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun SavedSurveysSection(
    surveys: List<SavedSurvey>,
    onLoad: (SavedSurvey) -> Unit,
    onDelete: (SavedSurvey) -> Unit,
    onShare: (SavedSurvey) -> Unit,
) {
    if (surveys.isEmpty()) return

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = "Saved surveys",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            surveys.forEachIndexed { index, survey ->
                if (index > 0) Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(1f)
                            .padding(vertical = 4.dp),
                    ) {
                        Text(
                            text = survey.label.ifBlank { survey.bssid },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "${survey.sampleCount} samples · " +
                                DATE_FORMAT.format(Date(survey.savedAtMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { onLoad(survey) }) { Text("Load") }
                    IconButton(onClick = { onShare(survey) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share ${survey.label}")
                    }
                    IconButton(onClick = { onDelete(survey) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete ${survey.label}")
                    }
                }
            }
        }
    }
}

private val DATE_FORMAT = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
