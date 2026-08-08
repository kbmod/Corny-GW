package com.kbmod.cornygw.ui.components

import android.location.Location
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.kbmod.cornygw.data.Estimator
import com.kbmod.cornygw.data.LocalFrame
import com.kbmod.cornygw.data.LocalPoint
import com.kbmod.cornygw.data.SurveySample
import com.kbmod.cornygw.ui.theme.SignalColors
import kotlin.math.max
import kotlin.math.min

/**
 * Plan view of a survey in a local metric frame, north up.
 *
 * Deliberately not a street map: rendering tiles would need a network
 * dependency and an API key, and would invite the user to read a
 * pixel-perfect address off a fit whose uncertainty is measured in tens of
 * metres. The shaded uncertainty disc is the honest primary output; the export
 * exists for when you genuinely want to overlay this on a real map.
 */
@Composable
fun SurveyMap(
    samples: List<SurveySample>,
    fit: Estimator.Fit?,
    currentLocation: Location?,
    headingDegrees: Double?,
    modifier: Modifier = Modifier,
) {
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    val outlineColor = MaterialTheme.colorScheme.outline
    val estimateColor = MaterialTheme.colorScheme.error
    val youColor = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(surfaceColor),
    ) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val paddingPx = with(density) { 24.dp.toPx() }

        val frame = fit?.frame ?: framePointingAt(samples, currentLocation)

        if (frame == null) {
            Text(
                text = "No positions recorded yet",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            return@BoxWithConstraints
        }

        val trackPoints = samples.map { frame.toLocal(it.latitude, it.longitude) to it.rssi }
        val youPoint = currentLocation?.let { frame.toLocal(it.latitude, it.longitude) }

        val world = worldBounds(
            trackPoints.map { it.first },
            fit,
            youPoint,
        )

        val usableWidth = max(1f, widthPx - paddingPx * 2)
        val usableHeight = max(1f, heightPx - paddingPx * 2)
        // One uniform scale for both axes: an anisotropic plot would make a
        // circular uncertainty region look directional, which is exactly the
        // wrong thing to imply.
        val scale = min(
            usableWidth / max(1.0, world.width).toFloat(),
            usableHeight / max(1.0, world.height).toFloat(),
        )

        fun project(point: LocalPoint) = Offset(
            x = widthPx / 2f + ((point.east - world.centreEast) * scale).toFloat(),
            y = heightPx / 2f - ((point.north - world.centreNorth) * scale).toFloat(),
        )

        val scaleBarMetres = niceScaleLength(world.width / 4.0)

        Canvas(Modifier.fillMaxSize()) {
            fit?.let { result ->
                val centre = project(result.local)
                val radiusPx = (result.uncertaintyRadiusM * scale).toFloat()
                if (radiusPx > 1f) {
                    drawCircle(estimateColor.copy(alpha = 0.12f), radiusPx, centre)
                    drawCircle(
                        color = estimateColor.copy(alpha = 0.5f),
                        radius = radiusPx,
                        center = centre,
                        style = Stroke(
                            width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f)),
                        ),
                    )
                }
            }

            // Walking track, oldest to newest, so the route reads as a path.
            for (index in 1 until trackPoints.size) {
                drawLine(
                    color = outlineColor.copy(alpha = 0.35f),
                    start = project(trackPoints[index - 1].first),
                    end = project(trackPoints[index].first),
                    strokeWidth = 2f,
                )
            }

            for ((point, rssi) in trackPoints) {
                drawCircle(
                    color = SignalColors.forRssi(rssi),
                    radius = 6f,
                    center = project(point),
                )
            }

            fit?.mirrorCandidate?.let { mirror ->
                // Drawn hollow to read as "the other possibility", not a
                // second source.
                drawCrosshair(project(mirror), estimateColor.copy(alpha = 0.55f), hollow = true)
            }

            fit?.let { result ->
                drawCrosshair(project(result.local), estimateColor)
            }

            youPoint?.let { point ->
                val centre = project(point)
                drawCircle(youColor, 10f, centre)
                drawCircle(Color.White, 4f, centre)
                headingDegrees?.let { heading ->
                    rotate(degrees = heading.toFloat(), pivot = centre) {
                        drawLine(
                            color = youColor,
                            start = centre,
                            end = Offset(centre.x, centre.y - 26f),
                            strokeWidth = 4f,
                        )
                    }
                }
            }

            val barPx = (scaleBarMetres * scale).toFloat()
            val barY = heightPx - paddingPx * 0.5f
            val barStart = Offset(paddingPx * 0.5f, barY)
            drawLine(onSurface, barStart, Offset(barStart.x + barPx, barY), strokeWidth = 3f)
            drawLine(
                onSurface,
                barStart,
                Offset(barStart.x, barY - 8f),
                strokeWidth = 3f,
            )
            drawLine(
                onSurface,
                Offset(barStart.x + barPx, barY),
                Offset(barStart.x + barPx, barY - 8f),
                strokeWidth = 3f,
            )
        }

        Text(
            text = "N ↑",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = onSurface,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        )

        Text(
            text = "${scaleBarMetres.toInt()} m",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = onSurface,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 20.dp),
        )

        Row(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LegendDot("weak", SignalColors.forRssi(-90))
            LegendDot("strong", SignalColors.forRssi(-45))
            if (fit != null) LegendDot("estimate", estimateColor)
            if (fit?.mirrorCandidate != null) {
                LegendDot("or here", estimateColor.copy(alpha = 0.55f))
            }
        }
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .padding(end = 3.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
                .padding(4.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun DrawScope.drawCrosshair(centre: Offset, color: Color, hollow: Boolean = false) {
    val arm = if (hollow) 12f else 16f
    val width = if (hollow) 2.5f else 4f
    drawLine(color, Offset(centre.x - arm, centre.y), Offset(centre.x + arm, centre.y), width)
    drawLine(color, Offset(centre.x, centre.y - arm), Offset(centre.x, centre.y + arm), width)
    drawCircle(color, if (hollow) 7f else 9f, centre, style = Stroke(width = width))
}

private data class WorldBounds(
    val centreEast: Double,
    val centreNorth: Double,
    val width: Double,
    val height: Double,
)

private fun worldBounds(
    track: List<LocalPoint>,
    fit: Estimator.Fit?,
    you: LocalPoint?,
): WorldBounds {
    val easts = mutableListOf<Double>()
    val norths = mutableListOf<Double>()

    track.forEach { easts += it.east; norths += it.north }
    you?.let { easts += it.east; norths += it.north }
    fit?.let { result ->
        // Include the whole uncertainty disc so it never gets cropped into
        // looking smaller than it is.
        val radius = result.uncertaintyRadiusM
        easts += result.local.east - radius
        easts += result.local.east + radius
        norths += result.local.north - radius
        norths += result.local.north + radius

        result.mirrorCandidate?.let { mirror ->
            easts += mirror.east
            norths += mirror.north
        }
    }

    if (easts.isEmpty()) return WorldBounds(0.0, 0.0, 50.0, 50.0)

    val minEast = easts.min()
    val maxEast = easts.max()
    val minNorth = norths.min()
    val maxNorth = norths.max()

    return WorldBounds(
        centreEast = (minEast + maxEast) / 2,
        centreNorth = (minNorth + maxNorth) / 2,
        width = max(20.0, (maxEast - minEast) * 1.15),
        height = max(20.0, (maxNorth - minNorth) * 1.15),
    )
}

private fun framePointingAt(samples: List<SurveySample>, location: Location?): LocalFrame? = when {
    samples.isNotEmpty() -> LocalFrame(
        samples.sumOf { it.latitude } / samples.size,
        samples.sumOf { it.longitude } / samples.size,
    )

    location != null -> LocalFrame(location.latitude, location.longitude)
    else -> null
}

/** Rounds a scale bar to something a human reads at a glance. */
private fun niceScaleLength(target: Double): Double {
    val candidates = listOf(1.0, 2.0, 5.0, 10.0, 20.0, 25.0, 50.0, 100.0, 200.0, 500.0)
    return candidates.lastOrNull { it <= max(1.0, target) } ?: 1.0
}
