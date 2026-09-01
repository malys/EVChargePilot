package com.evsuite.chargepilot

import com.evsuite.hardware.telemetry.EnergySnapshot

/** Lossless normalized snapshot for comparing screen output with captured property evidence. */
internal object DiagnosticSnapshotFormatter {

    fun format(snapshot: EnergySnapshot?): List<String> {
        if (snapshot == null) return listOf("snapshot=unavailable")
        val climate = snapshot.climate
        val tires = snapshot.tirePressures
        return listOf(
            "timestamp_epoch_ms=${snapshot.timestampMs}",
            "firmware=${snapshot.firmware.name}",
            "soc_percent=${snapshot.socPercent.value()}",
            "range_km=${snapshot.rangeKm.value()}",
            "speed_kmh=${snapshot.speedKmh.value()}",
            "battery_power_kw=${snapshot.batteryPowerKw.value()}",
            "outside_temp_c=${snapshot.outsideTempCelsius.value()}",
            "cabin_temp_c=${snapshot.cabinTempCelsius.value()}",
            "battery_temp_c=${snapshot.batteryTempCelsius.value()}",
            "battery_energy_kwh=${snapshot.batteryEnergyKwh.value()}",
            "battery_capacity_kwh=${snapshot.batteryCapacityKwh.value()}",
            "odometer_km=${snapshot.odometerKm.value()}",
            "charge_port_connected=${snapshot.chargePortConnected.value()}",
            "charging_status=${snapshot.chargingStatus.value()}",
            "parked=${snapshot.parked.value()}",
            "climate.power_on=${climate.powerOn.value()}",
            "climate.ac_on=${climate.acOn.value()}",
            "climate.auto_on=${climate.autoOn.value()}",
            "climate.econ_on=${climate.econOn.value()}",
            "climate.recirculation_on=${climate.recirculationOn.value()}",
            "climate.fan_level=${climate.fanLevel.value()}",
            "climate.fan_level_max=${climate.fanLevelMax.value()}",
            "climate.driver_target_c=${climate.driverTargetCelsius.value()}",
            "climate.passenger_target_c=${climate.passengerTargetCelsius.value()}",
            "tire.front_left_kpa=${tires.frontLeftKpa.value()}",
            "tire.front_right_kpa=${tires.frontRightKpa.value()}",
            "tire.rear_left_kpa=${tires.rearLeftKpa.value()}",
            "tire.rear_right_kpa=${tires.rearRightKpa.value()}",
        )
    }

    private fun Any?.value(): String = this?.toString() ?: "unavailable"
}
