package app.pendant.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** Helpers for enumerating and naming input devices. */
object AudioRoutes {

    fun builtinMics(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

    fun bluetoothMics(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        }

    fun wiredMics(am: AudioManager): List<AudioDeviceInfo> =
        am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
        }

    fun label(info: AudioDeviceInfo): String = when (info.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> {
            val addr = info.address?.lowercase().orEmpty()
            when {
                addr.contains("bottom") -> "Built-in mic (bottom)"
                addr.contains("top") -> "Built-in mic (top)"
                addr.contains("back") -> "Built-in mic (back)"
                else -> "Built-in mic"
            }
        }
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth: ${info.productName?.ifEmpty { "headset" }}"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> "Wired headset"
        else -> info.productName?.toString() ?: "Microphone"
    }

    /**
     * Picks device infos for the given built-in mic preference.
     * "address" of built-in mics is usually "bottom"/"top"/"back".
     */
    fun select(available: List<AudioDeviceInfo>, pref: app.pendant.settings.BuiltinMicPref): List<AudioDeviceInfo> {
        if (available.isEmpty()) return emptyList()
        val byAddr = { needle: String -> available.filter { it.address?.lowercase()?.contains(needle) == true } }
        return when (pref) {
            app.pendant.settings.BuiltinMicPref.BOTTOM -> byAddr("bottom").ifEmpty { available.take(1) }
            app.pendant.settings.BuiltinMicPref.TOP -> byAddr("top").ifEmpty { available.take(1) }
            app.pendant.settings.BuiltinMicPref.BACK -> byAddr("back").ifEmpty { available.take(1) }
            app.pendant.settings.BuiltinMicPref.AUTO -> available.take(1)
            app.pendant.settings.BuiltinMicPref.COMBINE -> {
                // Prefer two mics with distinct addresses, else the first two devices.
                val distinct = available.groupBy { it.address?.lowercase() ?: "" }
                    .values.take(2).map { it.first() }
                if (distinct.size >= 2) distinct else available.take(2)
            }
        }
    }

    fun isAtLeastS(): Boolean = Build.VERSION.SDK_INT >= 31
}
