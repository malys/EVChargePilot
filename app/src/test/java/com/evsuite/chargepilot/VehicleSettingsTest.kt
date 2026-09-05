package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleSettingsTest {

    @Test fun `typed figures replace the specification sheet`() {
        val parsed = VehicleSettings.parse("54,2", "88", "50", "15")
        val values = (parsed as VehicleSettings.Parsed.Ok).values
        // The dashboard keyboard produces a comma, and a comma is a decimal point here.
        assertEquals(54.2, values.usableCapacityKwhWhenNew, 1e-9)
        assertEquals(88.0, values.stateOfHealthPercent, 1e-9)
        assertEquals(50.0, values.minChargerPowerKw, 1e-9)
        assertEquals(15.0, values.reservePercent, 1e-9)
        assertTrue("no longer the defaults", !values.isDefault)
        // The pack the climb is a percentage of is now the driver's, not EVKX's.
        assertEquals(54.2, values.pack.usableCapacityKwhWhenNew, 1e-9)
        assertEquals(88.0, values.pack.stateOfHealthPercent, 1e-9)
    }

    @Test fun `an empty field means the documented default, not zero`() {
        val values = (VehicleSettings.parse("", "", "", "") as VehicleSettings.Parsed.Ok).values
        assertEquals(VehicleSettings.DEFAULT_CAPACITY_KWH, values.usableCapacityKwhWhenNew, 1e-9)
        assertEquals(VehicleSettings.DEFAULT_RESERVE_PERCENT, values.reservePercent, 1e-9)
        assertTrue(values.isDefault)
    }

    @Test fun `nonsense is refused by name, so the screen can say which box`() {
        // A capacity of zero divides the climb by nothing; a health of 300 % invents a pack;
        // a 90 % reserve turns every trip into a charging stop.
        assertEquals(
            VehicleSettings.Field.CAPACITY,
            (VehicleSettings.parse("0", "100", "22", "10")
                as VehicleSettings.Parsed.Refused).field,
        )
        assertEquals(
            VehicleSettings.Field.HEALTH,
            (VehicleSettings.parse("61,7", "300", "22", "10")
                as VehicleSettings.Parsed.Refused).field,
        )
        assertEquals(
            VehicleSettings.Field.MIN_POWER,
            (VehicleSettings.parse("61,7", "100", "0", "10")
                as VehicleSettings.Parsed.Refused).field,
        )
        assertEquals(
            VehicleSettings.Field.RESERVE,
            (VehicleSettings.parse("61,7", "100", "22", "90")
                as VehicleSettings.Parsed.Refused).field,
        )
        assertEquals(
            VehicleSettings.Field.CAPACITY,
            (VehicleSettings.parse("sixty", "100", "22", "10")
                as VehicleSettings.Parsed.Refused).field,
        )
    }
}
