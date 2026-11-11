package com.example.myexodus.accountability.delivery

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Plan §8.2 golden vectors. The same fixture is consumed by
 * `functions/test/partner-events.test.js`, so a JCS drift on either side fails one suite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class JcsTest {

    @Test
    fun `shared fixture vectors canonicalize identically in Kotlin`() {
        val vectors = fixture().getJSONArray("jcs")
        for (index in 0 until vectors.length()) {
            val vector = vectors.getJSONObject(index)
            assertEquals(
                vector.getString("name"),
                vector.getString("canonical"),
                Jcs.canonicalize(vector.get("value").toPlainValue()),
            )
        }
    }

    @Test
    fun `objects sort by UTF-16 code units`() {
        assertEquals("""{"B":3,"a":2,"aa":1}""", Jcs.canonicalize(linkedMapOf("aa" to 1, "a" to 2, "B" to 3)))
    }

    @Test
    fun `escapes the RFC 8785 set and leaves Unicode literal`() {
        assertEquals("\"\\u0001\\f\"", Jcs.canonicalize("\u0001\u000C"))
        assertEquals("\"é😀\"", Jcs.canonicalize("é😀"))
        assertEquals("\"\\\"\\\\\\b\\n\\r\\t\"", Jcs.canonicalize("\"\\\b\n\r\t"))
    }

    @Test
    fun `rejects values the protocol never carries`() {
        assertThrows(IllegalArgumentException::class.java) { Jcs.canonicalize(1.5) }
        assertThrows(IllegalArgumentException::class.java) { Jcs.canonicalize(Long.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { Jcs.canonicalize(mapOf("a" to 1.5)) }
    }

    @Test
    fun `rejects unpaired surrogates`() {
        assertThrows(IllegalArgumentException::class.java) { Jcs.canonicalize("\uD800") }
        assertThrows(IllegalArgumentException::class.java) { Jcs.canonicalize("\uDC00") }
        assertEquals("\"😀\"", Jcs.canonicalize("😀"))
    }

    private fun fixture(): JSONObject {
        val file = listOf(
            File("../functions/test/fixtures/partner-event-jcs-v1.json"),
            File("functions/test/fixtures/partner-event-jcs-v1.json"),
        ).firstOrNull { it.isFile } ?: error("shared fixture not found from ${File(".").absolutePath}")
        return JSONObject(file.readText())
    }

    private fun Any?.toPlainValue(): Any? = when (this) {
        null, JSONObject.NULL -> null
        is JSONObject -> keys().asSequence().associateWith { get(it).toPlainValue() }
        is JSONArray -> (0 until length()).map { get(it).toPlainValue() }
        else -> this
    }
}
