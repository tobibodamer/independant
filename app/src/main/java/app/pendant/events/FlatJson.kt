package app.pendant.events

/**
 * Minimal parser for the flat JSON objects [JsonLine] writes (pure, JVM-testable).
 * Same per-line shape as the index snapshot; keys are plain strings, values are
 * strings or numbers — exactly what [JsonLine.build] can produce, nothing more.
 */
object FlatJson {

    /** Parses one flat object line; null for foreign or corrupt lines. */
    fun parse(line: String): Map<String, String>? {
        if (line.length < 2 || line.first() != '{' || line.last() != '}') return null
        val out = HashMap<String, String>(8)
        var i = 1
        while (i < line.length - 1) {
            while (i < line.length - 1 && line[i] == ',') i++
            if (i >= line.length - 1) return null
            if (line[i] != '"') return null
            val key = readString(line, i) ?: return null
            i = key.second
            if (i >= line.length - 1 || line[i] != ':') return null
            val value = readValue(line, i + 1) ?: return null
            out[key.first] = value.first
            i = value.second
        }
        return out
    }

    /** Reads a quoted string at [from]; returns (unescaped text, index after the closing quote). */
    private fun readString(s: String, from: Int): Pair<String, Int>? {
        val sb = StringBuilder()
        var i = from + 1
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> return sb.toString() to (i + 1)
                c == '\\' -> {
                    if (i + 1 >= s.length) return null
                    when (val e = s[i + 1]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (i + 6 > s.length) return null
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            i += 4 // the loop's shared advance below adds the last two
                        }
                        else -> return null
                    }
                    i += 2
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        return null
    }

    /** Reads a string or number value at [from]; returns (value text, index after it). */
    private fun readValue(s: String, from: Int): Pair<String, Int>? {
        if (from >= s.length) return null
        return if (s[from] == '"') {
            readString(s, from)
        } else {
            var i = from
            while (i < s.length && s[i] != ',' && s[i] != '}') i++
            val raw = s.substring(from, i)
            if (raw.isEmpty() || raw.any { !it.isDigit() && it != '-' }) return null
            raw to i
        }
    }
}
