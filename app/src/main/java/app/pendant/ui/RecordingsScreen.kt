package app.pendant.ui

import android.content.Intent
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.pendant.PendantApp
import app.pendant.storage.SegmentInfo
import app.pendant.storage.SegmentStore
import app.pendant.timeline.ClipStarts
import app.pendant.timeline.ClipRef
import app.pendant.timeline.DayAnalysis
import app.pendant.timeline.DayEvents
import app.pendant.timeline.DayNames
import app.pendant.timeline.TimelineAnalysis
import app.pendant.timeline.TimelineState
import app.pendant.timeline.TimelineSummary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordingsScreen(app: PendantApp) {
    val context = LocalContext.current
    val store = app.store
    // Shared source of truth: painting straight from the flow costs zero provider
    // calls, even on a cold start (the persisted index feeds it). Refreshes come
    // from app start, clip finalize/delete, MediaStore churn and the refresh button.
    val recordings by app.recordings.clips.collectAsState()
    // Clip finalize/delete append to the capture log, and the engine re-lists the
    // clips then; hook the shared-flow refresh into the reader's cheap stat instead
    // of a second trigger path.
    LaunchedEffect(recordings) { app.dayEvents.refreshToday() }
    val zone = remember { ZoneId.systemDefault() }
    // Day groups paint immediately from name-derived clip starts; event-derived
    // bits (timeline strip, speech share) follow per day once the log loads.
    val groups = remember(recordings, zone) { buildDayGroups(recordings, zone) }
    var playingKey by remember { mutableStateOf<String?>(null) }
    val player = remember { MediaPlayer() }

    fun stopPlayback() {
        if (playingKey != null) {
            playingKey = null
            try {
                player.stop()
            } catch (_: Exception) {
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                player.release()
            } catch (_: Exception) {
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${recordings.size} clips · ${formatBytes(recordings.sumOf { it.sizeBytes })}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(vertical = 10.dp),
            )
            IconButton(onClick = { app.recordings.refresh() }) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
        }
        if (recordings.isEmpty()) {
            Text(
                "Nothing here yet. Clips appear as you speak.",
                style = MaterialTheme.typography.bodyMedium,
            )
            return@Column
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
            for (group in groups) {
                stickyHeader(key = "day-${group.date}") {
                    DayHeader(app.dayEvents, group, zone)
                }
                items(group.clips, key = { it.key }) { seg ->
                    Card(colors = CardDefaults.cardColors()) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = {
                                if (playingKey == seg.key) {
                                    stopPlayback()
                                } else {
                                    try {
                                        player.reset()
                                        player.setDataSource(context, seg.playbackUri())
                                        player.prepare()
                                        player.start()
                                        playingKey = seg.key
                                        player.setOnCompletionListener {
                                            playingKey = null
                                        }
                                    } catch (_: Exception) {
                                        playingKey = null
                                    }
                                }
                            }) {
                                if (playingKey == seg.key) PauseBars() else {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                                }
                            }
                            Column(Modifier.weight(1f)) {
                                Text(seg.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    formatDuration(seg.durationMs) + " · " + formatBytes(seg.sizeBytes),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            StartChip(seg, zone)
                            IconButton(onClick = {
                                if (playingKey == seg.key) stopPlayback()
                                share(context, seg)
                            }) {
                                Icon(Icons.Filled.Share, contentDescription = "Share")
                            }
                            IconButton(onClick = {
                                stopPlayback()
                                store.delete(seg)
                                app.recordings.refresh()
                            }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One per-day group: clips of that local day, newest first. */
private data class DayGroup(val date: LocalDate, val clips: List<SegmentInfo>)

/**
 * Groups clips by the local day of their derived start (tier 2/3; the log is not
 * loaded yet — match with [ClipStarts]). Days and clips newest first.
 */
private fun buildDayGroups(clips: List<SegmentInfo>, zone: ZoneId): List<DayGroup> {
    class Clipped(val date: LocalDate, val startMs: Long, val seg: SegmentInfo)
    val derived = clips.map { seg ->
        val start = ClipStarts.derive(seg.name, null, zone, seg.lastModifiedMs, seg.durationMs)
        Clipped(
            Instant.ofEpochMilli(start.epochMs.coerceIn(0, epochCeiling)).atZone(zone).toLocalDate(),
            start.epochMs,
            seg,
        )
    }
    return derived
        .groupBy { it.date }
        .map { (date, list) ->
            DayGroup(date, list.sortedByDescending { it.startMs }.map { it.seg })
        }
        .sortedByDescending { it.date }
}

private val epochCeiling: Long = 4_102_444_800_000L // ~2100

/**
 * Per-day sticky header: title + counts immediately; timeline strip and listening
 * summary appear when the day's capture log finishes loading.
 */
@Composable
private fun DayHeader(reader: DayEvents, group: DayGroup, zone: ZoneId) {
    // Loading the day is keyed off composition, not scroll: the reader's stat makes
    // repeat requests (unseen days scroll by) cheap.
    LaunchedEffect(group.date) { reader.request(group.date) }
    val loaded by reader.days.collectAsState()
    val events = loaded[group.date]
    val analysis = remember(group.date, events, group.clips) {
        events?.takeIf { it.isNotEmpty() }?.let { ev ->
            TimelineAnalysis.analyze(
                date = group.date,
                events = ev,
                clips = group.clips.map { ClipRef(it.name, it.durationMs, it.lastModifiedMs) },
                zone = zone,
                nowMs = System.currentTimeMillis(),
            )
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background) // clips scroll under the sticky header
            .padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                DayNames.title(group.date, LocalDate.now(), Locale.getDefault()),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Text(
                "${group.clips.size} clips · ${group.clips.sumOf { it.durationMs } / 60_000} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            // Loaded but no log data (pruned/absent file): keep the header compact.
            events != null && events.isEmpty() -> Unit
            events != null && analysis != null -> {
                TimelineSummary.text(analysis)?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                DayTimelineStrip(
                    analysis,
                    Modifier.fillMaxWidth().padding(top = 4.dp).height(14.dp),
                )
            }
            else -> Text(
                "—", // subtle loading dash while the day file parses
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

private val MIC_LOST_COLOR = Color(0xFFFFA55E)

/** 24-hour strip: classified state fills, gap ticks, clip extents, faint hour grid. */
@Composable
private fun DayTimelineStrip(analysis: DayAnalysis, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val w = size.width
        fun xOf(ms: Long): Float = ms.toFloat() / DAY_WIDTH_MS * w
        for (seg in analysis.segments) {
            val color = when (seg.state) {
                TimelineState.LISTENING -> colors.primary.copy(alpha = 0.35f)
                TimelineState.MIC_LOST -> MIC_LOST_COLOR.copy(alpha = 0.8f)
                TimelineState.PAUSED -> colors.primary.copy(alpha = 0.15f)
                TimelineState.DOWN -> null // not captured: stays background, grid shows
            } ?: continue
            drawRect(
                color,
                topLeft = Offset(xOf(seg.fromMs), 0f),
                size = Size(xOf(seg.toMs) - xOf(seg.fromMs), size.height),
            )
        }
        // Buffer losses: brief darker ticks inside the listening regions.
        for (tick in analysis.gapTicks) {
            val gx = xOf(tick.wallStartMs).coerceIn(0f, w - 2f)
            drawRect(
                colors.onSurface.copy(alpha = 0.5f),
                topLeft = Offset(gx, 0f),
                size = Size(2f, size.height),
            )
        }
        // Clips: slightly brighter solid marks over the listening they cover.
        for (extent in analysis.clipExtents.values) {
            if (extent.wallStartMs < 0) continue
            val from = xOf(extent.wallStartMs)
            val to = (from + extent.durationMs.toFloat() / DAY_WIDTH_MS * w).coerceAtMost(w)
            drawRect(colors.primary, topLeft = Offset(from, 0f), size = Size(to - from, size.height))
        }
        val line = colors.onSurface.copy(alpha = 0.10f)
        for (h in 0..24) {
            val gx = h * w / 24f
            drawLine(line, Offset(gx, 0f), Offset(gx, size.height), strokeWidth = 1f)
        }
    }
}

private const val DAY_WIDTH_MS = 86_400_000f

/** Tiny start-time chip (nice when names hide the clock: Music rows have no suffix). */
@Composable
private fun StartChip(seg: SegmentInfo, zone: ZoneId) {
    val start = remember(seg.name, seg.lastModifiedMs, seg.durationMs) {
        ClipStarts.derive(seg.name, null, zone, seg.lastModifiedMs, seg.durationMs)
    }
    val valid = start.epochMs in 0..epochCeiling
    if (!valid) return
    Text(
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
            .format(Instant.ofEpochMilli(if (start.epochMs < 0) 0L else start.epochMs)),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val nameFmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())

/** Two-bar pause glyph (material-icons-core has no Pause icon). */
@Composable
private fun PauseBars() {
    val tint = androidx.compose.material3.LocalContentColor.current
    Row(
        Modifier.height(20.dp).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(6.dp).height(20.dp).background(tint))
        Box(Modifier.width(6.dp).height(20.dp).background(tint))
    }
}

private fun formatTime(ms: Long): String = nameFmt.format(Date(ms))

private fun share(context: android.content.Context, seg: SegmentInfo) {
    try {
        // FileProvider cannot share remote content URIs, so MediaStore/SAF clips are
        // shared via their own content URI; plain files via FileProvider.
        val uri = seg.shareContentUri() ?: FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            seg.backingFile ?: return,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Share recording"))
    } catch (_: Exception) {
    }
}
