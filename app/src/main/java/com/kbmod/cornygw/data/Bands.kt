package com.kbmod.cornygw.data

enum class Band(val label: String) {
    GHZ_2_4("2.4 GHz"),
    GHZ_5("5 GHz"),
    GHZ_6("6 GHz"),
    UNKNOWN("?"),
}

object Bands {

    fun bandOf(frequencyMhz: Int): Band = when (frequencyMhz) {
        in 2401..2499 -> Band.GHZ_2_4
        in 5150..5895 -> Band.GHZ_5
        in 5925..7125 -> Band.GHZ_6
        else -> Band.UNKNOWN
    }

    fun channelOf(frequencyMhz: Int): Int = when {
        frequencyMhz == 2484 -> 14
        frequencyMhz in 2412..2472 -> (frequencyMhz - 2407) / 5
        frequencyMhz in 5150..5895 -> (frequencyMhz - 5000) / 5
        frequencyMhz in 5925..7125 -> (frequencyMhz - 5950) / 5
        else -> -1
    }

    /**
     * Whether this band is worth hunting on. 5 and 6 GHz attenuate much harder
     * through walls, which sounds like a drawback but is exactly what you want:
     * the signal falls off fast enough to tell two adjacent houses apart. 2.4
     * GHz carries so well that half the street reads the same.
     */
    fun isSharpBand(band: Band): Boolean = band == Band.GHZ_5 || band == Band.GHZ_6
}
