package app.pendant.ui

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.pendant.PendantApp
import app.pendant.settings.BuiltinMicPref
import app.pendant.settings.RouteMode
import app.pendant.settings.Settings
import app.pendant.settings.StatusBarIndicator
import app.pendant.vad.EnergyVad
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(app: PendantApp) {
    val settings by app.settings.settings.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Microphone source") {
            ChoiceRow(
                title = "Routing",
                value = settings.routeMode.name,
                options = RouteMode.entries.map { it.name },
                labels = mapOf(
                    "AUTOMATIC" to "Automatic (Bluetooth > headset > phone)",
                    "INTERNAL_ONLY" to "Phone microphones only",
                    "BLUETOOTH_PREFERRED" to "Bluetooth when connected, else phone",
                    "BLUETOOTH_ONLY" to "Bluetooth only",
                ),
                onSelected = { scope.launch { app.settings.setRouteMode(RouteMode.valueOf(it)) } },
            )
            ChoiceRow(
                title = "Built-in microphone",
                value = settings.builtinMic.name,
                options = BuiltinMicPref.entries.map { it.name },
                labels = mapOf(
                    "AUTO" to "Automatic",
                    "BOTTOM" to "Bottom mic",
                    "TOP" to "Top mic",
                    "BACK" to "Back mic",
                    "COMBINE" to "Combine two mics (mixed)",
                ),
                enabled = settings.routeMode != RouteMode.BLUETOOTH_ONLY,
                onSelected = { scope.launch { app.settings.setBuiltinMic(BuiltinMicPref.valueOf(it)) } },
            )
            Text(
                text = "Captured audio: ${settings.sampleRate / 1000} kHz mono.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow(
                "Phone's noise suppression & gain",
                settings.phoneProcessing,
                "Off gives cleaner audio for later transcription; on may help in loud places.",
            ) { scope.launch { app.settings.setPhoneProcessing(it) } }
        }

        Section("Speech detection") {
            val sensLabels = mapOf(
                "LOW" to "Only clear speech",
                "MEDIUM" to "Balanced",
                "HIGH" to "Catch quiet speech",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EnergyVad.Sensitivity.entries.forEach { s ->
                    FilterChip(
                        selected = settings.sensitivity == s,
                        onClick = { scope.launch { app.settings.setSensitivity(s) } },
                        label = { Text(sensLabels[s.name] ?: s.name) },
                    )
                }
            }
            LabeledSlider(
                label = "Keep recording while silent (after-speech hold)",
                valueText = "${settings.hangoverSec} s",
                value = settings.hangoverSec.toFloat(),
                min = 1f,
                max = 30f,
                step = 1,
            ) { scope.launch { app.settings.setHangoverSec(it.toInt()) } }
            LabeledSlider(
                label = "Pre-roll (audio kept before speech starts)",
                valueText = "${settings.prerollMs} ms",
                value = settings.prerollMs.toFloat(),
                min = 300f,
                max = 2000f,
                step = 4,
            ) { scope.launch { app.settings.setPrerollMs(it.toInt()) } }
            LabeledSlider(
                label = "Discard clips shorter than",
                valueText = "${settings.minSegmentMs} ms",
                value = settings.minSegmentMs.toFloat(),
                min = 0f,
                max = 3000f,
                step = 5,
            ) { scope.launch { app.settings.setMinSegmentMs(it.toLong()) } }
            LabeledSlider(
                label = "Rotate long continuous speech after",
                valueText = formatDuration(settings.maxSegmentSec * 1000),
                value = settings.maxSegmentSec.toFloat(),
                min = 120f,
                max = 3600f,
                step = 11,
            ) { scope.launch { app.settings.setMaxSegmentSec(it.toLong()) } }
        }

        Section("Audio quality") {
            ChoiceRow(
                title = "Bitrate",
                value = settings.bitrateBps.toString(),
                options = Settings.bitrateChoices().map { it.toString() },
                labels = Settings.bitrateChoices().associate { it.toString() to "${it / 1000} kbps" },
                onSelected = { scope.launch { app.settings.setBitrate(it.toInt()) } },
            )
            Text(
                "24–32 kbps Opus is excellent for speech. Higher rates help with music-heavy backgrounds.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section("Battery & visibility") {
            SwitchRow(
                "Status bar indicator",
                settings.statusIndicator == StatusBarIndicator.SHOW_ICON,
                "Shows a small recording icon while active.",
            ) { scope.launch { app.settings.setStatusIndicator(if (it) StatusBarIndicator.SHOW_ICON else StatusBarIndicator.HIDE_ICON) } }
            SwitchRow(
                "Keep CPU awake while recording",
                settings.keepCpuAwake,
                "Recommended. Prevents the device from dropping audio during deep sleep.",
            ) { scope.launch { app.settings.setKeepCpuAwake(it) } }
            SwitchRow(
                "Start recording after phone reboot",
                settings.startOnBoot,
                "On Android 14+ Android may still require one manual start after boot.",
            ) { scope.launch { app.settings.setStartOnBoot(it) } }
        }

        Section("Storage") {
            ChoiceRow(
                title = "Auto-delete after",
                value = settings.autoDeleteDays.toString(),
                options = Settings.autoDeleteChoices().map { it.toString() },
                labels = Settings.autoDeleteChoices().associate { it.toString() to if (it == 0) "Never" else "$it days" },
                onSelected = { scope.launch { app.settings.setAutoDeleteDays(it.toInt()) } },
            )
            ChoiceRow(
                title = "Storage limit",
                value = settings.maxStorageGb.toString(),
                options = Settings.maxStorageChoices().map { it.toString() },
                labels = Settings.maxStorageChoices().associate { it.toString() to if (it == 0) "Unlimited" else "$it GB" },
                onSelected = { scope.launch { app.settings.setMaxStorageGb(it.toInt()) } },
            )
            Text(
                text = "Saved to: ${app.store.dir.absolutePath}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            content()
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    subtitle: String = "",
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ChoiceRow(
    title: String,
    value: String,
    options: List<String>,
    labels: Map<String, String>,
    enabled: Boolean = true,
    onSelected: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        Text(
            labels[value] ?: value,
            style = MaterialTheme.typography.bodySmall,
            color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Close") }
            },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { opt ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelected(opt); open = false }.padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = opt == value, onClick = { onSelected(opt); open = false })
                            Text(labels[opt] ?: opt, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    step: Int,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            steps = if (step > 0) ((max - min) / step).toInt() - 1 else 0,
        )
    }
}
