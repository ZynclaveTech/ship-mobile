package com.zynclave.ship.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared definitions corpus at the repo root (`testdata/sdui`): the iOS
 * tests run the same files, so both parsers accept and refuse exactly the same
 * definitions.
 */
class SduiCorpusTest {
    private val root = File(requireNotNull(System.getProperty("ship.testdata")) { "ship.testdata is not set" }, "sdui")

    private fun files(dir: String): List<File> =
        File(root, dir).listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
            .also { assertTrue("no files in $dir", it.isNotEmpty()) }

    private fun parse(file: File) {
        val data = file.readText()
        val assetExists = { asset: String -> "missing" !in asset }
        when {
            file.name.startsWith("card-") -> Sdui.parseCard(data, "shipsample", setOf("tile"), assetExists)
            file.name.startsWith("popup-") -> Sdui.parsePopup(data, "shipsample", setOf("tile"), assetExists)
            else -> fail("${file.name}: name must start card- or popup-")
        }
    }

    @Test
    fun `every accept file parses`() {
        for (file in files("accept")) {
            try {
                parse(file)
            } catch (e: Sdui.Invalid) {
                fail("${file.name} was refused: ${e.reason}")
            }
        }
    }

    @Test
    fun `every reject file is refused`() {
        for (file in files("reject")) {
            try {
                parse(file)
                fail("${file.name} parsed")
            } catch (_: Sdui.Invalid) {
            }
        }
    }

    @Test
    fun `a non-boolean dismissible is ignored, so the card keeps the default`() {
        val card = Sdui.parseCard(
            File(root, "accept/card-dismissible-number-keeps-default.json").readText(), "shipsample", emptySet(),
        ) { true }
        assertEquals(true, card.dismissible)
    }
}
