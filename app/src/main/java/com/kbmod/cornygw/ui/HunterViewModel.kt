package com.kbmod.cornygw.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kbmod.cornygw.data.ApSighting
import com.kbmod.cornygw.data.Estimator
import com.kbmod.cornygw.data.HuntSettings
import com.kbmod.cornygw.data.NetworkGroup
import com.kbmod.cornygw.data.RssiSmoother
import com.kbmod.cornygw.data.SavedSurvey
import com.kbmod.cornygw.data.ScanBatch
import com.kbmod.cornygw.data.SurveySample
import com.kbmod.cornygw.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class RssiPoint(val atMs: Long, val rssi: Int, val smoothed: Double)

enum class Trend(val label: String) {
    WARMER("Getting closer"),
    COLDER("Getting further"),
    STEADY("Holding steady"),
}

data class ScanUiState(
    val batch: ScanBatch = ScanBatch.EMPTY,
    val groups: List<NetworkGroup> = emptyList(),
    val query: String = "",
    val wifiEnabled: Boolean = true,
    val locationEnabled: Boolean = true,
    val isScanning: Boolean = false,
    val staleScanCount: Int = 0,
)

data class HuntUiState(
    val targetBssid: String? = null,
    val targetSsid: String = "",
    val current: ApSighting? = null,
    val smoothedRssi: Double? = null,
    val history: List<RssiPoint> = emptyList(),
    val peakRssi: Int? = null,
    val peakAtMs: Long? = null,
    val trend: Trend = Trend.STEADY,
    val trendSlopeDbPerSample: Double = 0.0,
    val lastSeenMs: Long? = null,
)

data class SurveyUiState(
    val isRecording: Boolean = false,
    val samples: List<SurveySample> = emptyList(),
    val fit: Estimator.Fit? = null,
    val isComputing: Boolean = false,
    val location: Location? = null,
    val headingDegrees: Double? = null,
    val message: String? = null,
    val savedSurveys: List<SavedSurvey> = emptyList(),
)

