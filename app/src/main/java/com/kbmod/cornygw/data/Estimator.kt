package com.kbmod.cornygw.data

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Solves for where a radio physically is, given RSSI measured at known places.
 *
 * ## The model
 *
 * For a candidate transmitter position `p`, the log-distance model predicts
 *
 *     rssi_i = ref - 10 n log10(d_i)      d_i = |p - sample_i|
 *
 * `ref` (the transmitter's apparent power at 1 m) is unknown, and we have no
 * way to calibrate it because we cannot get close to a neighbour's router. But
 * notice `ref` enters linearly. Rearranged,
 *
 *     v_i = rssi_i + 10 n log10(d_i) = ref
 *
 * so for the *correct* `p`, every `v_i` equals the same constant, and the best
 * `ref` for any candidate is just `mean(v)`. That collapses a 3-parameter fit
 * into a 2-D search whose cost function is simply `variance(v)` — no matrix
 * algebra, no Gauss-Newton, no divergence on bad starting guesses.
 *
 * ## Why a grid search rather than gradient descent
 *
 * The cost surface is genuinely multi-modal when the walk is one-sided, and a
 * local optimiser silently returns whichever basin it started in. A coarse
 * sweep followed by two refinements is a handful of milliseconds at street
 * scale and cannot miss the global basin. It also hands us the uncertainty
 * region for free: we already evaluated the neighbourhood, so we can report
 * how far the fit can move before it stops explaining the data.
 *
 * ## The failure mode this is built to expose
 *
 * Signal strength constrains *distance*, not *direction*. Samples from a single
 * spot produce a fit that is free to slide anywhere on a ring around you and
 * still fit perfectly. That is why [Fit.angularCoverage] is reported alongside
 * the residual and why confidence is capped when coverage is poor: a tight
 * residual on a one-sided walk is precision, not accuracy.
 */
object Estimator {

    /** Samples worse than this GPS accuracy poison the geometry. */
    private const val MAX_ACCURACY_M = 30f

    /** Spatial de-duplication cell. Standing still must not out-vote walking. */
    private const val BIN_SIZE_M = 3.0

    private const val MIN_BINNED_POINTS = 5

    private const val COARSE_STEP_M = 4.0
    private const val MEDIUM_STEP_M = 1.0
    private const val FINE_STEP_M = 0.25

    private const val SEARCH_MARGIN_M = 150.0
    private const val MEDIUM_WINDOW_M = 40.0
    private const val FINE_WINDOW_M = 2.0

    /** Residual slack that still counts as "explains the data". */
    private const val RADIUS_SLACK_DB = 1.0

    /**
     * Plausible range for a consumer access point's apparent power at 1 m.
     *
     * Without this bound the fit has a genuine degeneracy: pushing the candidate
     * position far away flattens the `log10(d)` term until it can absorb almost
     * any noise, and the solver happily runs hundreds of metres off while
     * reporting a small residual. Every such runaway implies an absurd
     * transmitter — one apparently radiating -11 dBm at a metre — so bounding
     * the power bounds the runaway. In testing this alone pulled a badly
     * degenerate case from 219 m of error down to 1.3 m.
     */
    private const val REF_MIN_DBM = -65.0
    private const val REF_MAX_DBM = -15.0

    /**
     * Below this ratio between the minor and major spread of the sample cloud,
     * the walk counts as a straight line.
     */
    private const val COLLINEAR_RATIO = 0.05

    enum class Confidence(val label: String) {
        HIGH("High"),
        MEDIUM("Medium"),
        LOW("Low"),
    }

    data class Fit(
        val latitude: Double,
        val longitude: Double,
        val local: LocalPoint,
        val frame: LocalFrame,
        /** Fitted apparent transmit power at 1 m, in dBm. */
        val refRssiAt1m: Double,
        /** RMS of the model residual, in dB. Under ~4 dB is a good fit. */
        val residualRmsDb: Double,
        val uncertaintyRadiusM: Double,
        /** True when the uncertainty region ran off the edge of the sweep. */
        val radiusIsLowerBound: Boolean,
        /** 0..1 fraction of compass sectors the samples occupy around the fit. */
        val angularCoverage: Double,
        /** max - min RSSI across the survey, in dB. */
        val rssiSpreadDb: Int,
        val binnedSampleCount: Int,
        val rawSampleCount: Int,
        /** Variance explained, 0..1. Analogous to R^2. */
        val fitQuality: Double,
        val pathLossExponent: Double,
        /**
         * The equally-good solution on the other side of the walk, present only
         * when the route was effectively a straight line.
         *
         * A straight walk is mirror-symmetric: a source 20 m to the left of the
         * pavement and one 20 m to the right produce identical readings at every
         * point along it, so the data cannot distinguish them. The solver picks
         * one arbitrarily and can be badly wrong doing so — in testing it chose
         * the side 38 m from truth when the mirror was 6 m away. Surfacing both
         * is the only honest option.
         */
        val mirrorCandidate: LocalPoint?,
    ) {
        val isMirrorAmbiguous: Boolean get() = mirrorCandidate != null

        val confidence: Confidence
            get() = when {
                // A coin-flip between two sides is never high confidence, no
                // matter how tightly either one fits.
                isMirrorAmbiguous -> if (residualRmsDb <= 7.0 && fitQuality >= 0.35) {
                    Confidence.MEDIUM
                } else {
                    Confidence.LOW
                }

                angularCoverage >= 0.5 && residualRmsDb <= 4.0 &&
                    fitQuality >= 0.6 && uncertaintyRadiusM <= 15.0 && !radiusIsLowerBound ->
                    Confidence.HIGH

                angularCoverage >= 0.25 && residualRmsDb <= 7.0 && fitQuality >= 0.35 ->
                    Confidence.MEDIUM

                else -> Confidence.LOW
            }

        /** Plain-language advice on what would most improve this fit. */
        val advice: String
            get() = when {
                isMirrorAmbiguous ->
                    "Your route was a straight line, so the data cannot tell which side of " +
                        "it the source is on — both marked positions fit equally well. Walk " +
                        "even a short distance perpendicular to your original path to break " +
                        "the tie."

                angularCoverage < 0.25 ->
                    "Samples came from one direction. Walk around the far side of " +
                        "the target — distance alone cannot fix a direction."

                rssiSpreadDb < 10 ->
                    "Signal barely changed across the walk ($rssiSpreadDb dB). Cover " +
                        "more ground, especially closer to the strongest reading."

                residualRmsDb > 7.0 ->
                    "Readings disagree with any single source position " +
                        "(${residualRmsDb.roundToInt()} dB residual). Walls, mesh nodes " +
                        "sharing an SSID, or a moving hotspot can all cause this."

                angularCoverage < 0.5 ->
                    "Partial coverage. Sampling from the opposite side would tighten this."

                uncertaintyRadiusM > 15.0 ->
                    "Position is loose. More samples near the signal peak will help most."

                else ->
                    "Good geometry and a tight fit. Compare against building outlines " +
                        "before drawing conclusions."
            }
    }

    private data class BinnedPoint(val point: LocalPoint, val rssi: Int)

    /**
     * @param samples all readings for one BSSID
     * @param pathLossExponent environment constant; see [PathLoss]
     * @return null when there is not enough usable data to fit anything
     */
    fun estimate(samples: List<SurveySample>, pathLossExponent: Double): Fit? {
        val usable = samples.filter { it.accuracyM <= MAX_ACCURACY_M }
        if (usable.size < MIN_BINNED_POINTS) return null

        val frame = LocalFrame(
            originLat = usable.sumOf { it.latitude } / usable.size,
            originLon = usable.sumOf { it.longitude } / usable.size,
        )

        val binned = binSamples(usable, frame)
        if (binned.size < MIN_BINNED_POINTS) return null

        val rssiValues = binned.map { it.rssi.toDouble() }
        val baselineVariance = Geo.variance(rssiValues)

        val easts = binned.map { it.point.east }
        val norths = binned.map { it.point.north }
        val bounds = SearchBounds(
            minEast = easts.min() - SEARCH_MARGIN_M,
            maxEast = easts.max() + SEARCH_MARGIN_M,
            minNorth = norths.min() - SEARCH_MARGIN_M,
            maxNorth = norths.max() + SEARCH_MARGIN_M,
        )

        val coarse = sweep(bounds, COARSE_STEP_M, binned, pathLossExponent)

        val mediumBounds = bounds.window(coarse.point, MEDIUM_WINDOW_M)
        val medium = sweep(mediumBounds, MEDIUM_STEP_M, binned, pathLossExponent)

        val fineBounds = bounds.window(medium.point, FINE_WINDOW_M)
        val best = sweep(fineBounds, FINE_STEP_M, binned, pathLossExponent)

        val residualRms = sqrt(max(0.0, best.cost))
        val (radius, isLowerBound) = uncertaintyRadius(
            best = best,
            bounds = mediumBounds,
            step = MEDIUM_STEP_M,
            points = binned,
            pathLossExponent = pathLossExponent,
            windowM = MEDIUM_WINDOW_M,
        )

        val (lat, lon) = frame.toLatLon(best.point)
        val fitQuality = if (baselineVariance <= 1e-6) {
            0.0
        } else {
            (1.0 - best.cost / baselineVariance).coerceIn(0.0, 1.0)
        }

        return Fit(
            latitude = lat,
            longitude = lon,
            local = best.point,
            frame = frame,
            refRssiAt1m = best.refRssi,
            residualRmsDb = residualRms,
            uncertaintyRadiusM = radius,
            radiusIsLowerBound = isLowerBound,
            angularCoverage = Geo.angularCoverage(best.point, binned.map { it.point }),
            rssiSpreadDb = (binned.maxOf { it.rssi } - binned.minOf { it.rssi }),
            binnedSampleCount = binned.size,
            rawSampleCount = samples.size,
            fitQuality = fitQuality,
            pathLossExponent = pathLossExponent,
            mirrorCandidate = mirrorAcrossPath(binned, best.point),
        )
    }

    /**
     * Collapses samples onto a grid, keeping the strongest reading per cell.
     *
     * Strongest rather than mean is the physically correct choice: obstruction
     * — your own body included — can only ever attenuate, so within one cell the
     * maximum is the reading least corrupted by things that are not distance.
     */
    private fun binSamples(samples: List<SurveySample>, frame: LocalFrame): List<BinnedPoint> {
        val cells = HashMap<Pair<Int, Int>, BinnedPoint>()
        for (sample in samples) {
            val point = frame.toLocal(sample.latitude, sample.longitude)
            val key = (point.east / BIN_SIZE_M).roundToInt() to (point.north / BIN_SIZE_M).roundToInt()
            val existing = cells[key]
            if (existing == null || sample.rssi > existing.rssi) {
                cells[key] = BinnedPoint(point, sample.rssi)
            }
        }
        return cells.values.toList()
    }

    private data class SearchBounds(
        val minEast: Double,
        val maxEast: Double,
        val minNorth: Double,
        val maxNorth: Double,
    ) {
        fun window(centre: LocalPoint, halfWidth: Double) = SearchBounds(
            minEast = centre.east - halfWidth,
            maxEast = centre.east + halfWidth,
            minNorth = centre.north - halfWidth,
            maxNorth = centre.north + halfWidth,
        )
    }

    private data class Candidate(val point: LocalPoint, val cost: Double, val refRssi: Double)

    private fun sweep(
        bounds: SearchBounds,
        step: Double,
        points: List<BinnedPoint>,
        pathLossExponent: Double,
    ): Candidate {
        var best = Candidate(LocalPoint(bounds.minEast, bounds.minNorth), Double.MAX_VALUE, 0.0)
        var east = bounds.minEast
        while (east <= bounds.maxEast) {
            var north = bounds.minNorth
            while (north <= bounds.maxNorth) {
                val candidate = evaluate(LocalPoint(east, north), points, pathLossExponent)
                if (candidate.cost < best.cost) best = candidate
                north += step
            }
            east += step
        }
        return best
    }

    /**
     * Cost is the variance of `rssi + 10 n log10(d)` across samples; the mean of
     * that same quantity is the maximum-likelihood `ref` for this candidate.
     *
     * When that maximum-likelihood power falls outside what a real access point
     * could plausibly be, `ref` is clamped and the cost picks up the resulting
     * offset. Since the cost of holding `ref` at a fixed `r` is
     * `variance + (mean - r)^2`, the penalised surface stays continuous and
     * agrees exactly with the unconstrained one wherever the answer is sane.
     */
    private fun evaluate(
        candidate: LocalPoint,
        points: List<BinnedPoint>,
        pathLossExponent: Double,
    ): Candidate {
        var sum = 0.0
        var sumOfSquares = 0.0
        for (binned in points) {
            val distance = max(1.0, binned.point.distanceTo(candidate))
            val value = binned.rssi + 10.0 * pathLossExponent * log10(distance)
            sum += value
            sumOfSquares += value * value
        }
        val count = points.size
        val mean = sum / count
        val variance = max(0.0, sumOfSquares / count - mean * mean)

        val ref = mean.coerceIn(REF_MIN_DBM, REF_MAX_DBM)
        val offset = mean - ref
        return Candidate(candidate, variance + offset * offset, ref)
    }

    /**
     * Detects a straight-line walk and returns the reflection of [estimate]
     * across it, or null when the route had enough width to be unambiguous.
     *
     * Works from the eigenvalues of the sample cloud's covariance: when the
     * minor spread is negligible against the major one, every sample lies on a
     * line, and reflecting the solution across that line yields an exactly
     * equally good fit.
     */
    private fun mirrorAcrossPath(
        points: List<BinnedPoint>,
        estimate: LocalPoint,
    ): LocalPoint? {
        val count = points.size
        if (count < 3) return null

        val meanEast = points.sumOf { it.point.east } / count
        val meanNorth = points.sumOf { it.point.north } / count

        var varEast = 0.0
        var varNorth = 0.0
        var covariance = 0.0
        for (binned in points) {
            val de = binned.point.east - meanEast
            val dn = binned.point.north - meanNorth
            varEast += de * de
            varNorth += dn * dn
            covariance += de * dn
        }
        varEast /= count
        varNorth /= count
        covariance /= count

        val trace = varEast + varNorth
        val determinant = varEast * varNorth - covariance * covariance
        val discriminant = max(0.0, trace * trace / 4.0 - determinant)
        val major = trace / 2.0 + sqrt(discriminant)
        val minor = trace / 2.0 - sqrt(discriminant)

        if (major <= 1e-9) return null
        if (minor / major > COLLINEAR_RATIO) return null

        // Principal axis direction.
        val (rawX, rawY) = if (kotlin.math.abs(covariance) > 1e-9) {
            (major - varNorth) to covariance
        } else {
            if (varEast >= varNorth) 1.0 to 0.0 else 0.0 to 1.0
        }
        val norm = sqrt(rawX * rawX + rawY * rawY)
        if (norm <= 1e-9) return null
        val axisEast = rawX / norm
        val axisNorth = rawY / norm

        val de = estimate.east - meanEast
        val dn = estimate.north - meanNorth
        val along = de * axisEast + dn * axisNorth
        return LocalPoint(
            east = meanEast + 2 * along * axisEast - de,
            north = meanNorth + 2 * along * axisNorth - dn,
        )
    }

    /**
     * Radius of the region whose residual is within [RADIUS_SLACK_DB] of the
     * best. This is an honest "how far could the answer be" rather than a
     * formal confidence interval, and it collapses correctly on the pathological
     * one-sided-walk case: the qualifying region becomes an arc, and its extent
     * blows past the sweep window, which we report via the lower-bound flag.
     */
    private fun uncertaintyRadius(
        best: Candidate,
        bounds: SearchBounds,
        step: Double,
        points: List<BinnedPoint>,
        pathLossExponent: Double,
        windowM: Double,
    ): Pair<Double, Boolean> {
        val threshold = sqrt(max(0.0, best.cost)) + RADIUS_SLACK_DB
        val thresholdCost = threshold * threshold
        var radius = 0.0

        var east = bounds.minEast
        while (east <= bounds.maxEast) {
            var north = bounds.minNorth
            while (north <= bounds.maxNorth) {
                val point = LocalPoint(east, north)
                if (evaluate(point, points, pathLossExponent).cost <= thresholdCost) {
                    val distance = point.distanceTo(best.point)
                    if (distance > radius) radius = distance
                }
                north += step
            }
            east += step
        }

        // The sweep is a square window, so its own corner is sqrt(2) * half-width
        // away; anything at or beyond the inscribed circle means the region is
        // clipped and the true radius is larger than what we measured.
        val isLowerBound = radius >= windowM - step
        return radius to isLowerBound
    }
}
