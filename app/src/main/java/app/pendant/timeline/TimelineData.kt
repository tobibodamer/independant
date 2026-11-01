package app.pendant.timeline

import java.time.LocalDate

/** States of the 24-hour day strip. */
enum class TimelineState { LISTENING, MIC_LOST, PAUSED, DOWN }

/** One classified piece of a day: from ms-to-to ms are offsets since local midnight. */
data class TimelineSegment(val state: TimelineState, val fromMs: Long, val toMs: Long)

/** A gap (buffer loss) tick for the strip, at its wall-clock position. */
data class GapTick(val wallStartMs: Long, val ms: Long)

/** One clip that needs an analyzed extent (a listing row reduced to pure fields). */
data class ClipRef(val name: String, val durationMs: Long, val lastModifiedMs: Long)

/** Clip extent for the strip: [ClipStarts] owns the derivation precedence. */
data class ClipExtent(
    val startEpochMs: Long,
    val durationMs: Long,
    /** Wall-clock position of the start epoch within its day (skip on draw when off-day). */
    val wallStartMs: Long,
    /** Lineage of the derivation (see [ClipStarts]). */
    val source: Int,
)

/** Everything the Recordings header/strip draws for one local day. */
data class DayAnalysis(
    val date: LocalDate,
    val segments: List<TimelineSegment>,
    val listeningMs: Long,
    val micLostMs: Long,
    val pausedMs: Long,
    val downMs: Long,
    /** Speech within listening (Σ alive `audio_s` × `speech`), 0 when not derivable. */
    val speechMs: Long,
    /** True when at least one heartbeat carried `audio_s` together with a speech share. */
    val speechKnown: Boolean,
    /** Total buffer losses (`gap` events), ms. */
    val gapsMs: Long,
    val gapTicks: List<GapTick>,
    /** Per-clip extents keyed by [ClipStarts.keyOf]. */
    val clipExtents: Map<String, ClipExtent>,
)
