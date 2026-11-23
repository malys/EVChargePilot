package com.evsuite.chargepilot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** MG4Link declares geo: and drops it; it is never reported as having taken the destination. */
class MapAppsTest {

    @Test
    fun `a geo intent that lands on MG4Link carries no destination`() {
        assertFalse(MapApps.takesDestination(MapApps.MG4LINK_PACKAGE))
        assertFalse(MapApps.takesDestination(null))
        assertTrue(MapApps.takesDestination("com.google.android.apps.maps"))
    }

    @Test
    fun `MG4Link is told from the car's own AllGo app by its class`() {
        assertTrue(MapApps.isMg4Link("com.andrerinas.openheadunit.main.MainActivity"))
        assertFalse(MapApps.isMg4Link("com.allgo.app.androidauto.MainActivity"))
        assertFalse(MapApps.isMg4Link(null))
    }
}
