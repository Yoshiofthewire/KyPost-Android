package com.urlxl.mail.contacts.device

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class DeviceContactSyncCoordinator(
    private val repository: DeviceContactRepository,
    private val settings: DeviceContactSyncSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var debounceJob: kotlinx.coroutines.Job? = null

    fun syncNowAsync() {
        if (!settings.isEnabled()) return
        scope.launch { runCatching { repository.syncAll() } }
    }

    fun syncWithDebounce() {
        if (!settings.isEnabled()) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(1500)
            runCatching { repository.syncAll() }
        }
    }
}
