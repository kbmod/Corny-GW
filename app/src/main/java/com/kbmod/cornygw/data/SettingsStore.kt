package com.kbmod.cornygw.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class HuntSettings(
    val pathLossExponent: Double = PathLoss.DEFAULT_PATH_LOSS_EXPONENT,
    val refRssiAt1m: Double = PathLoss.DEFAULT_REF_RSSI_AT_1M,
    val scanIntervalMs: Long = WifiScanner.DEFAULT_INTERVAL_MS,
    val smoothing: Double = 0.35,
)

class SettingsStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences("corny_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<HuntSettings> = _settings.asStateFlow()

    private fun read() = HuntSettings(
        pathLossExponent = prefs.getFloat(KEY_EXPONENT, PathLoss.DEFAULT_PATH_LOSS_EXPONENT.toFloat())
            .toDouble(),
        refRssiAt1m = prefs.getFloat(KEY_REF_RSSI, PathLoss.DEFAULT_REF_RSSI_AT_1M.toFloat())
            .toDouble(),
        scanIntervalMs = prefs.getLong(KEY_INTERVAL, WifiScanner.DEFAULT_INTERVAL_MS),
        smoothing = prefs.getFloat(KEY_SMOOTHING, 0.35f).toDouble(),
    )

    fun update(transform: (HuntSettings) -> HuntSettings) {
        val updated = transform(_settings.value)
        prefs.edit()
            .putFloat(KEY_EXPONENT, updated.pathLossExponent.toFloat())
            .putFloat(KEY_REF_RSSI, updated.refRssiAt1m.toFloat())
            .putLong(KEY_INTERVAL, updated.scanIntervalMs)
            .putFloat(KEY_SMOOTHING, updated.smoothing.toFloat())
            .apply()
        _settings.value = updated
    }

    private companion object {
        const val KEY_EXPONENT = "path_loss_exponent"
        const val KEY_REF_RSSI = "ref_rssi_1m"
        const val KEY_INTERVAL = "scan_interval_ms"
        const val KEY_SMOOTHING = "smoothing"
    }
}
