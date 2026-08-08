package com.kbmod.cornygw.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Ground-truth tests for the position solver.
 *
 * Each case synthesises RSSI from the same log-distance model the solver
 * assumes, at a known transmitter position, then checks that the solver
 * recovers it — and, just as importantly, that it declines to sound confident
 * about the configurations that are genuinely unsolvable.
 */
class EstimatorTest {

    private val origin = LocalFrame(51.5000000, -0.1200000)
    private val pathLoss = 3.0

    /** Builds samples along [walk], as heard from a transmitter at [apEast]/[apNorth]. */
    private fun synthesise(
        walk: List<LocalPoint>,
        apEast: Double = 0.0,
        apNorth: Double = 0.0,
        refRssi: Double = -40.0,
        noiseDb: Double = 0.0,
        exponent: Double = pathLoss,
        seed: Int = 1,
    ): List<SurveySample> {
        val random = Random(seed)
        return walk.mapIndexed { index, point ->
            val distance = maxOf(0.5, hypot(point.east - apEast, point.north - apNorth))
            val noise = if (noiseDb == 0.0) 0.0 else random.nextGaussian() * noiseDb
            val rssi = refRssi - 10 * exponent * log10(distance) + noise
            val (lat, lon) = origin.toLatLon(point)
            SurveySample(
                atMs = 1_000L * index,
                bssid = "aa:bb:cc:dd:ee:ff",
                rssi = rssi.roundToInt(),
                latitude = lat,
                longitude = lon,
                accuracyM = 5f,
            )
        }
    }

