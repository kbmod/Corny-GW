package com.kbmod.cornygw.data

/**
 * One radio observed in one scan.
 *
 * The unit of physics is the BSSID (a single radio), not the SSID. A neighbour
 * with a mesh kit broadcasts the same SSID from three boxes in three rooms, and
 * averaging their signal together would point you at the middle of their house
 * instead of at a radio. Everything downstream of here keys on [bssid]; the
 * SSID is only ever used for grouping in the UI.
 */
data class ApSighting(
    val bssid: String,
    val ssid: String,
    val rssi: Int,
    val frequencyMhz: Int,
    val capabilities: String,
    val seenAtMs: Long,
) {
    val isHidden: Boolean get() = ssid.isBlank()
    val displaySsid: String get() = if (isHidden) "(hidden network)" else ssid
    val band: Band get() = Bands.bandOf(frequencyMhz)
    val channel: Int get() = Bands.channelOf(frequencyMhz)

    /** Rough security summary, good enough to tell an open hotspot from WPA3. */
    val security: String
        get() = when {
            capabilities.contains("SAE") -> "WPA3"
            capabilities.contains("WPA2") || capabilities.contains("RSN") -> "WPA2"
            capabilities.contains("WPA") -> "WPA"
            capabilities.contains("WEP") -> "WEP"
            else -> "Open"
        }
}

/** All radios seen in a single scan pass. */
data class ScanBatch(
    val sightings: List<ApSighting>,
    /**
     * False when Android handed us a cached result instead of a new sweep —
     * scan throttling. The UI surfaces this so the user does not read stale
     * numbers as "the signal stopped changing".
     */
    val isFresh: Boolean,
    val atMs: Long,
) {
    companion object {
        val EMPTY = ScanBatch(emptyList(), isFresh = false, atMs = 0L)
    }
}

/** Sightings that share an SSID, newest reading per radio. */
data class NetworkGroup(
    val ssid: String,
    val radios: List<ApSighting>,
) {
    val displaySsid: String get() = radios.firstOrNull()?.displaySsid ?: ssid
    val strongest: ApSighting get() = radios.maxBy { it.rssi }
    val bestRssi: Int get() = strongest.rssi
}

/** One point on a walking survey: where you stood, and what you heard there. */
data class SurveySample(
    val atMs: Long,
    val bssid: String,
    val rssi: Int,
    val latitude: Double,
    val longitude: Double,
    /** Horizontal GPS accuracy in metres; samples with poor fixes get dropped. */
    val accuracyM: Float,
)
