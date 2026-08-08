package com.kbmod.cornygw.data

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Log-distance path loss: rssi(d) = refRssiAt1m - 10 * n * log10(d)
 *
 * `n` is the path loss exponent. 2.0 is free space; a suburban wall or two
 * puts you nearer 3; dense construction is 4+. It is the single knob that
 * matters most for turning dBm into metres, and it is genuinely unknowable
 * without calibration, which is why the app exposes it as a slider and treats
 * every absolute distance it prints as an estimate with a range attached.
 */
object PathLoss {

    const val DEFAULT_REF_RSSI_AT_1M = -40.0
    const val DEFAULT_PATH_LOSS_EXPONENT = 3.0

    /** Closest distance we will ever claim. Below ~0.5 m the model is nonsense. */
    private const val MIN_DISTANCE_M = 0.5

    fun distanceMeters(rssi: Int, refRssiAt1m: Double, pathLossExponent: Double): Double {
        val exponent = (refRssiAt1m - rssi) / (10.0 * pathLossExponent)
        return max(MIN_DISTANCE_M, 10.0.pow(exponent))
    }

    fun rssiAt(distanceM: Double, refRssiAt1m: Double, pathLossExponent: Double): Double =
        refRssiAt1m - 10.0 * pathLossExponent * log10(max(MIN_DISTANCE_M, distanceM))

    /**
     * A plausible distance interval rather than a single number. Multipath and
     * body shadowing swing a stationary reading by several dB, so we quote the
     * distance implied by rssi +/- [uncertaintyDb].
     */
    fun distanceRange(
        rssi: Int,
        refRssiAt1m: Double,
        pathLossExponent: Double,
        uncertaintyDb: Int = 6,
    ): ClosedFloatingPointRange<Double> {
        val near = distanceMeters(rssi + uncertaintyDb, refRssiAt1m, pathLossExponent)
        val far = distanceMeters(rssi - uncertaintyDb, refRssiAt1m, pathLossExponent)
        return near..far
    }
}

/**
 * Exponential moving average over RSSI.
 *
 * Raw scan-to-scan RSSI jitters by 5+ dB while you stand still, which makes a
 * bare number useless for "am I getting warmer". Smoothing trades latency for a
 * readable trend.
 */
class RssiSmoother(private val alpha: Double = 0.35) {
    private var value: Double? = null

    fun update(rssi: Int): Double {
        val previous = value
        val next = if (previous == null) rssi.toDouble() else alpha * rssi + (1 - alpha) * previous
        value = next
        return next
    }

    fun current(): Double? = value

    fun reset() {
        value = null
    }
}

/** Bucketed signal quality, for colouring and for plain-language labels. */
enum class SignalQuality(val label: String) {
    EXCELLENT("Excellent"),
    GOOD("Good"),
    FAIR("Fair"),
    WEAK("Weak"),
    VERY_WEAK("Very weak"),
    ;

    companion object {
        fun of(rssi: Number): SignalQuality {
            val value = rssi.toDouble()
            return when {
                value >= -50 -> EXCELLENT
                value >= -62 -> GOOD
                value >= -72 -> FAIR
                value >= -82 -> WEAK
                else -> VERY_WEAK
            }
        }
    }
}
