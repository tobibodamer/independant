package app.pendant.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pendant.vad.EnergyVad
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "pendant_settings")

enum class RouteMode {
    /** Use the best available input, switching automatically: Bluetooth > wired headset > built-in. */
    AUTOMATIC,
    /** Always the phone's own microphone(s). */
    INTERNAL_ONLY,
    /** Use a Bluetooth mic whenever one is connected, fall back to built-in. */
    BLUETOOTH_PREFERRED,
    /** Only ever record from a Bluetooth mic; idle while none is connected. */
    BLUETOOTH_ONLY,
}

enum class BuiltinMicPref {
    /** Let Android pick (usually the bottom mic). */
    AUTO,
    BOTTOM,
    TOP,
    BACK,
    /** Capture from two built-in mics at once and mix them. */
    COMBINE,
}

enum class StatusBarIndicator { SHOW_ICON, HIDE_ICON }

/** Which detector decides what counts as speech. */
enum class Detector {
    /** Adaptive energy threshold — cheap, reacts to any sound. */
    ENERGY,
    /** Silero ONNX neural net — speech-trained; ignores bumps, music, traffic. */
    SILERO,
}

/** Where finished speech segments land. Applies to NEW segments; an open recording keeps its sink. */
enum class StorageMode {
    /** Public Music folder via MediaStore (RELATIVE_PATH "Music/Pendant"), no permissions needed. */
    MUSIC,
    /** App-private external dir (Android/data/…/files/recordings), always available. */
    APP_PRIVATE,
    /** User-picked tree via SAF (OPEN_DOCUMENT_TREE), written into <tree>/Pendant. */
    CUSTOM,
}

data class Settings(
    val routeMode: RouteMode = RouteMode.BLUETOOTH_PREFERRED,
    val builtinMic: BuiltinMicPref = BuiltinMicPref.AUTO,
    val preferredBtAddress: String = "",
    // The opus-encoder wedge occurs at both 16 kHz and 48 kHz (it is a defect of
    // the sync-mode path in c2.android.opus.encoder, not a sample-rate problem),
    // so keep the cheaper default; the settings screen still offers 48 kHz.
    val sampleRate: Int = 16_000,
    val bitrateBps: Int = 24_000,
    val detector: Detector = Detector.ENERGY,
    val sensitivity: EnergyVad.Sensitivity = EnergyVad.Sensitivity.MEDIUM,
    /** Enter margin in dB above the adaptive floor used when sensitivity == CUSTOM. */
    val customThresholdDb: Int = EnergyVad.Sensitivity.MEDIUM.enterDb.toInt(),
    /** true: trigger relative to the adaptive noise floor. false: one fixed absolute level. */
    val vadTracksFloor: Boolean = true,
    /** Absolute enter level in dB used when vadTracksFloor == false. */
    val absoluteThresholdDb: Int = 40,
    /** Enter probability % for the Silero detector (20–90). */
    val sileroThresholdPct: Int = 50,
    val hangoverSec: Int = 5,
    val prerollMs: Int = 1000,
    val minSegmentMs: Long = 800,
    val maxSegmentSec: Long = 900,
    val statusIndicator: StatusBarIndicator = StatusBarIndicator.SHOW_ICON,
    val phoneProcessing: Boolean = false,
    val keepCpuAwake: Boolean = false,
    val startOnBoot: Boolean = false,
    val autoDeleteDays: Int = 0,
    val maxStorageGb: Int = 0,
    val storageMode: StorageMode = StorageMode.MUSIC,
    val customTreeUri: String = "",
) {
    companion object {
        fun hangoverChoices() = intArrayOf(3, 4, 5, 6, 7, 8, 10, 15, 20, 30)
        fun bitrateChoices() = intArrayOf(16_000, 24_000, 32_000, 48_000)
        fun sampleRateChoices() = intArrayOf(16_000, 48_000)
        fun autoDeleteChoices() = intArrayOf(0, 7, 30, 90)
        fun maxStorageChoices() = intArrayOf(0, 1, 2, 5, 10, 20)
    }
}

class PendantSettings(private val context: Context) {

