package com.evsuite.chargepilot

import com.google.gson.GsonBuilder

/**
 * One validation drive, as the file a session at a desk reads six weeks later.
 *
 * Deliberately one file rather than one per question, against the letter of CP-055's scope.
 * The diagnostic export bundles at most [DiagnosticExporter.MAX_EVIDENCE_FILES] artifacts and
 * keeps the newest; eight validation files would evict the guidance trace, the signal
 * statistics and the trip history from the very bundle they are meant to travel in. So every
 * question keeps its own named, separately readable block, and they share a file.
 *
 * Pretty-printed for the same reason: this is read by a person, in whatever text editor a
 * laptop opened the USB stick with.
 */
internal data class ValidationArtifact(
    val schemaVersion: Int = SCHEMA_VERSION,
    val probe: String = PROBE,
    val savedAtMs: Long,
    val firmware: String,
    /** False means the toggle was never turned on, which explains every empty block below. */
    val validationModeOn: Boolean,
    val armedAtMs: Long?,
    val notes: List<String> = NOTES,
    val questions: List<Answer>,
) {
    data class Answer(
        val id: String,
        val ticket: String,
        val question: String,
        val fired: Boolean,
        /** When [fired] is false, what would have made it fire — the finding, not a blank. */
        val status: String,
        /** False once this block's ring dropped its oldest lines. */
        val complete: Boolean,
        val lines: List<String>,
    )

    fun toJson(): String = GSON.toJson(this)

    /**
     * The artifact, guaranteed to fit the exporter's per-file ceiling.
     *
     * A file over that ceiling is not truncated by the exporter, it is dropped and merely
     * listed as skipped — so the one file a validation drive exists to produce would go
     * missing quietly. The longest block gives up its oldest lines first, because a question
     * that recorded forty lines can spare some and a question that recorded two cannot.
     */
    fun toBoundedJson(maxBytes: Int = MAX_ARTIFACT_BYTES): String {
        var candidate = this
        var json = candidate.toJson()
        while (json.toByteArray(Charsets.UTF_8).size > maxBytes) {
            val longest = candidate.questions.maxByOrNull { it.lines.size } ?: break
            if (longest.lines.isEmpty()) break
            candidate = candidate.copy(
                questions = candidate.questions.map { answer ->
                    if (answer !== longest) {
                        answer
                    } else {
                        answer.copy(
                            lines = answer.lines.drop(1 + answer.lines.size / 4),
                            complete = false,
                        )
                    }
                }
            )
            json = candidate.toJson()
        }
        return json
    }

    companion object {
        /**
         * The recorded lines as answers, including the questions nothing was recorded for.
         *
         * An absent block is the one thing this file must never contain: a reader six weeks
         * later cannot tell "no chargers came back" from "the search never ran", and the two
         * close different tickets. So every question appears, and an empty one says what
         * would have made it fire.
         */
        fun of(
            savedAtMs: Long,
            firmware: String,
            validationModeOn: Boolean,
            armedAtMs: Long?,
            lines: Map<ValidationQuestion, List<String>>,
            incomplete: Set<ValidationQuestion>,
        ) = ValidationArtifact(
            savedAtMs = savedAtMs,
            firmware = firmware,
            validationModeOn = validationModeOn,
            armedAtMs = armedAtMs,
            questions = ValidationQuestion.entries.map { question ->
                val recorded = lines[question].orEmpty()
                Answer(
                    id = question.id,
                    ticket = question.ticket,
                    question = question.question,
                    fired = recorded.isNotEmpty(),
                    status = when {
                        recorded.isNotEmpty() -> "recorded ${recorded.size} line(s)"
                        !validationModeOn ->
                            "never fired: validation mode was off for this whole process"
                        else -> "never fired, and it records when ${question.firesWhen}"
                    },
                    complete = question !in incomplete,
                    lines = recorded,
                )
            },
        )

        const val SCHEMA_VERSION = 1
        const val PROBE = "cp055-validation"

        /** File name kind, kept apart from the other artifacts sharing the evidence folder. */
        const val KIND = "validation"

        /** Inside the exporter's 64 KiB per-file ceiling, with room for it to grow. */
        const val MAX_ARTIFACT_BYTES = 40 * 1024

        private val GSON = GsonBuilder().setPrettyPrinting().create()

        private val NOTES = listOf(
            "CP-055. One drive, every question that needed a car. Read it beside " +
                "analysis/CP-055_validation_drive.md, which says what the drive was.",
            "Each line is prefixed with the seconds elapsed since the probes were armed.",
            "What is deliberately absent: the API keys, the destination text the driver " +
                "typed, and any coordinate of the origin. None of it is recorded here.",
            "What is deliberately present, and travels on the USB stick: the names of roads " +
                "on the route driven, and the raw parcel bytes of transactions 38 and 39, " +
                "which may contain the destination set in the car's own navigation.",
        )
    }
}
