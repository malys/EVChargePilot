package com.evsuite.chargepilot

import com.evsuite.hardware.FirmwareInfo
import com.evsuite.hardware.probe.InterfaceProbe
import com.evsuite.hardware.probe.RuntimeInterfaceProbe
import com.google.gson.Gson

/**
 * The `RI-` survey, carried out on the diagnostic bundle.
 *
 * Six of the nine `RI-` tickets are blocked on the same missing fact: which vendor interfaces
 * this head unit publishes and where they answer. `RuntimeInterfaceProbe` asks that question,
 * but it lives in a library with no way out of the car — the survey has to leave on the USB
 * stick or it was never run. This is that exit.
 *
 * Written on the export path like every other always-on artifact, so a driver who exports once
 * carries the map whether or not they knew a survey existed.
 *
 * **Nothing here is production and nothing may become it.** The probe calls no setter on any
 * interface, and this artifact records what it read rather than returning a value the app could
 * act on. A reading that turns into a fallback has skipped the question the survey exists to
 * ask.
 */
internal data class RuntimeInterfaceArtifact(
    val schemaVersion: Int = SCHEMA_VERSION,
    val probe: String = PROBE,
    val savedAtMs: Long,
    val firmware: String,
    val notes: List<String> = NOTES,
    val interfaces: List<InterfaceProbe>,
) {
    fun toJson(): String = GSON.toJson(this)

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROBE = "runtime-interfaces"
        const val KIND = "interfaces"

        private val GSON = Gson()

        private val NOTES = listOf(
            "ABSENT, DENIED and ANSWERED are three different answers. Absent closes a " +
                "question; denied says the capability is on this car and this app is not " +
                "allowed it, which is worth knowing before a drive is spent on it.",
            "descriptor is the binder's own name, read from the handle. Nothing in this " +
                "survey calls into an interface of unknown meaning, and no setter is " +
                "reachable from the probe at all.",
            "values are raw reads kept as strings. A probe that converted before recording " +
                "would have made the assumption it exists to test.",
            "A hub binds asynchronously. Every entry reading ABSENT in a bundle exported " +
                "seconds after the app started is the survey running too early, not the head " +
                "unit lacking the interface — export again after the app has been up a while.",
        )

        fun of(nowMs: Long = System.currentTimeMillis()) = RuntimeInterfaceArtifact(
            savedAtMs = nowMs,
            firmware = FirmwareInfo.getGeneration().name,
            // A survey that throws still leaves the bundle: an empty list under a firmware
            // name says the probe ran and produced nothing, which a missing file does not.
            interfaces = runCatching { RuntimeInterfaceProbe.survey() }.getOrDefault(emptyList()),
        )
    }
}
