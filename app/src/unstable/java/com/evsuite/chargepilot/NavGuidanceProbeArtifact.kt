package com.evsuite.chargepilot

import com.evsuite.hardware.saic.NavGuidanceReducer
import com.google.gson.Gson

/**
 * The CP-040 trace as a file, because a clipboard never reaches the USB stick.
 *
 * It is written into the same `evidence` folder the capture files use, so the diagnostic
 * export picks it up with everything else and one bundle carries every probe. The caveats
 * that used to live in the copied Markdown are carried in [notes]: whoever reads the file
 * away from the car has to read them with it.
 */
internal data class NavGuidanceProbeArtifact(
    val schemaVersion: Int = SCHEMA_VERSION,
    val probe: String = PROBE,
    val savedAtMs: Long,
    val firmware: String,
    val adapterBound: Boolean,
    val listenerRegistered: Boolean,
    val callbacks: Int,
    /** Transaction code to count, exactly as the census saw it. */
    val census: Map<Int, Int>,
    /** Codes the R69 transaction map does not name: the signal that the map is shifted. */
    val undecodedCodes: List<Int>,
    val censusBeyondCeiling: Int,
    val traceLines: Int,
    /** False once the ring dropped its oldest lines. */
    val traceComplete: Boolean,
    val notes: List<String> = NOTES,
    val trace: List<String>,
) {
    fun toJson(): String = GSON.toJson(this)

    /**
     * The artifact, guaranteed to fit the diagnostic export's per-file ceiling.
     *
     * A file over that ceiling is not truncated by the exporter, it is dropped and merely
     * listed as skipped — so the one artifact a validation run exists to produce would go
     * missing quietly, and only a careful reader of the manifest would notice. 300 lines of
     * trace with accented road names lands around 47 KB against a 64 KB limit, which is
     * enough headroom until it is not.
     *
     * Oldest trace lines go first, because the end of a drive is what a forecast is judged
     * on. Dropping any marks [traceComplete] false, exactly as the ring overflowing does.
     */
    fun toBoundedJson(maxBytes: Int = MAX_ARTIFACT_BYTES): String {
        var candidate = this
        var json = candidate.toJson()
        while (json.toByteArray(Charsets.UTF_8).size > maxBytes && candidate.trace.isNotEmpty()) {
            val keep = (candidate.trace.size * 3) / 4
            candidate = candidate.copy(
                trace = candidate.trace.takeLast(keep.coerceAtLeast(0)),
                traceComplete = false,
            )
            json = candidate.toJson()
        }
        return json
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val PROBE = "nav-guidance"

        /** File name kind, kept apart from the capture files sharing the evidence folder. */
        const val KIND = "navguidance"

        /**
         * Comfortably inside the exporter's own 64 KiB per-file ceiling, so a file that grew
         * between the write and the export still travels.
         */
        const val MAX_ARTIFACT_BYTES = 48 * 1024

        private val GSON = Gson()

        private val NOTES = listOf(
            "dist and turn are raw callback values; their unit is unproven. State it by " +
                "comparing against a distance known independently before using any of this.",
            "The transaction map was read from an R69 build. Traffic on undecoded codes, or " +
                "silence on the decoded ones while guidance is clearly running, means this " +
                "firmware numbers the interface differently and every reading is off by the " +
                "same shift.",
        )

        fun of(
            savedAtMs: Long,
            firmware: String,
            adapterBound: Boolean,
            listenerRegistered: Boolean,
            callbacks: Int,
            census: Map<Int, Int>,
            censusBeyondCeiling: Int,
            trace: List<String>,
            traceComplete: Boolean,
        ) = NavGuidanceProbeArtifact(
            savedAtMs = savedAtMs,
            firmware = firmware,
            adapterBound = adapterBound,
            listenerRegistered = listenerRegistered,
            callbacks = callbacks,
            census = census.toSortedMap(),
            undecodedCodes = census.keys
                .filterNot { it in NavGuidanceReducer.KNOWN_TRANSACTIONS }
                .sorted(),
            censusBeyondCeiling = censusBeyondCeiling,
            traceLines = trace.size,
            traceComplete = traceComplete,
            trace = trace,
        )
    }
}