/**
 * Single activity-scoped view model.
 *
 * All three screens read the same scan stream, the same selected target and the
 * same survey buffer, so splitting them into separate view models would only
 * add a coordination problem — and a second broadcast receiver.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HunterViewModel(application: Application) : AndroidViewModel(application) {

    private val graph = application.graph

    private val _scanState = MutableStateFlow(ScanUiState())
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    private val _huntState = MutableStateFlow(HuntUiState())
    val huntState: StateFlow<HuntUiState> = _huntState.asStateFlow()

    private val _surveyState = MutableStateFlow(SurveyUiState())
    val surveyState: StateFlow<SurveyUiState> = _surveyState.asStateFlow()

    val settings: StateFlow<HuntSettings> = graph.settingsStore.settings

    private var smoother = RssiSmoother(graph.settingsStore.settings.value.smoothing)
    private val history = ArrayDeque<RssiPoint>()

    private var scanJob: Job? = null
    private var locationJob: Job? = null
    private var headingJob: Job? = null
    private var fitJob: Job? = null

    private var lastFitSampleCount = 0
    private var lastBatchAtMs = 0L

    init {
        refreshSavedSurveys()
    }

    // region lifecycle

    fun startStreams() {
        if (scanJob?.isActive != true) {
            scanJob = viewModelScope.launch {
                graph.settingsStore.settings
                    .map { it.scanIntervalMs }
                    .distinctUntilChanged()
                    .flatMapLatest { interval -> graph.wifiScanner.batches(interval) }
                    .collect { onBatch(it) }
            }
        }
        if (locationJob?.isActive != true) {
            locationJob = viewModelScope.launch {
                graph.locationStream.fixes().collect { fix ->
                    _surveyState.value = _surveyState.value.copy(location = fix)
                }
            }
        }
        if (headingJob?.isActive != true && graph.headingStream.isAvailable) {
            headingJob = viewModelScope.launch {
                graph.headingStream.headings { _surveyState.value.location }
                    .collect { heading ->
                        _surveyState.value = _surveyState.value.copy(headingDegrees = heading)
                    }
            }
        }
        _scanState.value = _scanState.value.copy(
            isScanning = true,
            wifiEnabled = graph.wifiScanner.isWifiEnabled,
            locationEnabled = graph.locationStream.isLocationEnabled,
        )
    }

    fun stopStreams() {
        scanJob?.cancel()
        locationJob?.cancel()
        headingJob?.cancel()
        scanJob = null
        locationJob = null
        headingJob = null
        _scanState.value = _scanState.value.copy(isScanning = false)
    }

    fun requestScanNow() {
        viewModelScope.launch { graph.wifiScanner.requestScan() }
    }

    // endregion

    // region scanning

    private fun onBatch(batch: ScanBatch) {
        if (batch.atMs == lastBatchAtMs && batch.sightings.isEmpty()) return
        lastBatchAtMs = batch.atMs

        val groups = batch.sightings
            .groupBy { it.ssid }
            .map { (ssid, radios) -> NetworkGroup(ssid, radios.sortedByDescending { it.rssi }) }
            .sortedByDescending { it.bestRssi }

        _scanState.value = _scanState.value.copy(
            batch = batch,
            groups = groups,
            wifiEnabled = graph.wifiScanner.isWifiEnabled,
            locationEnabled = graph.locationStream.isLocationEnabled,
            staleScanCount = if (batch.isFresh) 0 else _scanState.value.staleScanCount + 1,
        )

        updateHunt(batch)

        // Only fresh sweeps become survey samples. A cached batch repeats the
        // previous RSSI, and pairing an old reading with a new GPS position
        // would inject a fabricated measurement straight into the fit.
        if (batch.isFresh) recordSurveySample(batch)
    }

    private fun updateHunt(batch: ScanBatch) {
        val bssid = _huntState.value.targetBssid ?: return
        val sighting = batch.sightings.firstOrNull { it.bssid == bssid }

        if (sighting == null) {
            _huntState.value = _huntState.value.copy(current = null)
            return
        }

        // Cached batches must not feed the smoother either; re-feeding the same
        // value repeatedly would drag the average toward it and invent a trend.
        if (!batch.isFresh && history.isNotEmpty()) {
            _huntState.value = _huntState.value.copy(current = sighting)
            return
        }

        val smoothed = smoother.update(sighting.rssi)
        history.addLast(RssiPoint(sighting.seenAtMs, sighting.rssi, smoothed))
        while (history.size > MAX_HISTORY) history.removeFirst()

        val previousPeak = _huntState.value.peakRssi
        val isNewPeak = previousPeak == null || sighting.rssi > previousPeak

        _huntState.value = _huntState.value.copy(
            current = sighting,
            targetSsid = sighting.displaySsid,
            smoothedRssi = smoothed,
            history = history.toList(),
            peakRssi = if (isNewPeak) sighting.rssi else previousPeak,
            peakAtMs = if (isNewPeak) sighting.seenAtMs else _huntState.value.peakAtMs,
            lastSeenMs = sighting.seenAtMs,
            trendSlopeDbPerSample = trendSlope(),
            trend = classifyTrend(trendSlope()),
        )
    }

    /** Least-squares slope of the smoothed trace over the recent window, dB/sample. */
    private fun trendSlope(): Double {
        val window = history.takeLast(TREND_WINDOW)
        if (window.size < 3) return 0.0
        val n = window.size
        val meanX = (n - 1) / 2.0
        val meanY = window.sumOf { it.smoothed } / n
        var numerator = 0.0
        var denominator = 0.0
        window.forEachIndexed { index, point ->
            val dx = index - meanX
            numerator += dx * (point.smoothed - meanY)
            denominator += dx * dx
        }
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    private fun classifyTrend(slope: Double): Trend = when {
        slope > TREND_THRESHOLD_DB -> Trend.WARMER
        slope < -TREND_THRESHOLD_DB -> Trend.COLDER
        else -> Trend.STEADY
    }

    fun selectTarget(sighting: ApSighting) {
        history.clear()
        smoother = RssiSmoother(graph.settingsStore.settings.value.smoothing)
        _huntState.value = HuntUiState(
            targetBssid = sighting.bssid,
            targetSsid = sighting.displaySsid,
            current = sighting,
        )
        _surveyState.value = _surveyState.value.copy(
            samples = emptyList(),
            fit = null,
            isRecording = false,
            message = null,
        )
        lastFitSampleCount = 0
    }

    fun clearTarget() {
        history.clear()
        smoother.reset()
        _huntState.value = HuntUiState()
        _surveyState.value = _surveyState.value.copy(
            samples = emptyList(),
            fit = null,
            isRecording = false,
        )
    }

    fun setQuery(query: String) {
        _scanState.value = _scanState.value.copy(query = query)
    }

    // endregion

    // region survey

    fun toggleRecording() {
        val state = _surveyState.value
        if (state.isRecording) {
            _surveyState.value = state.copy(isRecording = false)
            recomputeFit(force = true)
            return
        }

        if (_huntState.value.targetBssid == null) {
            _surveyState.value = state.copy(message = "Pick a target network first.")
            return
        }
        if (!graph.locationStream.isLocationEnabled) {
            _surveyState.value = state.copy(
                message = "Location services are off. The survey needs GPS to know where each reading was taken.",
            )
            return
        }
        _surveyState.value = state.copy(isRecording = true, message = null)
        requestScanNow()
    }

    private fun recordSurveySample(batch: ScanBatch) {
        val state = _surveyState.value
        if (!state.isRecording) return

        val bssid = _huntState.value.targetBssid ?: return
        val sighting = batch.sightings.firstOrNull { it.bssid == bssid } ?: return
        val fix = state.location ?: return

        // A fix older than the scan describes a place we may have already left.
        if (batch.atMs - fix.time > MAX_FIX_AGE_MS) return
        if (fix.accuracy > MAX_FIX_ACCURACY_M) return

        val sample = SurveySample(
            atMs = batch.atMs,
            bssid = bssid,
            rssi = sighting.rssi,
            latitude = fix.latitude,
            longitude = fix.longitude,
            accuracyM = fix.accuracy,
        )
        _surveyState.value = state.copy(samples = state.samples + sample)
        recomputeFit(force = false)
    }

    private fun recomputeFit(force: Boolean) {
        val samples = _surveyState.value.samples
        if (samples.size < MIN_SAMPLES_FOR_FIT) return
        if (!force && samples.size - lastFitSampleCount < FIT_SAMPLE_STRIDE) return
        if (fitJob?.isActive == true) return

        lastFitSampleCount = samples.size
        val exponent = graph.settingsStore.settings.value.pathLossExponent

        _surveyState.value = _surveyState.value.copy(isComputing = true)
        fitJob = viewModelScope.launch {
            val fit = withContext(Dispatchers.Default) {
                Estimator.estimate(samples, exponent)
            }
            _surveyState.value = _surveyState.value.copy(fit = fit, isComputing = false)
        }
    }

    fun recomputeNow() = recomputeFit(force = true)

    fun clearSurvey() {
        lastFitSampleCount = 0
        _surveyState.value = _surveyState.value.copy(
            samples = emptyList(),
            fit = null,
            isRecording = false,
            message = null,
        )
    }

    fun saveSurvey(onSaved: (File) -> Unit = {}) {
        val state = _surveyState.value
        val bssid = _huntState.value.targetBssid
        if (state.samples.isEmpty() || bssid == null) {
            _surveyState.value = state.copy(message = "Nothing recorded yet.")
            return
        }
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                graph.surveyStore.save(_huntState.value.targetSsid, bssid, state.samples)
            }
            _surveyState.value = _surveyState.value.copy(message = "Saved ${file.name}")
            refreshSavedSurveys()
            onSaved(file)
        }
    }

    fun refreshSavedSurveys() {
        viewModelScope.launch {
            val surveys = withContext(Dispatchers.IO) { graph.surveyStore.list() }
            _surveyState.value = _surveyState.value.copy(savedSurveys = surveys)
        }
    }

    fun deleteSurvey(survey: SavedSurvey) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { graph.surveyStore.delete(survey) }
            refreshSavedSurveys()
        }
    }

    fun loadSurvey(survey: SavedSurvey) {
        viewModelScope.launch {
            val samples = withContext(Dispatchers.IO) { graph.surveyStore.load(survey.file) }
            if (samples.isEmpty()) {
                _surveyState.value = _surveyState.value.copy(message = "That file had no usable rows.")
                return@launch
            }
            _huntState.value = _huntState.value.copy(
                targetBssid = survey.bssid,
                targetSsid = survey.ssid,
            )
            _surveyState.value = _surveyState.value.copy(
                samples = samples,
                isRecording = false,
                message = "Loaded ${survey.sampleCount} samples from ${survey.file.name}",
            )
            lastFitSampleCount = 0
            recomputeFit(force = true)
        }
    }

    fun shareSurvey(survey: SavedSurvey) = graph.surveyStore.shareIntent(survey.file)

    fun dismissMessage() {
        _surveyState.value = _surveyState.value.copy(message = null)
    }

    // endregion

    fun updateSettings(transform: (HuntSettings) -> HuntSettings) {
        graph.settingsStore.update(transform)
        smoother = RssiSmoother(graph.settingsStore.settings.value.smoothing)
        recomputeFit(force = true)
    }

    private companion object {
        const val MAX_HISTORY = 120
        const val TREND_WINDOW = 6
        const val TREND_THRESHOLD_DB = 0.75
        const val MIN_SAMPLES_FOR_FIT = 6
        const val FIT_SAMPLE_STRIDE = 4
        const val MAX_FIX_AGE_MS = 6_000L
        const val MAX_FIX_ACCURACY_M = 30f
    }
}
