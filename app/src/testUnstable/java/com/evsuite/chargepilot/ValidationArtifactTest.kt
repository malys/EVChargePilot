package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ValidationArtifactTest {

    private fun artifact(
        lines: Map<ValidationQuestion, List<String>> = emptyMap(),
        on: Boolean = true,
        incomplete: Set<ValidationQuestion> = emptySet(),
    ) = ValidationArtifact.of(
        savedAtMs = 1_700_000_000_000L,
        firmware = "SWI68",
        validationModeOn = on,
        armedAtMs = 1_699_999_000_000L,
        lines = lines,
        incomplete = incomplete,
    )

    @Test
    fun `a question that recorded nothing still says what would have made it fire`() {
        val artifact = artifact(
            mapOf(ValidationQuestion.ROUTE_SECTIONS to listOf("route 210.0 km, 4 section(s)")),
        )
        assertEquals(ValidationQuestion.entries.size, artifact.questions.size)

        val sections = artifact.questions.single { it.id == ValidationQuestion.ROUTE_SECTIONS.id }
        assertTrue(sections.fired)

        // The one the drive never reached is the one a reader most needs explained: without
        // this line "no chargers" and "no search" look identical in the bundle.
        val chargers = artifact.questions.single { it.id == ValidationQuestion.CHARGERS.id }
        assertFalse(chargers.fired)
        assertTrue(chargers.status, chargers.status.contains(ValidationQuestion.CHARGERS.firesWhen))
        assertEquals("CP-048", chargers.ticket)
    }

    @Test
    fun `with the toggle off, the empty blocks blame the toggle and not the drive`() {
        artifact(on = false).questions.forEach {
            assertTrue(it.status, it.status.contains("validation mode was off"))
        }
    }

    @Test
    fun `an oversized artifact loses the longest block's oldest lines, and admits it`() {
        val long = (1..400).map { "line $it ${"x".repeat(200)}" }
        val short = listOf("first", "second")
        val bounded = artifact(
            mapOf(
                ValidationQuestion.ROUTE_SECTIONS to long,
                ValidationQuestion.LOCATION_GRANT to short,
            ),
        ).toBoundedJson()

        // Over the exporter's per-file ceiling the file is not truncated, it is dropped and
        // merely listed as skipped — so the drive's one artifact would go missing quietly.
        assertTrue(
            "size=${bounded.toByteArray(Charsets.UTF_8).size}",
            bounded.toByteArray(Charsets.UTF_8).size <= ValidationArtifact.MAX_ARTIFACT_BYTES,
        )
        assertTrue("the newest lines are what survive", bounded.contains("line 400"))
        assertFalse("the oldest are what go", bounded.contains("\"line 1 "))
        // A block that gave nothing up is still trustworthy, and says so separately.
        assertTrue(bounded.contains("first"))
        assertTrue(bounded.contains("second"))
    }
}
