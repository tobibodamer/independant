package app.pendant.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import app.pendant.PendantApp
import app.pendant.capture.CaptureController
import app.pendant.recording.RecordingController
import app.pendant.settings.BuiltinMicPref
import app.pendant.settings.RouteMode
import app.pendant.settings.Settings
import app.pendant.settings.Detector
import app.pendant.settings.StorageMode
import app.pendant.settings.StatusBarIndicator
import app.pendant.vad.EnergyVad
import app.pendant.vad.SileroVad
import app.pendant.vad.Vad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** SAF tree picker that opens directly inside the phone's public Music folder. */
class OpenTreeInMusic : ActivityResultContracts.OpenDocumentTree() {
    override fun createIntent(context: Context, input: Uri?): Intent {
        val intent = super.createIntent(context, input)
        input?.let { intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, it) }
        return intent
    }
}

@Composable
fun SettingsScreen(app: PendantApp) {
    val settings by app.settings.settings.collectAsState(initial = Settings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val musicInitialUri = remember {
        DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Music")
    }
    var customDirDisplay by remember { mutableStateOf(app.store.customDirSummary) }
    val treePicker = rememberLauncherForActivityResult(OpenTreeInMusic()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // The picker returned a grant we cannot persist; keep the previous mode.
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            app.settings.setCustomTreeUri(uri.toString())
            app.settings.setStorageMode(StorageMode.CUSTOM)
        }
    }
    LaunchedEffect(settings.customTreeUri, settings.storageMode) {
        if (settings.storageMode == StorageMode.CUSTOM) {
            withContext(Dispatchers.IO) {
                app.store.updateCustomDirSummary(settings.customTreeUri)
                app.store.customDirSummary?.let { customDirDisplay = it }
            }
        }
    }

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
            SwitchRow(
                "Phone's noise suppression",
                settings.phoneProcessing,
                "Off gives cleaner audio; on helps in loud places.",
            ) { scope.launch { app.settings.setPhoneProcessing(it) } }
            Note("${settings.sampleRate / 1000} kHz mono")
        }

        Section("Speech detection") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.detector == Detector.ENERGY,
                    onClick = { scope.launch { app.settings.setDetector(Detector.ENERGY) } },
                    label = { Text("Energy") },
                    modifier = Modifier.weight(1f),
                )
                FilterChip(
                    selected = settings.detector == Detector.SILERO,
                    onClick = { scope.launch { app.settings.setDetector(Detector.SILERO) } },
                    label = { Text("Silero (AI)") },
                    modifier = Modifier.weight(1f),
                )
            }
            Note(
                if (settings.detector == Detector.SILERO) {
                    "Speech-trained neural net, run on-device: bumps, music and traffic won't open clips — but neither will non-speech sounds."
                } else {
                    "Reacts to any sound above the threshold."
                },
            )
            if (settings.detector == Detector.ENERGY) {
                val sensLabels = mapOf(
                    "LOW" to "Strict",
                    "MEDIUM" to "Balanced",
                    "HIGH" to "Lenient",
                    "CUSTOM" to "Custom",
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EnergyVad.Sensitivity.entries.chunked(2).forEach { rowChips ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowChips.forEach { s ->
                                FilterChip(
                                    selected = settings.sensitivity == s,
                                    onClick = { scope.launch { app.settings.setSensitivity(s) } },
                                    enabled = settings.vadTracksFloor,
                                    label = { Text(sensLabels[s.name] ?: s.name, maxLines = 1) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
                Note(
                    if (settings.vadTracksFloor) "Chips: threshold margin above the adaptive noise floor." else {
                        "Fixed level ignores the noise floor — chips don't apply."
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.vadTracksFloor,
                        onClick = { scope.launch { app.settings.setVadTracksFloor(true) } },
                        label = { Text("Above floor") },
                        modifier = Modifier.weight(1f),
                    )
                    FilterChip(
                        selected = !settings.vadTracksFloor,
                        onClick = { scope.launch { app.settings.setVadTracksFloor(false) } },
                        label = { Text("Fixed level") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            MicLevelMeter(
                detector = settings.detector,
                tracksFloor = settings.vadTracksFloor,
                sensitivity = settings.sensitivity,
                customThresholdDb = settings.customThresholdDb,
                absoluteThresholdDb = settings.absoluteThresholdDb,
                sileroThresholdPct = settings.sileroThresholdPct,
                onCommitFloorThreshold = { db ->
                    scope.launch {
                        app.settings.setSensitivity(EnergyVad.Sensitivity.CUSTOM)
                        app.settings.setCustomThresholdDb(db)
                    }
                },
                onCommitAbsoluteThreshold = { db ->
                    scope.launch { app.settings.setAbsoluteThresholdDb(db) }
                },
                onCommitSileroThreshold = { pct ->
                    scope.launch { app.settings.setSileroThresholdPct(pct) }
                },
            )
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
            Note("24–32 kbps is excellent for speech")
        }

        Section("Battery & visibility") {
            SwitchRow(
                "Status bar indicator",
                settings.statusIndicator == StatusBarIndicator.SHOW_ICON,
                "Small recording icon while active.",
            ) { scope.launch { app.settings.setStatusIndicator(if (it) StatusBarIndicator.SHOW_ICON else StatusBarIndicator.HIDE_ICON) } }
            SwitchRow(
                "Keep CPU awake while recording",
                settings.keepCpuAwake,
                "Prevents dropped audio during deep sleep.",
            ) { scope.launch { app.settings.setKeepCpuAwake(it) } }
            SwitchRow(
                "Start recording after reboot",
                settings.startOnBoot,
                "Android may need one manual start after reboot (14+).",
            ) { scope.launch { app.settings.setStartOnBoot(it) } }
        }

        Section("Storage location") {
            ChoiceRow(
                title = "Where clips are saved",
                value = settings.storageMode.name,
                options = StorageMode.entries.map { it.name },
                labels = mapOf(
                    "DOCUMENTS" to "Documents folder (default)",
                    "MUSIC" to "Music folder",
                    "APP_PRIVATE" to "App-private folder",
                    "CUSTOM" to "Custom folder (SAF)",
                ),
                onSelected = { opt ->
                    when (opt) {
                        "DOCUMENTS" -> scope.launch { app.settings.setStorageMode(StorageMode.DOCUMENTS) }
                        "MUSIC" -> scope.launch { app.settings.setStorageMode(StorageMode.MUSIC) }
                        "APP_PRIVATE" -> scope.launch { app.settings.setStorageMode(StorageMode.APP_PRIVATE) }
                        "CUSTOM" ->
                            if (settings.customTreeUri.isNotBlank()) {
                                scope.launch { app.settings.setStorageMode(StorageMode.CUSTOM) }
                            } else {
                                treePicker.launch(musicInitialUri)
                            }
                    }
                },
            )
            Note(
                when (settings.storageMode) {
                    StorageMode.DOCUMENTS -> "Public Documents/Pendant — clips and capture log in one tree"
                    StorageMode.MUSIC -> "Public Music/Pendant, visible in any file app"
                    StorageMode.APP_PRIVATE -> "App-private storage"
                    StorageMode.CUSTOM -> "Custom picked folder"
                },
            )
            if (settings.storageMode == StorageMode.CUSTOM) {
                Row(
                    Modifier.fillMaxWidth().clickable { treePicker.launch(musicInitialUri) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Pick custom folder…", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            customDirDisplay ?: "not chosen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Note(
                "Path: " + when (settings.storageMode) {
                    StorageMode.DOCUMENTS -> "/storage/emulated/0/Documents/Pendant"
                    StorageMode.MUSIC -> "/storage/emulated/0/Music/Pendant"
                    StorageMode.APP_PRIVATE -> app.store.appPrivatePath()
                    StorageMode.CUSTOM -> customDirDisplay ?: "not chosen"
                } + " — applies to new clips",
            )
        }

        CaptureServiceSection(app, settings)

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
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors()) {
        Column(Modifier.fillMaxWidth().padding(16.dp), Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
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
            Text(title, style = MaterialTheme.typography.bodyLarge)
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
        Text(title, style = MaterialTheme.typography.bodyLarge)
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
                            Text(labels[opt] ?: opt, style = MaterialTheme.typography.bodyLarge)
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
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(valueText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Slider(
            value = value.coerceIn(min, max),
            onValueChange = onChange,
            valueRange = min..max,
            steps = if (step > 0) ((max - min) / step).toInt() - 1 else 0,
        )
    }
}

/** Absolute dB range the meter bar covers (quiet room ≈ 20, loud speech ≈ 50–60). */
/** Top of the level-meter scale, shared with the home-screen live waveform. */
internal const val METER_DB_MAX = 70f
private const val METER_RATE = 16_000

/**
 * Discord-style live input meter: the bar is the current level, the vertical
 * line is the speech threshold (energy detector only — the Silero detector is
 * probability-based and has no line), and the bar turns green when speech
 * would be detected.
 *
 * Two level sources:
 * - While the engine records, the meter reads the engine's published VAD values
 *   (RecordingController.meter) — the real detector, real mic route, no second
 *   AudioRecord (Samsung doesn't share the input across processes).
 * - While idle it runs its own 16 kHz AudioRecord with the detector the engine
 *   would use, so the marker and cue sit exactly where real capture triggers.
 *
 * Thresholds per detector: energy = floor-relative margin (5–25 dB), fixed
 * absolute level (10–60 dB) chosen with the Above floor / Fixed level chips;
 * Silero = speech probability (20–90 %).
 *
 * Dragging the slider commits CUSTOM / fixed / probability. Only a real drag
 * commits — a plain tap keeps the selected preset.
 */
@Composable
private fun MicLevelMeter(
    detector: Detector,
    tracksFloor: Boolean,
    sensitivity: EnergyVad.Sensitivity,
    customThresholdDb: Int,
    absoluteThresholdDb: Int,
    sileroThresholdPct: Int,
    onCommitFloorThreshold: (Int) -> Unit,
    onCommitAbsoluteThreshold: (Int) -> Unit,
    onCommitSileroThreshold: (Int) -> Unit,
) {
    val context = LocalContext.current
    val permitted = remember {
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
    }
    val engineState by RecordingController.state.collectAsState()
    // Lifecycle-bound so the engine stops producing meter values once the app leaves the screen.
    val engineMeter by RecordingController.meter.collectAsStateWithLifecycle()
    val engineIdle = engineState == RecordingController.State.IDLE
    val silero = detector == Detector.SILERO
    val activeVal = when {
        silero -> sileroThresholdPct
        tracksFloor -> if (sensitivity == EnergyVad.Sensitivity.CUSTOM) customThresholdDb else {
            sensitivity.enterDb.toInt()
        }
        else -> absoluteThresholdDb
    }

    var levelDb by remember { mutableStateOf(0f) }
    var thresholdAbsDb by remember { mutableStateOf(0f) }
    var speaking by remember { mutableStateOf(false) }
    var micError by remember { mutableStateOf(false) }
    var micErrorText by remember { mutableStateOf("") }

    LaunchedEffect(
        detector, tracksFloor, sensitivity, customThresholdDb, absoluteThresholdDb,
        sileroThresholdPct, permitted, engineIdle,
    ) {
        if (!permitted || !engineIdle) return@LaunchedEffect
        // Same detector the engine would use, so the cue is honest in idle mode.
        val vad: Vad = if (silero) {
            SileroVad(context, METER_RATE, sileroThresholdPct / 100f)
        } else {
            EnergyVad(
                METER_RATE,
                sensitivity,
                customThresholdDb,
                fixedEnterDb = if (tracksFloor) null else absoluteThresholdDb.toFloat(),
            )
        }
        val buf = ShortArray(METER_RATE / 50) // 20 ms frames, same cadence as capture
        var rec: AudioRecord? = null
        micError = false
        var smooth = Float.NaN
        var lastThreshold = Float.NaN
        thresholdAbsDb = if (tracksFloor) 0f else absoluteThresholdDb.toFloat()
        try {
            withContext(Dispatchers.IO) {
                val minBuf = AudioRecord.getMinBufferSize(
                    METER_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) throw IllegalStateException("no audio input available")
                rec = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(METER_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build(),
                    )
                    .setBufferSizeInBytes(maxOf(minBuf * 2, buf.size * 8))
                    .build()
                val r = requireNotNull(rec) { "no audio input available" }
                if (r.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("audio input unavailable")
                r.startRecording()
                // Mic warm-up: input can stream all-zero frames for ~1 s after start,
                // and the HAL throws in occasional all-zero frames around sound-trigger
                // session changes — so only CONSECUTIVE silence (5 s+) is an error.
                var zeros = 0
                while (isActive && RecordingController.state.value == RecordingController.State.IDLE) {
                    val n = r.read(buf, 0, buf.size)
                    if (n < buf.size) continue
                    if (buf.all { it == 0.toShort() }) {
                        if (++zeros > 250) throw IllegalStateException("microphone delivers silence")
                        continue
                    }
                    zeros = 0
                    vad.process(buf, n)
                    // Attack faster than release so the bar glides instead of flickering.
                    val e = vad.energyDb
                    smooth = if (smooth.isNaN()) e else smooth + (e - smooth) * (if (e > smooth) 0.45f else 0.12f)
                    val l = (smooth * 2).toInt() / 2f // 0.5 dB display resolution
                    if (l != levelDb) levelDb = l
                    val t = (vad.enterThresholdDb * 2).toInt() / 2f
                    if (t > 0f && t != lastThreshold) {
                        lastThreshold = t
                        thresholdAbsDb = t
                    }
                    if (speaking != vad.speechActive) speaking = vad.speechActive
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Effect restart / composition teardown — never an error, and the
            // finally block closes the recorder and idle detector.
            throw e
        } catch (e: Exception) {
            android.util.Log.w("PendantSettings", "level meter mic failed: ${e.message}", e)
            micError = true
            micErrorText = e.message ?: e::class.java.simpleName
        } finally {
            try {
                rec?.stop()
            } catch (_: Exception) {
            }
            try {
                rec?.release()
            } catch (_: Exception) {
            }
            try {
                vad.close()
            } catch (_: Throwable) {
            }
        }
    }

    // While the engine records, prefer its published values (real detector + mic route).
    val shownLevel = engineMeter?.levelDb ?: levelDb
    val shownThreshold = engineMeter?.thresholdDb ?: thresholdAbsDb
    val shownSpeaking = engineMeter?.speaking ?: speaking

    val range = when {
        silero -> 20f..90f
        tracksFloor -> 5f..25f
        else -> 10f..60f
    }
    var dragVal by remember(
        detector, tracksFloor, sensitivity, customThresholdDb, absoluteThresholdDb, sileroThresholdPct,
    ) { mutableStateOf(activeVal.toFloat()) }
    var moved by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(26.dp)) {
            val radius = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(color = Color(0xFF232733), cornerRadius = radius)
            val frac = (shownLevel / METER_DB_MAX).coerceIn(0f, 1f)
            drawRoundRect(
                color = if (shownSpeaking) Color(0xFF4CC38A) else Color(0xFF6FA0FF),
                size = Size(size.width * frac, size.height),
                cornerRadius = radius,
            )
            // Probability-based detectors (Silero) report <= 0 -> no threshold line.
            if (shownThreshold > 0f) {
                val mFrac = (shownThreshold / METER_DB_MAX).coerceIn(0f, 1f)
                drawLine(
                    color = Color(0xFFFF8A6B),
                    start = Offset(size.width * mFrac, 0f),
                    end = Offset(size.width * mFrac, size.height),
                    strokeWidth = 3.dp.toPx(),
                )
            }
        }
        Text(
            when {
                !permitted -> "Allow microphone access to see the live level"
                micError -> "Microphone not available right now" +
                    (if (micErrorText.isNotBlank()) " ($micErrorText)" else "")
                engineMeter == null && !engineIdle -> "Waiting for the recorder…"
                shownSpeaking -> "🎤 Speech detected"
                silero -> "Quiet — the AI records what it recognizes as speech"
                else -> "Quiet — talking should fill the bar past the line (" +
                    "%.0f".format(shownThreshold) + " dB)"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (shownSpeaking) Color(0xFF4CC38A) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            when {
                silero -> "Speech probability: ${dragVal.roundToInt()} %"
                tracksFloor -> "Speech threshold: +${dragVal.roundToInt()} dB above the noise floor"
                else -> "Speech threshold: ${dragVal.roundToInt()} dB (fixed level)"
            },
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        Slider(
            value = dragVal.coerceIn(range.start, range.endInclusive),
            onValueChange = { v ->
                moved = true
                dragVal = v
            },
            onValueChangeFinished = {
                if (moved) {
                    when {
                        silero -> onCommitSileroThreshold(dragVal.roundToInt())
                        tracksFloor -> onCommitFloorThreshold(dragVal.roundToInt())
                        else -> onCommitAbsoluteThreshold(dragVal.roundToInt())
                    }
                }
                moved = false
            },
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt() - 1,
        )
        Note(
            when {
                silero -> "Higher percentages need more confident speech. " +
                    "Applies immediately, also during recording."
                tracksFloor -> "Raise it until desk noise stays under the line while you talk over it. " +
                    "Applies immediately, also during recording."
                else -> "One level for every room — good when ambient noise sits just below speech. " +
                    "Applies immediately, also during recording."
            },
        )
    }
}

/**
 * Optional link to a capture service: this phone reports its microphone and follows the
 * service's assignment (it pauses while another device records). Off by default; nothing
 * leaves the phone until it is switched on and filled in.
 */
@Composable
private fun CaptureServiceSection(app: PendantApp, settings: Settings) {
    val scope = rememberCoroutineScope()
    val status by CaptureController.status.collectAsState()
    var url by remember(settings.captureUrl) { mutableStateOf(settings.captureUrl) }
    var token by remember(settings.captureToken) { mutableStateOf(settings.captureToken) }
    val dirty = url.trim() != settings.captureUrl || token.trim() != settings.captureToken

    Section("Capture service") {
        SwitchRow(
            "Connect to a capture service",
            settings.captureEnabled,
            "Reports this microphone to your other devices' coordinator and pauses while another one records.",
        ) { scope.launch { app.settings.setCaptureEnabled(it) } }
        if (settings.captureEnabled) {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Service URL") },
                placeholder = { Text("http://host:7760") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (dirty) {
                Button(onClick = { scope.launch { app.settings.setCaptureService(url, token) } }) { Text("Save") }
            }
            Note(
                when {
                    settings.captureUrl.isBlank() || settings.captureToken.isBlank() -> "Enter the service URL and this phone's token."
                    !RecordingController.state.collectAsState().value.let {
                        it == RecordingController.State.RECORDING || it == RecordingController.State.PAUSED
                    } -> "Connects once recording is started."
                    else -> when (val st = status) {
                        CaptureController.Status.Off -> "Not connected."
                        CaptureController.Status.Connecting -> "Connecting…"
                        is CaptureController.Status.Connected -> when (st.mode) {
                            "off" -> "Connected. Another device records, this phone is paused."
                            null -> "Connected. Nothing assigned, recording."
                            else -> "Connected. This phone records."
                        }
                        is CaptureController.Status.Failing -> "No answer (${st.reason}). Recording on its own until the service is back."
                    }
                },
            )
            Note("Clips and status lines still sync as before. The token is stored on this phone only.")
        }
    }
}