    private object Keys {
        val routeMode = stringPreferencesKey("route_mode")
        val builtinMic = stringPreferencesKey("builtin_mic")
        val preferredBtAddress = stringPreferencesKey("preferred_bt_address")
        val sampleRate = intPreferencesKey("sample_rate")
        val bitrateBps = intPreferencesKey("bitrate_bps")
        val detector = stringPreferencesKey("vad_detector")
        val sensitivity = stringPreferencesKey("sensitivity")
        val customThresholdDb = intPreferencesKey("custom_threshold_db")
        val vadTracksFloor = intPreferencesKey("vad_tracks_floor")
        val absoluteThresholdDb = intPreferencesKey("absolute_threshold_db")
        val sileroThresholdPct = intPreferencesKey("silero_threshold_pct")
        val hangoverSec = intPreferencesKey("hangover_sec")
        val prerollMs = intPreferencesKey("preroll_ms")
        val minSegmentMs = longPreferencesKey("min_segment_ms")
        val maxSegmentSec = longPreferencesKey("max_segment_sec")
        val statusIndicator = stringPreferencesKey("status_indicator")
        val phoneProcessing = intPreferencesKey("phone_processing")
        val keepCpuAwake = intPreferencesKey("keep_cpu_awake")
        val startOnBoot = intPreferencesKey("start_on_boot")
        val autoDeleteDays = intPreferencesKey("auto_delete_days")
        val maxStorageGb = intPreferencesKey("max_storage_gb")
        val storageMode = stringPreferencesKey("storage_mode")
        val customTreeUri = stringPreferencesKey("custom_tree_uri")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        Settings(
            routeMode = p[Keys.routeMode]?.let { runCatching { RouteMode.valueOf(it) }.getOrNull() } ?: Settings().routeMode,
            builtinMic = p[Keys.builtinMic]?.let { runCatching { BuiltinMicPref.valueOf(it) }.getOrNull() } ?: Settings().builtinMic,
            preferredBtAddress = p[Keys.preferredBtAddress] ?: "",
            sampleRate = p[Keys.sampleRate] ?: 16_000,
            bitrateBps = p[Keys.bitrateBps] ?: 24_000,
            detector = p[Keys.detector]?.let { runCatching { Detector.valueOf(it) }.getOrNull() }
                ?: Settings().detector,
            sensitivity = p[Keys.sensitivity]?.let { runCatching { EnergyVad.Sensitivity.valueOf(it) }.getOrNull() }
                ?: EnergyVad.Sensitivity.MEDIUM,
            customThresholdDb = p[Keys.customThresholdDb] ?: Settings().customThresholdDb,
            vadTracksFloor = (p[Keys.vadTracksFloor] ?: 1) == 1,
            absoluteThresholdDb = p[Keys.absoluteThresholdDb] ?: Settings().absoluteThresholdDb,
            sileroThresholdPct = p[Keys.sileroThresholdPct] ?: Settings().sileroThresholdPct,
            hangoverSec = p[Keys.hangoverSec] ?: 5,
            prerollMs = p[Keys.prerollMs] ?: 1000,
            minSegmentMs = p[Keys.minSegmentMs] ?: 800,
            maxSegmentSec = p[Keys.maxSegmentSec] ?: 900,
            statusIndicator = p[Keys.statusIndicator]?.let { runCatching { StatusBarIndicator.valueOf(it) }.getOrNull() }
                ?: StatusBarIndicator.SHOW_ICON,
            phoneProcessing = (p[Keys.phoneProcessing] ?: 0) == 1,
            keepCpuAwake = (p[Keys.keepCpuAwake] ?: 0) == 1,
            startOnBoot = (p[Keys.startOnBoot] ?: 0) == 1,
            autoDeleteDays = p[Keys.autoDeleteDays] ?: 0,
            maxStorageGb = p[Keys.maxStorageGb] ?: 0,
            storageMode = p[Keys.storageMode]?.let { runCatching { StorageMode.valueOf(it) }.getOrNull() }
                ?: Settings().storageMode,
            customTreeUri = p[Keys.customTreeUri] ?: "",
        )
    }

    suspend fun setRouteMode(v: RouteMode) = context.dataStore.edit { it[Keys.routeMode] = v.name }
    suspend fun setBuiltinMic(v: BuiltinMicPref) = context.dataStore.edit { it[Keys.builtinMic] = v.name }
    suspend fun setPreferredBtAddress(v: String) = context.dataStore.edit { it[Keys.preferredBtAddress] = v }
    suspend fun setSampleRate(v: Int) = context.dataStore.edit { it[Keys.sampleRate] = v }
    suspend fun setBitrate(v: Int) = context.dataStore.edit { it[Keys.bitrateBps] = v }
    suspend fun setDetector(v: Detector) = context.dataStore.edit { it[Keys.detector] = v.name }
    suspend fun setSensitivity(v: EnergyVad.Sensitivity) = context.dataStore.edit { it[Keys.sensitivity] = v.name }
    suspend fun setCustomThresholdDb(v: Int) = context.dataStore.edit { it[Keys.customThresholdDb] = v }
    suspend fun setVadTracksFloor(v: Boolean) = context.dataStore.edit { it[Keys.vadTracksFloor] = if (v) 1 else 0 }
    suspend fun setAbsoluteThresholdDb(v: Int) = context.dataStore.edit { it[Keys.absoluteThresholdDb] = v }
    suspend fun setSileroThresholdPct(v: Int) = context.dataStore.edit { it[Keys.sileroThresholdPct] = v }
    suspend fun setHangoverSec(v: Int) = context.dataStore.edit { it[Keys.hangoverSec] = v }
    suspend fun setPrerollMs(v: Int) = context.dataStore.edit { it[Keys.prerollMs] = v }
    suspend fun setMinSegmentMs(v: Long) = context.dataStore.edit { it[Keys.minSegmentMs] = v }
    suspend fun setMaxSegmentSec(v: Long) = context.dataStore.edit { it[Keys.maxSegmentSec] = v }
    suspend fun setStatusIndicator(v: StatusBarIndicator) = context.dataStore.edit { it[Keys.statusIndicator] = v.name }
    suspend fun setPhoneProcessing(v: Boolean) = context.dataStore.edit { it[Keys.phoneProcessing] = if (v) 1 else 0 }
    suspend fun setKeepCpuAwake(v: Boolean) = context.dataStore.edit { it[Keys.keepCpuAwake] = if (v) 1 else 0 }
    suspend fun setStartOnBoot(v: Boolean) = context.dataStore.edit { it[Keys.startOnBoot] = if (v) 1 else 0 }
    suspend fun setAutoDeleteDays(v: Int) = context.dataStore.edit { it[Keys.autoDeleteDays] = v }
    suspend fun setMaxStorageGb(v: Int) = context.dataStore.edit { it[Keys.maxStorageGb] = v }
    suspend fun setStorageMode(v: StorageMode) = context.dataStore.edit { it[Keys.storageMode] = v.name }
    suspend fun setCustomTreeUri(v: String) = context.dataStore.edit { it[Keys.customTreeUri] = v }
}
