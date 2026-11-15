package io.github.hazymorning.amsel.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * The app offers German (`locales_config.xml`), so every strings file in `values` has a twin in
 * `values-de` with the same names and the same format arguments.
 */
class GermanStringsTest {
    @Test fun appStringsHaveGerman() = assertInStep(File("src/main/res"))

    @Test fun playbackStringsHaveGerman() = assertInStep(File("../playback/src/main/res"))

    @Test fun argumentsAreReadFromTheText() {
        assertEquals(setOf("%1\$s", "%2\$d"), arguments("„%1\$s“ hat %2\$d Titel, 100 %%"))
        assertEquals(emptySet<String>(), arguments("Keine Argumente"))
    }

    private fun assertInStep(res: File) {
        val english = stringFiles(File(res, "values"))
        val german = stringFiles(File(res, "values-de"))
        assertTrue("No strings in $res", english.isNotEmpty())
        assertEquals("Files without a twin in $res", english.keys, german.keys)
        english.forEach { (name, file) ->
            val en = strings(file)
            val de = strings(german.getValue(name))
            assertEquals("Without German in $name", emptySet<String>(), en.keys - de.keys)
            assertEquals("German without English in $name", emptySet<String>(), de.keys - en.keys)
            val differing = en.filter { (key, text) -> de[key]?.let { arguments(it) != arguments(text) } == true }
            assertEquals("Format arguments differ in $name", emptySet<String>(), differing.keys)
        }
    }

    private fun stringFiles(dir: File): Map<String, File> =
        dir.listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .associateBy { it.name }

    /** Each translatable string by name; a plural's or an array's items as one text. */
    private fun strings(file: File): Map<String, String> {
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        return KINDS.flatMap { kind ->
            val nodes = root.getElementsByTagName(kind)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }
            .filter { it.getAttribute("translatable") != "false" }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun arguments(text: String): Set<String> =
        FORMAT.findAll(text).map { it.value }.filter { it != "%%" }.toSet()

    private companion object {
        val KINDS = listOf("string", "plurals", "string-array")
        val FORMAT = Regex("""%(\d+\$)?[-#+0,(]*\d*(\.\d+)?[a-zA-Z%]""")
    }
}
