package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.ChargeEnergy
import kotlin.math.abs

// EVKX DC tables (evkx.net, "optimum conditions"), kW at 0, 5, … 100 % of charge.
private val DC_51_KWH = listOf(20, 76, 81, 85, 85, 86, 84, 79, 75, 72, 70, 67, 63, 51, 42, 41, 27, 24, 15, 13, 10)
private val DC_64_KWH = listOf(20, 130, 130, 132, 134, 135, 135, 135, 137, 137, 138, 123, 93, 69, 64, 60, 55, 35, 24, 9, 6)

/**
 * The MG4 (EH32) packs, told apart by the usable capacity the driver declared: the car
 * publishes neither its version nor `INFO_EV_BATTERY_CAPACITY`. EVKX gives the 77 kWh pack the
 * 64 kWh table, so the two share a curve here too.
 */
enum class Mg4Variant(val usableKwh: Double, val label: String, val defaultAcKw: Double, dc: List<Int>) {
    STANDARD_51(50.8, "51 kWh", 6.6, DC_51_KWH),
    LONG_RANGE_64(61.7, "64 kWh", 11.0, DC_64_KWH),
    EXTENDED_77(74.4, "77 kWh", 11.0, DC_64_KWH),
    ;

    /** Theoretical DC power, kW, at every [ChargeCurve.BAND_PERCENT] of charge from 0 to 100. */
    val dcKw: List<Double> = dc.map(Int::toDouble)

    companion object {
        /** The 51 kWh pack peaks at 86 kW; a DC step well above it was drawn by a bigger one. */
        private const val STANDARD_PEAK_KW = 95.0

        fun of(declaredKwh: Double, peakDcKw: Double?): Mg4Variant {
            val nearest = values().minBy { abs(it.usableKwh - declaredKwh) }
            return if (nearest == STANDARD_51 && (peakDcKw ?: 0.0) > STANDARD_PEAK_KW) LONG_RANGE_64 else nearest
        }
    }
}

/** What the watched charges drew, per band of charge, split AC from DC. */
object ChargeCurve {
    const val BAND_PERCENT = 5
    const val BANDS = 100 / BAND_PERCENT

    /** No MG4 on-board charger reaches this: a charge that did was DC. */
    const val DC_ABOVE_KW = 20.0

    /** Pack-side power a 6.6 kW on-board charger never reaches: this car has the 11 kW one. */
    const val ELEVEN_KW_ABOVE = 7.5

    fun isDc(charge: ChargeEnergy): Boolean = (charge.peakPowerKw ?: 0.0) > DC_ABOVE_KW

    /** Time-weighted mean kW per band (index 0 = 0–5 %), null where no watched step fell. */
    fun measured(charges: List<ChargeEnergy>, dc: Boolean): List<Double?> {
        val ms = DoubleArray(BANDS)
        val kwMs = DoubleArray(BANDS)
        charges.filter { it.plugged && isDc(it) == dc }.flatMap { it.steps }.forEach {
            val band = (it.midSocPercent / BAND_PERCENT).toInt().coerceIn(0, BANDS - 1)
            ms[band] += it.durationMs.toDouble()
            kwMs[band] += it.powerKw * it.durationMs
        }
        return List(BANDS) { if (ms[it] > 0.0) kwMs[it] / ms[it] else null }
    }

    fun peakDcKw(charges: List<ChargeEnergy>): Double? =
        charges.filter { it.plugged && isDc(it) }.mapNotNull { it.peakPowerKw }.maxOrNull()

    /** The on-board charger, from the AC charges watched; the version's usual one otherwise. */
    fun acKw(charges: List<ChargeEnergy>, variant: Mg4Variant): Double {
        val peak = charges.filter { it.plugged && !isDc(it) }.mapNotNull { it.peakPowerKw }.maxOrNull()
            ?: return variant.defaultAcKw
        return if (peak > ELEVEN_KW_ABOVE) 11.0 else 6.6
    }
}
