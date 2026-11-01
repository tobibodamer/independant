package app.pendant.events

import org.junit.Assert.assertEquals
import org.junit.Test

class JsonLineTest {

    @Test
    fun `builds a flat object with typed values`() {
        val line = JsonLine()
            .put("t", 1761800000123L)
            .put("e", "alive")
            .put("level_db", -48.25f)
            .put("speech", 0.125f, decimals = 2)
            .put("open", true)
            .put("mic", null)
            .build()
        assertEquals("""{"t":1761800000123,"e":"alive","level_db":-48.3,"speech":0.13,"open":true,"mic":null}""", line)
    }

    @Test
    fun `escapes strings and drops non-finite numbers`() {
        val line = JsonLine()
            .put("msg", "quote \" back \\ new\nline \u0001")
            .put("floor_db", Float.NaN)
            .build()
        assertEquals("""{"msg":"quote \" back \\ new\nline \u0001","floor_db":null}""", line)
    }
}
