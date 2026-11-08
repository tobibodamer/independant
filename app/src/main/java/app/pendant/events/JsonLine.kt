package app.pendant.events

import java.util.Locale

/** Builds one flat JSON object for the event log (pure, JVM-testable). */
class JsonLine {
    private val sb = StringBuilder("{")

    fun put(key: String, value: String?): JsonLine = key(key).apply {
        if (value == null) sb.append("null") else quote(value)
    }

    fun put(key: String, value: Long): JsonLine = key(key).apply { sb.append(value) }

    fun put(key: String, value: Int): JsonLine = put(key, value.toLong())

    fun put(key: String, value: Boolean): JsonLine = key(key).apply { sb.append(value) }

    /** Rounded to [decimals]; non-finite values become null. */
    fun put(key: String, value: Float, decimals: Int = 1): JsonLine = key(key).apply {
        if (value.isFinite()) sb.append(String.format(Locale.US, "%.${decimals}f", value)) else sb.append("null")
    }

    /** Inserts [json] (already valid JSON, such as an array) unquoted. */
    fun putRaw(key: String, json: String): JsonLine = key(key).apply { sb.append(json) }

    fun build(): String = sb.toString() + "}"

    private fun key(key: String): JsonLine {
        if (sb.length > 1) sb.append(',')
        quote(key)
        sb.append(':')
        return this
    }

    private fun quote(s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.US, "\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
