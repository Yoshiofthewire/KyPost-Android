package com.urlxl.mail.contacts.device

import android.content.Context
import com.urlxl.mail.SingletonGraph
import com.urlxl.mail.contacts.ContactsRuntime
import com.urlxl.mail.data.DataRuntime

class DeviceContactsGraph(context: Context) {
    private val appContext = context.applicationContext
    val settings = DeviceContactSyncSettings(appContext)
    val accountManager = DeviceContactAccountManager(appContext)
    val repository = DeviceContactRepository(
        context = appContext,
        db = DataRuntime.graph(appContext).database,
        syncRepository = ContactsRuntime.graph(appContext).repository,
        groupSyncRepository = ContactsRuntime.graph(appContext).groupSyncRepository,
    )
    val coordinator = DeviceContactSyncCoordinator(repository, settings)
    val observer = DeviceContactObserver(appContext, coordinator)

    fun bootstrapIfEnabled() {
        if (!settings.isEnabled()) return
        DeviceContactSyncScheduler.ensurePeriodic(appContext)
    }
}

object DeviceContactsRuntime {
    private val holder = SingletonGraph(::DeviceContactsGraph)

    fun graph(context: Context): DeviceContactsGraph = holder.get(context)
}
