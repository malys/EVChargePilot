package com.evsuite.chargepilot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VehiclePermissionsTest {

    @Test
    fun `only the dangerous car permissions are requested at runtime`() {
        assertEquals(
            listOf(
                "android.car.permission.CAR_SPEED",
                "android.car.permission.CAR_ENERGY",
            ),
            VehiclePermissions.RUNTIME,
        )
    }

    @Test
    fun `a fresh install is asked for every runtime permission`() {
        assertArrayEquals(
            VehiclePermissions.RUNTIME.toTypedArray(),
            VehiclePermissions.missing { false },
        )
    }

    @Test
    fun `a granted permission is not asked for again`() {
        assertArrayEquals(
            arrayOf(VehiclePermissions.CAR_ENERGY),
            VehiclePermissions.missing { it == VehiclePermissions.CAR_SPEED },
        )
    }

    @Test
    fun `nothing is asked once the vehicle signals are reachable`() {
        assertArrayEquals(emptyArray<String>(), VehiclePermissions.missing { true })
    }
}
