package com.evsuite.chargepilot

enum class PrimaryPage {
    ENERGY,
    ARRIVAL,
    TRIPS,
    DIAGNOSTICS,
    SETTINGS;

    fun neighbour(offset: Int): PrimaryPage? = entries.getOrNull(ordinal + offset)
}
