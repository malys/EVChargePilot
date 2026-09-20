package com.evsuite.chargepilot

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The language row's two mappings, which have to be each other's inverse or the screen shows a
 * different language from the one it stored.
 */
class LanguageChoiceTest {

    @Test
    fun everyButtonRoundTripsThroughItsTag() {
        for (button in listOf(R.id.languageSystem, R.id.languageEnglish, R.id.languageFrench)) {
            assertEquals(button, languageButton(languageTag(button)))
        }
    }

    @Test
    fun aRegionalTagIsTheSameButtonAsItsLanguage() {
        assertEquals(R.id.languageFrench, languageButton("fr-FR"))
        assertEquals(R.id.languageEnglish, languageButton("en-GB"))
    }

    @Test
    fun noStoredChoiceIsTheSystem() {
        assertEquals(R.id.languageSystem, languageButton(""))
        assertEquals("", languageTag(R.id.languageSystem))
    }
}
