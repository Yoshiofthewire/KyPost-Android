package com.urlxl.mail

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.urlxl.mail.contacts.ContactsRuntime
import com.urlxl.mail.contacts.device.DeviceContactsRuntime
import com.urlxl.mail.push.PushNotificationDispatcher
import com.urlxl.mail.push.PushRuntime

/**
 * Process-level wiring for push/pull delivery. Observes the process lifecycle so that every
 * time the app foregrounds we re-read the authoritative delivery mode and, when in "App Pull"
 * mode, kick an immediate pull — complementing the WorkManager periodic baseline. In push mode
 * [com.urlxl.mail.push.PullSyncCoordinator.pullOnce] no-ops and disarms the periodic worker.
 */
class LlamaApp : Application(), DefaultLifecycleObserver {
    override fun onCreate() {
        super<Application>.onCreate()
        PushNotificationDispatcher.ensureChannel(this)
        DeviceContactsRuntime.graph(this).bootstrapIfEnabled()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        // App moved to the foreground.
        PushRuntime.graph(this).pullCoordinator.pullNowAsync()
        ContactsRuntime.graph(this).coordinator.syncNowAsync()
        DeviceContactsRuntime.graph(this).coordinator.syncNowAsync()
    }
}
