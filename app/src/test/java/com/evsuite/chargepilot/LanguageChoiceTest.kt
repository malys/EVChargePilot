package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The language list's two mappings, which have to be each other's inverse or the screen shows a
 * different language from the one it stored.
 */
class LanguageChoiceTest {

    @Test
    fun everyEntryRoundTripsThroughItsTag() {
        LANGUAGE_TAGS.forEachIndexed { index, tag ->
            assertEquals(index, languageIndex(tag))
        }
    }

    @Test
    fun aRegionalTagIsTheSameEntryAsItsLanguage() {
        assertEquals(LANGUAGE_TAGS.indexOf("fr"), languageIndex("fr-FR"))
        assertEquals(LANGUAGE_TAGS.indexOf("pt"), languageIndex("pt-BR"))
        assertEquals(LANGUAGE_TAGS.indexOf("de"), languageIndex("de-AT,en"))
    }

    @Test
    fun noStoredChoiceOrAnUnknownOneIsTheSystem() {
        assertEquals(0, languageIndex(""))
        assertEquals(0, languageIndex("nl-NL"))
        assertEquals("", LANGUAGE_TAGS[0])
    }

    /** EVTasker's five, so the two apps offer the driver the same list. */
    @Test
    fun theListIsTheSuitesFiveLanguages() {
        assertEquals(listOf("", "de", "en", "es", "fr", "it", "pt"), LANGUAGE_TAGS)
    }
}
