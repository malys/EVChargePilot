package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.BatteryLedgerEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ParkedBatteryAdviceTest {

    private val zone = ZoneOffset.UTC
    private val hour = 3_600_000L
    private val day = 24 * hour
    private val now = 90 * day

    /** One drive a day for [days] days ending yesterday: [kmOf] km at 0.2 points a km. */
    private fun history(days: Int, kmOf: (Int) -> Double): List<BatteryLedgerEntry> {
        val entries = ArrayList<BatteryLedgerEntry>()
        var odometer = 10_000.0
        (days downTo 1).forEach { back ->
            val start = now - back * day + 8 * hour
            val km = kmOf(back)
            entries += BatteryLedgerEntry(start, 80f, odometer.toFloat())
            odometer += km
            entries += BatteryLedgerEntry(start + hour, (80 - km * 0.2).toFloat(), odometer.toFloat())
        }
        return entries
    }

    @Test
    fun `short days get the floor, and a high limit is advised down`() {
        val proposal = ParkedBatteryAdvice.limitProposal(history(30) { 40.0 }, now, zone)!!
        assertEquals(60, proposal.proposedPercent)
        assertEquals(23.0, proposal.neededPercent, 1e-6)
        assertEquals(1.0, proposal.coveredShare, 1e-9)
        assertEquals(ParkedBatteryAdvice.LimitVerdict.LOWER, ParkedBatteryAdvice.limitVerdict(proposal, 100))
        assertNull(ParkedBatteryAdvice.limitVerdict(proposal, 65))
        assertEquals(ParkedBatteryAdvice.LimitVerdict.UNREAD, ParkedBatteryAdvice.limitVerdict(proposal, null))
    }

    @Test
    fun `one long day in twenty-five is outside the percentile, several are not`() {
        val one = ParkedBatteryAdvice.limitProposal(history(25) { if (it == 3) 300.0 else 40.0 }, now, zone)!!
        assertEquals(60, one.proposedPercent)
        assertEquals(24.0 / 25, one.coveredShare, 1e-9)
        val many = ParkedBatteryAdvice.limitProposal(history(20) { if (it % 4 == 0) 300.0 else 40.0 }, now, zone)!!
        assertEquals(75, many.proposedPercent)
        assertEquals(ParkedBatteryAdvice.LimitVerdict.RAISE, ParkedBatteryAdvice.limitVerdict(many, 70))
    }

    @Test
    fun `too little history proposes nothing`() {
        assertNull(ParkedBatteryAdvice.limitProposal(emptyList(), now, zone))
        assertNull(ParkedBatteryAdvice.limitProposal(history(10) { 40.0 }, now, zone))
    }

    @Test
    fun `a night at full charge is one dwell, a short stop is none`() {
        val entries = listOf(
            BatteryLedgerEntry(0L, 100f, 5_000f),
            BatteryLedgerEntry(13 * hour, 99f, 5_000f),
            BatteryLedgerEntry(13 * hour + 60_000, 98f, 5_010f),
            BatteryLedgerEntry(14 * hour, 95f, 5_010f),
            BatteryLedgerEntry(16 * hour, 95f, 5_010f),
        )
        val dwell = ParkedBatteryAdvice.dwells(entries).single()
        assertEquals(13.0, dwell.hours, 1e-9)
        assertEquals(99.0, dwell.socPercent, 1e-9)
    }

    @Test
    fun `heat needs parked, a high charge and a hot outside, once a day`() {
        val entries = listOf(
            BatteryLedgerEntry(10 * hour, 85f, 5_000f, outsideTempCelsius = 32f, parked = true),
            BatteryLedgerEntry(11 * hour, 86f, 5_000f, outsideTempCelsius = 34f, parked = true),
            BatteryLedgerEntry(12 * hour, 85f, 5_020f, outsideTempCelsius = 35f, parked = false),
            BatteryLedgerEntry(day + 10 * hour, 70f, 5_020f, outsideTempCelsius = 35f, parked = true),
        )
        val heat = ParkedBatteryAdvice.heat(entries, zone).single()
        assertEquals("1970-01-01", heat.day)
        assertEquals(34.0, heat.outsideCelsius, 1e-9)
        assertTrue(ParkedBatteryAdvice.heat(entries.drop(2), zone).isEmpty())
    }
}