    private fun Random.nextGaussian(): Double {
        // Box-Muller; kotlin.random has no Gaussian of its own.
        val u1 = nextDouble().coerceAtLeast(1e-12)
        val u2 = nextDouble()
        return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2.0 * Math.PI * u2)
    }

    /** A loop of varying radius — a realistic "walk around the block" route. */
    private fun loopWalk(): List<LocalPoint> = (0 until 16).map { step ->
        val radius = 18 + 12 * cos(step * 0.7)
        val angle = step * Math.PI / 8
        LocalPoint(radius * cos(angle), radius * sin(angle))
    }

    /**
     * Distance between the fit and a known point, both expressed in this test's
     * frame.
     *
     * The solver anchors its own frame at the centroid of the samples, so
     * `fit.local` is not comparable to the coordinates used to synthesise the
     * data. Going through lat/lon is what makes the two commensurable.
     */
    private fun errorFrom(fit: Estimator.Fit, east: Double, north: Double): Double {
        val fitted = origin.toLocal(fit.latitude, fit.longitude)
        return hypot(fitted.east - east, fitted.north - north)
    }

    /** Same conversion for the mirror candidate, which lives in the solver frame. */
    private fun mirrorErrorFrom(
        fit: Estimator.Fit,
        east: Double,
        north: Double,
    ): Double {
        val mirror = fit.mirrorCandidate ?: return Double.MAX_VALUE
        val (lat, lon) = fit.frame.toLatLon(mirror)
        val converted = origin.toLocal(lat, lon)
        return hypot(converted.east - east, converted.north - north)
    }

    @Test
    fun `recovers position from a clean loop`() {
        val fit = Estimator.estimate(synthesise(loopWalk()), pathLoss)
        assertNotNull(fit)
        fit!!
        assertTrue("expected sub-metre error, got ${errorFrom(fit, 0.0, 0.0)}",
            errorFrom(fit, 0.0, 0.0) < 1.0)
        assertEquals(Estimator.Confidence.HIGH, fit.confidence)
        assertTrue(fit.angularCoverage > 0.9)
    }

    @Test
    fun `recovers transmit power from a clean loop`() {
        for (trueRef in listOf(-30.0, -40.0, -50.0)) {
            val fit = Estimator.estimate(synthesise(loopWalk(), refRssi = trueRef), pathLoss)
            assertNotNull(fit)
            assertEquals(trueRef, fit!!.refRssiAt1m, 1.0)
        }
    }

    @Test
    fun `survives realistic measurement noise`() {
        val fit = Estimator.estimate(
            synthesise(loopWalk(), noiseDb = 4.0, seed = 7),
            pathLoss,
        )
        assertNotNull(fit)
        assertTrue(
            "expected error under 12 m, got ${errorFrom(fit!!, 0.0, 0.0)}",
            errorFrom(fit, 0.0, 0.0) < 12.0,
        )
    }

    /**
     * Guards the power-plausibility bound. Without it the solver escapes to a
     * position hundreds of metres away where the distance term flattens out and
     * absorbs the noise, implying a transmitter far too powerful to be real.
     */
    @Test
    fun `never fits an implausible transmitter`() {
        val fit = Estimator.estimate(
            synthesise(loopWalk(), noiseDb = 5.0, seed = 3),
            pathLoss,
        )
        assertNotNull(fit)
        assertTrue(
            "fitted power ${fit!!.refRssiAt1m} dBm is not a real access point",
            fit.refRssiAt1m in -65.0..-15.0,
        )
        assertTrue(
            "solver ran away to ${errorFrom(fit, 0.0, 0.0)} m",
            errorFrom(fit, 0.0, 0.0) < 30.0,
        )
    }

    /**
     * A straight walk is mirror-symmetric about its own path: a source either
     * side of it produces identical readings. The solver must say so rather
     * than pick a side and sound sure.
     */
    @Test
    fun `flags mirror ambiguity on a straight walk`() {
        val straight = (-60..60 step 5).map { LocalPoint(it.toDouble(), 0.0) }
        val fit = Estimator.estimate(
            synthesise(straight, apEast = 0.0, apNorth = 20.0, noiseDb = 3.0, seed = 3),
            pathLoss,
        )
        assertNotNull(fit)
        fit!!
        assertTrue("straight walk should be flagged ambiguous", fit.isMirrorAmbiguous)
        assertTrue(fit.confidence != Estimator.Confidence.HIGH)

        // One of the two candidates must be close to the truth; the mirror is
        // what rescues the case where the solver picked the wrong side.
        val bestError = minOf(
            errorFrom(fit, 0.0, 20.0),
            mirrorErrorFrom(fit, 0.0, 20.0),
        )
        assertTrue("neither candidate was near the truth (best $bestError m)", bestError < 15.0)
    }

    @Test
    fun `does not flag ambiguity when the walk has width`() {
        val fit = Estimator.estimate(synthesise(loopWalk(), noiseDb = 2.0, seed = 5), pathLoss)
        assertNotNull(fit)
        assertNull(fit!!.mirrorCandidate)
    }

    @Test
    fun `refuses to fit when the survey never moved`() {
        val standingStill = (0 until 20).map { LocalPoint(30.0, 30.0) }
        val fit = Estimator.estimate(synthesise(standingStill, noiseDb = 3.0), pathLoss)
        // Every reading lands in one spatial bin, leaving nothing to solve.
        assertNull(fit)
    }

    @Test
    fun `rejects samples with unusable gps accuracy`() {
        val samples = synthesise(loopWalk()).map { it.copy(accuracyM = 80f) }
        assertNull(Estimator.estimate(samples, pathLoss))
    }

    @Test
    fun `reports low confidence for a distant one-sided walk`() {
        val oneSided = (-20..20 step 4).map { LocalPoint(it.toDouble(), -50.0) }
        val fit = Estimator.estimate(synthesise(oneSided, noiseDb = 3.0, seed = 5), pathLoss)
        assertNotNull(fit)
        assertTrue(fit!!.confidence != Estimator.Confidence.HIGH)
    }

    @Test
    fun `tolerates a wrong path loss exponent`() {
        // Data generated at 3.5, solved assuming 2.5. The fitted reference power
        // absorbs much of the mismatch, so the position should still land close.
        val fit = Estimator.estimate(
            synthesise(loopWalk(), exponent = 3.5, noiseDb = 2.0, seed = 17),
            pathLossExponent = 2.5,
        )
        assertNotNull(fit)
        assertTrue(
            "error ${errorFrom(fit!!, 0.0, 0.0)} m under model mismatch",
            errorFrom(fit, 0.0, 0.0) < 30.0,
        )
    }

    @Test
    fun `uncertainty region contains the true position`() {
        val fit = Estimator.estimate(synthesise(loopWalk(), noiseDb = 3.0, seed = 23), pathLoss)
        assertNotNull(fit)
        fit!!
        assertTrue(
            "true position ${errorFrom(fit, 0.0, 0.0)} m away but radius only " +
                "${fit.uncertaintyRadiusM} m",
            errorFrom(fit, 0.0, 0.0) <= fit.uncertaintyRadiusM + 1.0,
        )
    }
}

