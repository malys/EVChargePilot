package com.evsuite.chargepilot

/**
 * The last readings the dashboard drew, kept where the diagnostics page can read them.
 *
 * Consumption smoothing and the adaptive range estimate are stateful: they exist in the
 * dashboard's calculators and cannot be rebuilt from one sample. Now that diagnostics is its own
 * page rather than a dialog over the dashboard, it reads the published frame instead of
 * recomputing a weaker one. Everything else in the report comes from the vehicle or the recorder
 * directly, so a driver who opens diagnostics first gets an empty provenance section rather than
 * an invented one.
 */
object DashboardFrame {

    @Volatile
    var readings: DashboardReadings = DashboardReadings.empty()
        private set

    fun publish(value: DashboardReadings) {
        readings = value
    }
}
