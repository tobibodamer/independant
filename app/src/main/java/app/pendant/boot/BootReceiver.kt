package app.pendant.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.pendant.Notifications
import app.pendant.PendantApp
import app.pendant.recording.RecordingController
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/**
 * Starts recording after boot when the user enabled it.
 *
 * Note: since Android 14, microphone foreground services cannot be started from
 * the background; on newer devices we catch the rejection and post a
 * one-tap notification instead.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = PendantApp.get(context)
        val startOnBoot = runBlocking {
            runCatching { app.settings.settings.first().startOnBoot }.getOrDefault(false)
        }
        if (!startOnBoot) return
        try {
            RecordingController.start(context)
        } catch (e: Exception) {
            Log.w("PendantBoot", "blocked from starting at boot: ${e.message}")
            Notifications.showAlert(
                context,
                "Pendant",
                "Recording could not start automatically. Tap here to start it.",
            )
        }
    }
}