class GeoTest {

    @Test
    fun `local frame round trips`() {
        val frame = LocalFrame(51.5, -0.12)
        val point = LocalPoint(123.4, -56.7)
        val (lat, lon) = frame.toLatLon(point)
        val back = frame.toLocal(lat, lon)
        assertEquals(point.east, back.east, 0.01)
        assertEquals(point.north, back.north, 0.01)
    }

    @Test
    fun `bearings follow the compass`() {
        val origin = LocalPoint(0.0, 0.0)
        assertEquals(0.0, Geo.bearingDegrees(origin, LocalPoint(0.0, 10.0)), 0.001)
        assertEquals(90.0, Geo.bearingDegrees(origin, LocalPoint(10.0, 0.0)), 0.001)
        assertEquals(180.0, Geo.bearingDegrees(origin, LocalPoint(0.0, -10.0)), 0.001)
        assertEquals(270.0, Geo.bearingDegrees(origin, LocalPoint(-10.0, 0.0)), 0.001)
    }

    @Test
    fun `compass points name the bearing`() {
        assertEquals("N", Geo.compassPoint(0.0))
        assertEquals("NE", Geo.compassPoint(45.0))
        assertEquals("S", Geo.compassPoint(180.0))
        assertEquals("NW", Geo.compassPoint(315.0))
        assertEquals("N", Geo.compassPoint(359.0))
    }

    @Test
    fun `angle delta takes the short way round`() {
        assertEquals(20.0, Geo.angleDelta(350.0, 10.0), 0.001)
        assertEquals(-20.0, Geo.angleDelta(10.0, 350.0), 0.001)
        assertEquals(180.0, Geo.angleDelta(0.0, 180.0), 0.001)
    }

    @Test
    fun `angular coverage sees a full loop and a straight line differently`() {
        val centre = LocalPoint(0.0, 0.0)
        val ring = (0 until 16).map {
            LocalPoint(20 * cos(it * Math.PI / 8), 20 * sin(it * Math.PI / 8))
        }
        assertEquals(1.0, Geo.angularCoverage(centre, ring), 0.001)

        val line = (1..8).map { LocalPoint(it * 5.0, 30.0) }
        assertTrue(Geo.angularCoverage(centre, line) <= 0.375)
    }
}

class PathLossTest {

    @Test
    fun `distance and rssi are inverse`() {
        val ref = -40.0
        val exponent = 3.0
        for (distance in listOf(1.0, 5.0, 25.0, 100.0)) {
            val rssi = PathLoss.rssiAt(distance, ref, exponent)
            val back = PathLoss.distanceMeters(rssi.roundToInt(), ref, exponent)
            assertEquals(distance, back, distance * 0.25)
        }
    }

    @Test
    fun `stronger signal means closer`() {
        val near = PathLoss.distanceMeters(-40, -40.0, 3.0)
        val far = PathLoss.distanceMeters(-80, -40.0, 3.0)
        assertTrue(near < far)
    }

    @Test
    fun `range brackets the point estimate`() {
        val range = PathLoss.distanceRange(-70, -40.0, 3.0)
        val point = PathLoss.distanceMeters(-70, -40.0, 3.0)
        assertTrue(range.start < point)
        assertTrue(range.endInclusive > point)
    }
}

class BandsTest {

    @Test
    fun `maps frequencies to channels`() {
        assertEquals(1, Bands.channelOf(2412))
        assertEquals(6, Bands.channelOf(2437))
        assertEquals(11, Bands.channelOf(2462))
        assertEquals(14, Bands.channelOf(2484))
        assertEquals(36, Bands.channelOf(5180))
        assertEquals(149, Bands.channelOf(5745))
    }

    @Test
    fun `maps frequencies to bands`() {
        assertEquals(Band.GHZ_2_4, Bands.bandOf(2437))
        assertEquals(Band.GHZ_5, Bands.bandOf(5180))
        assertEquals(Band.GHZ_6, Bands.bandOf(6115))
        assertEquals(Band.UNKNOWN, Bands.bandOf(900))
    }

    @Test
    fun `higher bands are the sharp ones`() {
        assertTrue(Bands.isSharpBand(Band.GHZ_5))
        assertTrue(Bands.isSharpBand(Band.GHZ_6))
        assertTrue(!Bands.isSharpBand(Band.GHZ_2_4))
    }
}
