package com.urlxl.mail.contacts

import android.content.Context
import com.urlxl.mail.SingletonGraph
import com.urlxl.mail.data.DataRuntime
import com.urlxl.mail.push.PushRuntime
import kotlinx.coroutines.flow.first

class ContactsGraph(context: Context) {
    private val appContext = context.applicationContext
    val repository = ContactSyncRepository(
        db = DataRuntime.graph(appContext).database,
        client = ContactSyncClient(),
        cursorStore = ContactCursorStore(appContext),
        pairingProvider = { PushRuntime.graph(appContext).repository.state.first().pairing },
    )
    val coordinator = ContactSyncCoordinator(repository)
    val groupSyncRepository = GroupSyncRepository(
        db = DataRuntime.graph(appContext).database,
        client = GroupsSyncClient(),
        pairingProvider = { PushRuntime.graph(appContext).repository.state.first().pairing },
    )
}

object ContactsRuntime {
    private val holder = SingletonGraph(::ContactsGraph)

    fun graph(context: Context): ContactsGraph = holder.get(context)
}
