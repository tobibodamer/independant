package app.pendant

import android.app.Application

class PendantApp : Application() {

    val settings by lazy { app.pendant.settings.PendantSettings(this) }
    val store by lazy { app.pendant.storage.SegmentStore(this) }
    /** Shared clip listing feeding the Recordings tab (index snapshot + fresh re-lists). */
    val recordings by lazy { app.pendant.storage.RecordingsSource(this, store) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
        // App-start trigger: building the source loads the persisted snapshot and
        // schedules one fast re-list, so tab opens never rescan and never paint empty.
        recordings
    }

    companion object {
        fun get(context: android.content.Context) = context.applicationContext as PendantApp
    }
}
