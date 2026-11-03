package app.pendant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pendant.PendantApp
import app.pendant.recording.RecordingController

@Composable
fun HomeScreen(app: PendantApp, state: RecordingController.State) {
    val context = LocalContext.current
    val stats by RecordingController.stats.collectAsState()
    // Lifecycle-bound: collecting these is what tells the engine a screen is
    // watching, so the live values (and the more expensive per-frame reads behind
    // them) only run while the app is actually on screen.
    val meter by RecordingController.meter.collectAsStateWithLifecycle()
    val liveSegment by RecordingController.liveSegment.collectAsStateWithLifecycle()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ------- Status card -------
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), Arrangement.spacedBy(8.dp)) {
                // Keep the segment open past a short pause. While the file stays
                // open, more speech continues the same clip instead of starting a
                // new one — show that as its own state rather than "Listening…".
                val headline = when (state) {
                    RecordingController.State.RECORDING -> when {
                        stats.voiceActive -> "🎤 You're speaking"
                        liveSegment != null -> "⏳ Holding your clip…"
                        else -> "Listening…"
                    }
                    RecordingController.State.PAUSED -> "⏸ Paused"
                    RecordingController.State.STARTING -> "Starting…"
                    else -> "Off"
                }
                AnimatedContent(
                    targetState = headline,
                    transitionSpec = {
                        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 4 }) togetherWith
                            (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 4 })
                    },
                    label = "statusHeadline",
                ) { text ->
                    Text(text, style = MaterialTheme.typography.headlineSmall)
                }
                Text(
                    text = stats.sourceLabel.ifEmpty { "no capture yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // Keep the last live value so the indicator fades out with its
                // final state instead of vanishing the instant the clip closes.
                var lastLive by remember { mutableStateOf<RecordingController.LiveSegment?>(null) }
                SideEffect { if (liveSegment != null) lastLive = liveSegment }
                AnimatedVisibility(
                    // Visibility follows the live flow; the content renders from
                    // lastLive so the exit animation fades out the final state.
                    visible = state == RecordingController.State.RECORDING && liveSegment != null,
                    enter = fadeIn(tween(200)) + expandVertically(tween(200)),
                    exit = fadeOut(tween(200)) + shrinkVertically(tween(200)),
                ) {
                    LiveClipIndicator(
                        live = lastLive,
                        meter = meter,
                        holding = liveSegment != null && !stats.voiceActive,
                    )
                }
            }
        }

        // ------- Big controls -------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val (label, action) = when (state) {
                RecordingController.State.RECORDING -> "Pause" to { RecordingController.pause(context) }
                RecordingController.State.PAUSED -> "Resume" to { RecordingController.resume(context) }
                RecordingController.State.STARTING -> "Starting…" to { }
                else -> "Start recording" to { RecordingController.start(context) }
            }
            Button(onClick = action, modifier = Modifier.weight(1f).height(64.dp), enabled = state != RecordingController.State.STARTING) {
                Text(label)
            }
            if (state == RecordingController.State.RECORDING || state == RecordingController.State.PAUSED) {
                OutlinedButton(
                    onClick = { RecordingController.stop(context) },
                    modifier = Modifier.weight(0.6f).height(64.dp),
                ) { Text("Stop") }
            }
        }

        Text(
            "Tip: pull down quick settings and add the “Pendant pause” tile for a one-tap pause from any screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ------- Today's stats -------
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(12.dp)) {
            StatCard("Clips today", stats.segmentsToday.toString(), Modifier.weight(1f))
            StatCard("Speech today", formatDuration(stats.secondsToday * 1000), Modifier.weight(1f))
            StatCard("Storage", formatBytes(stats.totalBytes), Modifier.weight(1f))
        }

        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
            Card(colors = CardDefaults.cardColors()) {
                Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                    Text("Battery optimization", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "For true 24/7 background operation, allow Pendant to ignore battery optimization.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    FilledTonalButton(onClick = { requestBatteryIgnore(context) }) {
                        Text("Allow unrestricted battery use")
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier, colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(14.dp)) {
            Text(value, style = MaterialTheme.typography.titleMedium)
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Bars of the live waveform, same palette as the settings-screen level meter. */
private val TrackColor = Color(0xFF232733)
private val SpeakColor = Color(0xFF4CC38A)
private val ListenColor = Color(0xFF6FA0FF)
private val OverrunColor = Color(0xFFFF8A6B)

private const val WAVE_BARS = 28

/**
 * Live view of the clip currently being recorded: a scrolling level waveform and
 * the clip's length so far.
 *
 * The thin bar under the timer has two meanings:
 *  - while speaking, it fills over the minimum keep length — once full, this
 *    utterance is guaranteed to become a file instead of being silently
 *    discarded as "too short";
 *  - while holding (pause inside the hangover window), it counts down the
 *    silence that remains before the clip closes. Green means the clip is
 *    already long enough to keep; orange means it would still be discarded.
 */
@Composable
private fun LiveClipIndicator(
    live: RecordingController.LiveSegment?,
    meter: RecordingController.MicMeter?,
    holding: Boolean,
) {
    live ?: return
    val wave = remember { mutableStateListOf<Float>() }
    SideEffect {
        val level = meter?.levelDb ?: return@SideEffect
        if (!holding) {
            wave.add((level / METER_DB_MAX).coerceIn(0f, 1f))
            while (wave.size > WAVE_BARS) wave.removeAt(0)
        }
    }
    val waveAlpha by animateFloatAsState(if (holding) 0.35f else 1f, label = "waveAlpha")
    val textAlpha by animateFloatAsState(if (holding) 0.55f else 1f, label = "textAlpha")
    val keep = (live.speechMs.toFloat() / live.minSegmentMs).coerceIn(0f, 1f)
    val barTarget = if (holding) {
        ((live.hangoverMs - live.silenceMs).toFloat() / live.hangoverMs).coerceIn(0f, 1f)
    } else {
        keep
    }
    val barFrac by animateFloatAsState(barTarget, label = "barFrac")
    val barColor = when {
        keep >= 1f -> SpeakColor
        holding -> OverrunColor
        else -> ListenColor
    }
    Row(
        Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = if (holding) {
                    "Clip still open, ${live.durationMs / 1000} seconds, waiting for more speech"
                } else {
                    "Recording clip, ${live.durationMs / 1000} seconds so far"
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.weight(1f).height(30.dp).graphicsLayer { alpha = waveAlpha }) {
            val step = size.width / WAVE_BARS
            val barWidth = step * 0.6f
            // Newest sample at the right edge; older bars scroll left.
            wave.forEachIndexed { i, v ->
                val x = size.width - (wave.size - i) * step
                val h = maxOf(v * size.height, 2.dp.toPx())
                drawRoundRect(
                    color = if (meter?.speaking == true) SpeakColor else ListenColor,
                    topLeft = Offset(x + (step - barWidth) / 2, (size.height - h) / 2),
                    size = Size(barWidth, h),
                    cornerRadius = CornerRadius(barWidth / 2, barWidth / 2),
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                formatClipLength(live.durationMs),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
            )
            Canvas(Modifier.padding(top = 5.dp).width(56.dp).height(4.dp)) {
                drawRoundRect(color = TrackColor, cornerRadius = CornerRadius(size.height / 2, size.height / 2))
                drawRoundRect(
                    color = barColor,
                    size = Size(size.width * barFrac, size.height),
                    cornerRadius = CornerRadius(size.height / 2, size.height / 2),
                )
            }
        }
    }
}

/** m:ss tick for the live clip timer. */
private fun formatClipLength(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

private fun requestBatteryIgnore(context: Context) {
    try {
        val i = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
        context.startActivity(i)
    } catch (_: Exception) {
        try {
            context.startActivity(Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> "%d h %02d min".format(h, m)
        m > 0 -> "%d min %02d s".format(m, s)
        else -> "%d s".format(s)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1 shl 30 -> "%.1f GB".format(bytes.toDouble() / (1 shl 30))
    bytes >= 1 shl 20 -> "%.1f MB".format(bytes.toDouble() / (1 shl 20))
    bytes >= 1 shl 10 -> "%d KB".format(bytes / (1 shl 10))
    else -> "$bytes B"
}
