package com.example.myexodus.accountability.delivery

/**
 * RFC 8785 (JCS) canonicalization for the partner-delivery event envelope. Constrained to
 * the value types the protocol produces: null, Boolean, non-surrogate-corrupt String,
 * safe integer, List and String-keyed Map. Floating point is rejected — the protocol never
 * carries it, and JCS double formatting is a separate risk surface.
 *
 * Server mirror: `functions/partner-events.js` `canonicalJson`. Shared vectors:
 * `functions/test/fixtures/partner-event-jcs-v1.json`.
 */
object Jcs {

    // ±(2^53 - 1): the range every JCS implementation formats identically.
    private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L

    fun canonicalize(value: Any?): String = buildString { appendValue(value) }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is Boolean -> append(if (value) "true" else "false")
            is String -> appendString(value)
            is Int, is Long -> {
                val n = (value as Number).toLong()
                require(n in -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER) { "unsafe JCS integer" }
                append(n)
            }
            is List<*> -> {
                append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    appendValue(item)
                }
                append(']')
            }
            is Map<*, *> -> appendObject(value)
            else -> throw IllegalArgumentException("unsupported JCS value: ${value.javaClass.name}")
        }
    }

    private fun StringBuilder.appendObject(map: Map<*, *>) {
        require(map.keys.all { it is String }) { "JCS object keys must be strings" }
        append('{')
        // Kotlin String.compareTo orders by UTF-16 code units, which is RFC 8785's rule.
        @Suppress("UNCHECKED_CAST")
        val keys = (map.keys as Set<String>).sorted()
        keys.forEachIndexed { index, key ->
            if (index > 0) append(',')
            appendString(key)
            append(':')
            appendValue(map[key])
        }
        append('}')
    }

    /** ECMAScript JSON.stringify string serialization, which RFC 8785 §3.2.2.2 adopts. */
    private fun StringBuilder.appendString(value: String) {
        append('"')
        var i = 0
        while (i < value.length) {
            val c = value[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
                c.isHighSurrogate() -> {
                    require(i + 1 < value.length && value[i + 1].isLowSurrogate()) {
                        "unpaired high surrogate"
                    }
                    append(c).append(value[i + 1])
                    i++
                }
                c.isLowSurrogate() -> throw IllegalArgumentException("unpaired low surrogate")
                else -> append(c)
            }
            i++
        }
        append('"')
    }
}
