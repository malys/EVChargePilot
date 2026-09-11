package com.evsuite.chargepilot

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import org.w3c.dom.Node
import javax.xml.parsers.DocumentBuilderFactory

/**
 * "Y aller" went missing from the car twice: once under seven cards, once in a bar that only
 * appeared when a route came back. Both were the same regression — the one action that ends
 * this screen being conditional on something the driver cannot see. This test is what stops a
 * third: the button is in the always-on-screen top bar, nothing declares it hidden, and no
 * code path hides it. Disabling it is still allowed; disappearing is not.
 */
class ChargeStopNavigateActionTest {
    @Test fun `the navigate action is in the top bar and nothing declares it hidden`() {
        val root = parse(module("src/main/res/layout/activity_charge_stop.xml")).documentElement
        val topBar = children(root).first()
        val action = children(topBar).firstOrNull { it.getAttribute("android:id") == ACTION_ID }
        assertTrue("navigateAction is not in the top bar", action != null)
        generateSequence(action as Node?) { it.parentNode }
            .filterIsInstance<Element>()
            .forEach {
                assertFalse(
                    "${it.getAttribute("android:id").ifEmpty { it.tagName }} declares a visibility",
                    it.hasAttribute("android:visibility"),
                )
            }
    }

    @Test fun `no code path hides the navigate action`() {
        val source = module("src/main/java/com/evsuite/chargepilot/ChargeStopActivity.kt").readText()
        assertFalse(
            "the navigate action is hidden somewhere; disable it instead",
            Regex("navigateAction\\.(visibility|isVisible)").containsMatchIn(source),
        )
    }

    private fun children(element: Element): List<Element> =
        (0 until element.childNodes.length)
            .mapNotNull { element.childNodes.item(it) as? Element }

    private fun parse(file: File) =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)

    /** Run from the module or from the workspace root, and the file is the same file. */
    private fun module(path: String): File =
        listOf(File(path), File("app/$path"), File("EVChargePilot/app/$path"))
            .first { it.isFile }

    private companion object {
        const val ACTION_ID = "@+id/navigateAction"
    }
}
