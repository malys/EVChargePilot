package com.evsuite.chargepilot

/**
 * The declared car permissions that a manifest entry alone does not grant.
 *
 * The Car service classifies `CAR_SPEED` (permission group `LOCATION`) and `CAR_ENERGY`
 * (group `CAR_MONITORING`) as `dangerous` on every generation this app targets, so
 * `CarPropertyManager` refuses those reads until the driver grants them at runtime. The
 * refusal is invisible from the seat: EVHardware turns an unreadable property into null, and a
 * null speed is exactly what an unsupported firmware produces — the trip controls stay
 * disabled, the automatic monitor suspends itself and the USB diagnostic export refuses,
 * all reporting "speed unreadable" for a permission the driver was never asked for.
 *
 * The other three car permissions this app declares — `CAR_EXTERIOR_ENVIRONMENT` (`normal`),
 * `CAR_VENDOR_EXTENSION` and `CONTROL_CAR_CLIMATE` (both `signature|privileged`, held through
 * the platform signature) — are granted at install time and must not be requested here.
 *
 * The set is named rather than read back from the manifest: a permission added to the manifest
 * must not silently become one this app puts in front of the driver.
 */
object VehiclePermissions {

    const val CAR_SPEED = "android.car.permission.CAR_SPEED"
    const val CAR_ENERGY = "android.car.permission.CAR_ENERGY"

    /** Asking for the LOCATION group grants no location: the app declares no location permission. */
    val RUNTIME = listOf(CAR_SPEED, CAR_ENERGY)

    /** The ones still to ask for, empty once the vehicle signals are reachable. */
    fun missing(isGranted: (String) -> Boolean): Array<String> =
        RUNTIME.filterNot(isGranted).toTypedArray()
}
