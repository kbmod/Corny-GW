package com.kbmod.cornygw.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS fixes as a Flow, via the platform [LocationManager].
 *
 * Deliberately no Play Services dependency: the fused provider is nicer but
 * this app has to work on any Android device, and for a walking survey raw GNSS
 * at 1 Hz is exactly what we want anyway — fused smoothing would quietly
 * correlate our position samples with each other.
 */
class LocationStream(context: Context) {

    private val appContext = context.applicationContext
    private val locationManager =
        appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    val isLocationEnabled: Boolean
        get() = runCatching {
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }.getOrDefault(false)

    @SuppressLint("MissingPermission")
    fun fixes(minIntervalMs: Long = 1_000L): Flow<Location> = callbackFlow {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location)
            }

            // Required on API < 30; the default implementations were only added
            // to the interface later.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Retained for API < 29 compatibility")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { locationManager.isProviderEnabled(it) }.getOrDefault(false) }

        if (providers.isEmpty()) {
            close()
            return@callbackFlow
        }

        val registered = mutableListOf<String>()
        for (provider in providers) {
            val ok = runCatching {
                locationManager.requestLocationUpdates(
                    provider,
                    minIntervalMs,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
            }.isSuccess
            if (ok) registered += provider
        }

        if (registered.isEmpty()) {
            close()
            return@callbackFlow
        }

        // Seed with the last known fix so the map has something to draw before
        // the first satellite update lands.
        registered.asSequence()
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { trySend(it) }

        awaitClose {
            runCatching { locationManager.removeUpdates(listener) }
        }
    }
}
