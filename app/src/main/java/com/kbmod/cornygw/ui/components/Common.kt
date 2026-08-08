package com.kbmod.cornygw.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kbmod.cornygw.ui.RssiPoint
import com.kbmod.cornygw.ui.theme.SignalColors

/** Compact five-bar signal indicator. */
@Composable
fun SignalBars(rssi: Int, modifier: Modifier = Modifier) {
    val fraction = SignalColors.fractionOf(rssi)
    val litBars = (fraction * 5f).toInt().coerceIn(0, 5)
    val color = SignalColors.forRssi(rssi)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(5) { index ->
            val lit = index < litBars
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height((5 + index * 3).dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(
                        if (lit) color
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                    ),
            )
        }
    }
}

/** Label/value pair used throughout the detail panels. */
@Composable
fun StatRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            color = valueColor,
        )
    }
}

@Composable
fun InfoCard(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * RSSI history trace.
 *
 * The vertical scale is fixed to the plotted window rather than autoscaled to
 * the data, with a floor on the span. Autoscaling would magnify 1 dB of noise
 * into a dramatic mountain range whenever the signal was actually steady.
 */
@Composable
fun Sparkline(
    points: List<RssiPoint>,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val lineColor = MaterialTheme.colorScheme.primary

    Canvas(modifier) {
        val width = size.width
        val height = size.height

        repeat(4) { index ->
            val y = height * (index + 1) / 5f
            drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 1f)
        }

        if (points.size < 2) return@Canvas

        val values = points.map { it.smoothed }
        val rawMin = values.min()
        val rawMax = values.max()
        val centre = (rawMin + rawMax) / 2.0
        val span = maxOf(rawMax - rawMin, MIN_SPAN_DB)
        val minimum = centre - span / 2
        val maximum = centre + span / 2

        fun xFor(index: Int) = width * index / (points.size - 1).toFloat()
        fun yFor(value: Double) =
            (height * (1.0 - (value - minimum) / (maximum - minimum))).toFloat()

        val path = Path()
        val fill = Path()
        points.forEachIndexed { index, point ->
            val x = xFor(index)
            val y = yFor(point.smoothed)
            if (index == 0) {
                path.moveTo(x, y)
                fill.moveTo(x, height)
                fill.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(xFor(points.size - 1), height)
        fill.close()

        drawPath(fill, lineColor.copy(alpha = 0.15f))
        drawPath(path, lineColor, style = Stroke(width = 3f))

        val last = points.last()
        drawCircle(
            color = SignalColors.forRssi(last.smoothed),
            radius = 5f,
            center = Offset(xFor(points.size - 1), yFor(last.smoothed)),
        )
    }
}

/** Direction indicator: a triangle rotated to [rotationDegrees]. */
@Composable
fun DirectionArrow(
    rotationDegrees: Float,
    color: Color,
    modifier: Modifier = Modifier.size(96.dp),
) {
    Canvas(modifier) {
        val radius = size.minDimension / 2f
        val centre = Offset(size.width / 2f, size.height / 2f)

        drawCircle(
            color = color.copy(alpha = 0.12f),
            radius = radius,
            center = centre,
        )

        rotate(degrees = rotationDegrees, pivot = centre) {
            val path = Path().apply {
                moveTo(centre.x, centre.y - radius * 0.78f)
                lineTo(centre.x + radius * 0.44f, centre.y + radius * 0.62f)
                lineTo(centre.x, centre.y + radius * 0.3f)
                lineTo(centre.x - radius * 0.44f, centre.y + radius * 0.62f)
                close()
            }
            drawPath(path, color)
        }
    }
}

private const val MIN_SPAN_DB = 12.0
