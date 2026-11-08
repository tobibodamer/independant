package app.pendant.capture

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

class CaptureProtocolTest {

    @Test
    fun `endpoint adds scheme, drops trailing slash and carries wait`() {
        assertEquals("http://nas:7760/v1/capture/heartbeat?wait=25", CaptureProtocol.endpoint(" nas:7760/ "))
        assertEquals("https://x.y/v1/capture/heartbeat?wait=0", CaptureProtocol.endpoint("https://x.y", wait = 0))
        assertNull(CaptureProtocol.endpoint("  "))
    }

    @Test
    fun `request lists the builtin microphone`() {
        val body = CaptureProtocol.requestBody(
            OffsetDateTime.parse("2026-11-08T14:30:12.420+01:00"),
            "wifi",
            CaptureProtocol.Input("available", "chunks", assigned = "chunks", battery = 0.5f),
        )
        val json = JSONObject(body)
        assertEquals("2026-11-08T14:30:12.420+01:00", json.getString("device_time"))
        assertEquals("wifi", json.getString("link"))
        val input = json.getJSONArray("inputs").getJSONObject(0)
        assertEquals("builtin", input.getString("id"))
        assertEquals("microphone", input.getString("kind"))
        assertEquals("available", input.getString("state"))
        assertEquals("chunks", input.getJSONArray("modes").getString(0))
        assertEquals("chunks", input.getString("doing"))
        assertEquals("chunks", input.getString("assigned"))
        assertEquals(0.5, input.getDouble("battery"), 1e-6)
    }

    @Test
    fun `unavailable input offers no modes and leaves out what it does not hold`() {
        val input = JSONObject(
            CaptureProtocol.requestBody(OffsetDateTime.now(), null, CaptureProtocol.Input("unavailable", "off", null)),
        ).getJSONArray("inputs").getJSONObject(0)
        assertEquals(0, input.getJSONArray("modes").length())
        assertFalse(input.has("assigned"))
        assertFalse(input.has("battery"))
    }

    @Test
    fun `goodbye sends no inputs`() {
        val json = JSONObject(CaptureProtocol.requestBody(OffsetDateTime.now(), null, null))
        assertEquals(0, json.getJSONArray("inputs").length())
    }

    @Test
    fun `answer is reduced to the builtin input`() {
        val a = CaptureProtocol.parseAnswer(
            """{"assign":{"desk":{"mode":"chunks"},"builtin":{"mode":"off","extra":1}},"valid_for":90,"next_in":5,"server_time":"x"}""",
        )!!
        assertEquals("off", a.mode)
        assertEquals(90, a.validForSeconds)
        assertEquals(5, a.nextInSeconds)
    }

    @Test
    fun `input left out of assign is unassigned, garbage is no answer`() {
        assertNull(CaptureProtocol.parseAnswer("""{"assign":{},"valid_for":30,"next_in":30}""")!!.mode)
        assertEquals(90, CaptureProtocol.parseAnswer("{}")!!.validForSeconds)
        assertNull(CaptureProtocol.parseAnswer("<html>"))
    }

    @Test
    fun `status line carries local time with offset`() {
        val line = CaptureProtocol.statusLine(Instant.parse("2026-11-08T13:30:12.900Z"), ZoneId.of("Europe/Berlin"), "paused")
        assertEquals("""{"at":"2026-11-08T14:30:12+01:00","state":"paused"}""", line)
    }

    @Test
    fun `lease holds until it runs out, then the input records`() {
        val lease = Lease()
        assertFalse(lease.isOff(0))
        lease.grant("off", 90_000, nowMs = 1_000)
        assertTrue(lease.isOff(90_999))
        assertEquals("off", lease.held(90_999))
        assertFalse(lease.isOff(91_000))
        assertNull(lease.held(91_000))
    }

    @Test
    fun `only off stops the input`() {
        val lease = Lease()
        lease.grant("chunks", 90_000, 0)
        assertFalse(lease.isOff(1))
        lease.grant(null, 90_000, 0)
        assertFalse(lease.isOff(1))
        lease.grant("stream", 90_000, 0)
        assertFalse(lease.isOff(1))
    }

    @Test
    fun `user pause and lost microphone make the input unavailable`() {
        val base = InputFacts(running = true, permitted = true, userPaused = false, silenced = false, remoteOff = false)
        assertEquals("available", base.state)
        assertEquals("chunks", base.doing)
        assertEquals("recording", base.status)

        val remote = base.copy(remoteOff = true)
        assertEquals("available", remote.state)
        assertEquals("off", remote.doing)
        assertEquals("paused", remote.status)

        val user = base.copy(userPaused = true)
        assertEquals("unavailable", user.state)
        assertEquals("off", user.doing)
        assertEquals("paused", user.status)

        val silenced = base.copy(silenced = true)
        assertEquals("unavailable", silenced.state)
        assertEquals("unavailable", silenced.status)
        assertEquals("unavailable", base.copy(permitted = false).state)
    }
}
