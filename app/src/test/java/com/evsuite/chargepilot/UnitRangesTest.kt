package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitRangesTest {

    private fun units(text: String) = unitRanges(text).map { text.substring(it) }

    @Test
    fun `the unit after the figure is found, the figure is not`() {
        assertEquals(listOf("%"), units("58,6 %"))
        assertEquals(listOf("kW"), units("+0,3 kW"))
        assertEquals(listOf("km"), units("— km"))
        assertEquals(listOf("°C"), units("13 °C"))
    }

    @Test
    fun `a compound unit stays whole and an estimate has two`() {
        assertEquals(listOf("kWh/100 km"), units("12,2 kWh/100 km"))
        assertEquals(listOf("km", "km"), units("≈ 120 km ± 15 km"))
    }

    @Test
    fun `a route line shrinks its units, not its words or clock`() {
        assertEquals(listOf("km", "min"), units("120,5 km · 45 min · arrivée 19:30"))
        assertEquals(listOf("km"), units("≈ 35 km d’autonomie restante à l’arrivée"))
        assertEquals(listOf("km/h"), units("130 km/h"))
    }

    @Test
    fun `a bare dash has no unit`() {
        assertEquals(emptyList<String>(), units("—"))
    }
}
