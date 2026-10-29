package app.pendant.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pendant.PendantApp
import app.pendant.recording.RecordingController

@Composable
fun HomeScreen(app: PendantApp, state: RecordingController.State) {
    val context = LocalContext.current
    val stats by RecordingController.stats.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ------- Status card -------
        Card(colors = CardDefaults.cardColors()) {
            Column(Modifier.fillMaxWidth().padding(20.dp), Arrangement.spacedBy(8.dp)) {
                Text(
                    text = when (state) {
                        RecordingController.State.RECORDING ->
                            if (stats.voiceActive) "🎤 You're speaking" else "Listening…"
                        RecordingController.State.PAUSED -> "⏸ Paused"
                        RecordingController.State.STARTING -> "Starting…"
                        else -> "Off"
                    },
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = stats.sourceLabel.ifEmpty { "no capture yet" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
