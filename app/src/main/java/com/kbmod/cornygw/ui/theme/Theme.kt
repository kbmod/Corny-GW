package com.kbmod.cornygw.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(
    primary = Color(0xFF2E6B4F),
    secondary = Color(0xFF4C6358),
    tertiary = Color(0xFF7A5C2E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FD3AC),
    secondary = Color(0xFFB3CCBE),
    tertiary = Color(0xFFE7C08A),
)

@Composable
fun CornyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/**
 * Signal strength as colour, on a continuous ramp rather than in buckets.
 *
 * Buckets make a 1 dB change across a boundary look like a big event and a 9 dB
 * change inside one look like nothing. When the whole task is noticing which
 * direction the number is moving, the ramp is the honest encoding.
 */
object SignalColors {
    private val Weak = Color(0xFFC62828)
    private val Middle = Color(0xFFF9A825)
    private val Strong = Color(0xFF2E7D32)

    private const val FLOOR_DBM = -95.0
    private const val CEILING_DBM = -45.0

    fun forRssi(rssi: Number): Color {
        val fraction = fractionOf(rssi)
        return if (fraction < 0.5f) {
            lerp(Weak, Middle, fraction * 2f)
        } else {
            lerp(Middle, Strong, (fraction - 0.5f) * 2f)
        }
    }

    /** 0f at the noise floor, 1f at a signal you are practically standing on. */
    fun fractionOf(rssi: Number): Float {
        val value = rssi.toDouble().coerceIn(FLOOR_DBM, CEILING_DBM)
        return ((value - FLOOR_DBM) / (CEILING_DBM - FLOOR_DBM)).toFloat()
    }
}
