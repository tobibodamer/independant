package app.pendant.capture

import app.pendant.events.JsonLine
import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * The capture service's heartbeat protocol (pure, JVM-testable): request body, answer,
 * lease and the status line the agent writes beside the clips.
 */
object CaptureProtocol {

    const val INPUT_ID = "builtin"
    const val INPUT_KIND = "microphone"
    const val MODE_OFF = "off"
    const val MODE_CHUNKS = "chunks"
    const val WAIT_SECONDS = 25

    /** "http://host:7760/" or "host:7760" -> "http://host:7760/v1/capture/heartbeat?wait=25". Null when blank. */
    fun endpoint(baseUrl: String, wait: Int = WAIT_SECONDS): String? {
        var base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        if (!base.contains("://")) base = "http://$base"
        return "$base/v1/capture/heartbeat?wait=$wait"
    }

    /** What the phone's one input reports. */
    data class Input(
        val state: String,
        val doing: String,
        val assigned: String?,
        val battery: Float? = null,
    )

    /** [input] null says goodbye: `"inputs": []`. */
    fun requestBody(deviceTime: OffsetDateTime, link: String?, input: Input?): String {
        val obj = JsonLine().put("device_time", deviceTime.format(DEVICE_TIME))
        if (!link.isNullOrBlank()) obj.put("link", link)
        val inputs = if (input == null) "" else JsonLine()
            .put("id", INPUT_ID)
            .put("kind", INPUT_KIND)
            .put("state", input.state)
            .also { it.putRaw("modes", if (input.state == STATE_AVAILABLE) """["$MODE_CHUNKS"]""" else "[]") }
            .put("doing", input.doing)
            .also { b -> input.assigned?.let { b.put("assigned", it) } }
            .also { b -> input.battery?.let { b.put("battery", it, decimals = 2) } }
            .build()
        return obj.putRaw("inputs", "[$inputs]").build()
    }

    private val DEVICE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")

    const val STATE_AVAILABLE = "available"
    const val STATE_UNAVAILABLE = "unavailable"

    /** The answer, reduced to this agent's one input. [mode] is null when `assign` left it out. */
    data class Answer(val mode: String?, val validForSeconds: Int, val nextInSeconds: Int)

    fun parseAnswer(body: String): Answer? = try {
        val json = JSONObject(body)
        val mode = json.optJSONObject("assign")?.optJSONObject(INPUT_ID)?.optString("mode", "")?.ifEmpty { null }
        Answer(
            mode = mode,
            validForSeconds = json.optInt("valid_for", DEFAULT_LEASE_SECONDS).coerceIn(1, 3600),
            nextInSeconds = json.optInt("next_in", 30).coerceIn(1, 3600),
        )
    } catch (_: Exception) {
        null
    }

    const val DEFAULT_LEASE_SECONDS = 90

    /** One status line: `{"at": "2026-11-08T14:30:12+01:00", "state": "recording"}`. */
    fun statusLine(at: Instant, zone: ZoneId, state: String): String =
        JsonLine()
            .put("at", at.atZone(zone).toOffsetDateTime().truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            .put("state", state)
            .build()

    const val STATUS_RECORDING = "recording"
    const val STATUS_PAUSED = "paused"
    const val STATUS_UNAVAILABLE = "unavailable"
}

/**
 * How the phone's microphone stands, derived from what the engine knows. Pure so the
 * rules are tested: the user's pause and a lost microphone make the input `unavailable`
 * (otherwise the coordinator would keep the slot for a phone that cannot record).
 */
data class InputFacts(
    val running: Boolean,
    val permitted: Boolean,
    val userPaused: Boolean,
    val silenced: Boolean,
    /** The lease says `off`. */
    val remoteOff: Boolean,
) {
    val state: String
        get() = if (running && permitted && !userPaused && !silenced) CaptureProtocol.STATE_AVAILABLE else CaptureProtocol.STATE_UNAVAILABLE

    val doing: String
        get() = if (running && !userPaused && !remoteOff) CaptureProtocol.MODE_CHUNKS else CaptureProtocol.MODE_OFF

    /** For the status folder: hippo turns these into listening hours. */
    val status: String
        get() = when {
            !permitted || silenced -> CaptureProtocol.STATUS_UNAVAILABLE
            doing == CaptureProtocol.MODE_CHUNKS -> CaptureProtocol.STATUS_RECORDING
            else -> CaptureProtocol.STATUS_PAUSED
        }
}

/**
 * The assignment held for the input, kept on a clock that does not jump
 * (`SystemClock.elapsedRealtime()` in the app, a plain number in tests).
 */
class Lease {
    private var mode: String? = null
    private var expiresAtMs = 0L

    @Synchronized
    fun grant(mode: String?, validForMs: Long, nowMs: Long) {
        this.mode = mode
        expiresAtMs = nowMs + validForMs
    }

    /** The assigned mode while the lease holds, else null (no assignment, or it ran out). */
    @Synchronized
    fun held(nowMs: Long): String? = if (nowMs < expiresAtMs) mode else null

    @Synchronized
    fun remainingMs(nowMs: Long): Long = if (held(nowMs) != null) expiresAtMs - nowMs else 0L

    /** Only `off` stops the input. No lease, no assignment or any recording mode: record. */
    fun isOff(nowMs: Long): Boolean = held(nowMs) == CaptureProtocol.MODE_OFF
}
