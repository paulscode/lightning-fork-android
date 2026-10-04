package com.paulscode.lightningfork

import com.paulscode.lightningfork.net.AppStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The English the plain classes fall back to (in the JVM tests, and as the
 * default) is the English in strings_app.xml, word for word: the two are
 * written apart, and must not drift.
 */
class AppStringsTest {
    private fun xmlStrings(file: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(file))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val n = nodes.item(i)
            // As Android reads it: \' is an apostrophe, \" a quote.
            n.attributes.getNamedItem("name").nodeValue to n.textContent.replace("\\'", "'").replace("\\\"", "\"")
        }
    }

    @Test fun the_english_fallback_is_the_resource_english() {
        val xml = xmlStrings("src/main/res/values/strings_app.xml")
        var checked = 0
        for (field in R.string::class.java.fields) {
            val name = field.name
            if (!name.startsWith("app_")) continue
            val id = field.getInt(null)
            val english = runCatching { AppStrings.English.get(id, 7) }.getOrNull() ?: continue
            val resource = xml[name] ?: error("$name is in the English table but not in strings_app.xml")
            assertEquals(name, String.format(java.util.Locale.US, resource, 7), english)
            checked++
        }
        assertTrue("checked $checked", checked >= 19)
    }
}
