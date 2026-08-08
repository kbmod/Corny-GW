package com.kbmod.cornygw.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Wraps [WifiManager] scanning in a Flow.
 *
 * Two things about Android Wi-Fi scanning drive this design:
 *
 * 1. `startScan()` is a *request*, not a command. Since Android 9 the platform
 *    throttles foreground apps to a handful of scans per two minutes; past the
 *    budget the call returns false and the broadcast carries the previous
 *    results with `EXTRA_RESULTS_UPDATED = false`. We surface that as
 *    [ScanBatch.isFresh] rather than pretending the radio went quiet.
 *
 * 2. Results arrive on a broadcast, not as a return value, and *other* apps'
 *    scans deliver to us too. So we listen continuously and merely nudge the
 *    system on an interval.
 */
class WifiScanner(context: Context) {

    private val appContext = context.applicationContext
    private val wifiManager =
        appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val isWifiEnabled: Boolean get() = wifiManager.isWifiEnabled

    /**
     * Emits a batch whenever results land, plus the cached set immediately on
     * collection so the UI is never blank while waiting for the first sweep.
     */
    fun batches(requestIntervalMs: Long = DEFAULT_INTERVAL_MS): Flow<ScanBatch> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val fresh = intent?.getBooleanExtra(
                    WifiManager.EXTRA_RESULTS_UPDATED,
                    true,
                ) ?: true
                trySend(readResults(fresh))
            }
        }

        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_EXPORTED,
        )

        trySend(readResults(isFresh = false))

        val pump = launch {
            while (isActive) {
                requestScan()
                delay(requestIntervalMs)
            }
        }

        awaitClose {
            pump.cancel()
            runCatching { appContext.unregisterReceiver(receiver) }
        }
    }

    /** Returns false when the platform refused (throttled or Wi-Fi is off). */
    fun requestScan(): Boolean = runCatching {
        @Suppress("DEPRECATION")
        wifiManager.startScan()
    }.getOrDefault(false)

    /**
     * Reads whatever the platform currently holds. Throws nothing: a missing
     * permission surfaces as an empty batch, which the permission gate in the
     * UI is already responsible for explaining.
     */
    @SuppressLint("MissingPermission")
    fun readResults(isFresh: Boolean): ScanBatch {
        val now = System.currentTimeMillis()
        if (!hasScanPermissions()) {
            return ScanBatch(emptyList(), isFresh = isFresh, atMs = now)
        }
        val results = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
        val sightings = results.mapNotNull { result ->
            val bssid = result.BSSID ?: return@mapNotNull null
            ApSighting(
                bssid = bssid,
                ssid = result.ssidCompat(),
                rssi = result.level,
                frequencyMhz = result.frequency,
                capabilities = result.capabilities.orEmpty(),
                seenAtMs = now,
            )
        }
        return ScanBatch(sightings = sightings, isFresh = isFresh, atMs = now)
    }

    private fun hasScanPermissions(): Boolean {
        val locationGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val nearbyWifiGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.NEARBY_WIFI_DEVICES,
            ) == PackageManager.PERMISSION_GRANTED
        return locationGranted && nearbyWifiGranted
    }

    private fun ScanResult.ssidCompat(): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // WifiSsid renders printable UTF-8 as a quoted string.
            val raw = wifiSsid?.toString().orEmpty()
            return raw.removeSurrounding("\"")
        }
        @Suppress("DEPRECATION")
        return SSID.orEmpty().removeSurrounding("\"")
    }

    companion object {
        /**
         * Four scans per two minutes is the documented foreground budget, so
         * asking more often than every 30 s just burns requests that come back
         * stale. Users who turn throttling off in developer options can drop
         * this in Settings.
         */
        const val DEFAULT_INTERVAL_MS = 30_000L
        const val FAST_INTERVAL_MS = 3_000L
    }
}
