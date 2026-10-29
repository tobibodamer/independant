package app.pendant

import android.app.Application

class PendantApp : Application() {

    val settings by lazy { app.pendant.settings.PendantSettings(this) }
    val store by lazy { app.pendant.storage.SegmentStore(this) }

    override fun onCreate() {
        super.onCreate()
        Notifications.createChannels(this)
    }

    companion object {
        fun get(context: android.content.Context) = context.applicationContext as PendantApp
    }
}
