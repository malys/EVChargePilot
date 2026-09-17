package com.evsuite.chargepilot

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `%%` is an escape, and only a formatted string is ever unescaped: `getString(id)` hands back the
 * two characters as they are, which is how the battery page came to offer "Charging to 100 %%".
 * A string carrying `%%` and no `%n$` argument is therefore always one of two bugs — a doubled
 * sign shown to the driver, or an argument that was meant to be there and is not. Both locales are
 * checked, because a translation is where the escape survives longest.
 */
class StringPercentTest {
    @Test
    fun `an escaped percent only appears in a string that is formatted`() {
        val offenders = LOCALES.flatMap { locale ->
            val file = File("src/main/res/$locale/strings.xml")
            val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val strings = root.getElementsByTagName("string")
            (0 until strings.length).map { strings.item(it) }
                .filter { it.textContent.contains("%%") && !ARGUMENT.containsMatchIn(it.textContent) }
                .map { "$locale/${it.attributes.getNamedItem("name").nodeValue}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    private companion object {
        val LOCALES = listOf("values", "values-fr")
        val ARGUMENT = Regex("""%\d+\$""")
    }
}
