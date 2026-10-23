package com.evsuite.chargepilot

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

class WindowViewportTest {
    @Test fun `every scroll container has one content child`() {
        val directory = listOf(File("src/main/res/layout"), File("app/src/main/res/layout"),
            File("EVChargePilot/app/src/main/res/layout")).first { it.isDirectory }
        directory.listFiles()!!.filter { it.extension == "xml" }.forEach { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("*")
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as Element
                if (!element.tagName.endsWith("ScrollView")) continue
                assertEquals("${file.name}: ${element.tagName} needs one content child", 1,
                    (0 until element.childNodes.length).count { element.childNodes.item(it) is Element })
            }
        }
    }

    @Test fun `emulator checks use the measured car app box`() {
        val config = listOf(File("../mise.toml"), File("mise.toml"), File("EVChargePilot/mise.toml"))
            .first { it.isFile && it.readText().contains("EMU_APP_HEIGHT") }.readText()
        for ((key, value) in mapOf("EMU_WIDTH" to "1778", "EMU_APP_HEIGHT" to "640", "EMU_DENSITY" to "160")) {
            assertEquals("$key must match the car's measured app box", value,
                Regex("(?m)^$key = \"([^\"]+)\"").find(config)?.groupValues?.get(1))
        }
    }
}
