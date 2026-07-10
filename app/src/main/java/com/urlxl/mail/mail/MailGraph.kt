package com.urlxl.mail.mail

import android.content.Context
import com.urlxl.mail.MailGateway
import com.urlxl.mail.MailSettings
import com.urlxl.mail.SingletonGraph
import com.urlxl.mail.data.DataRuntime
import com.urlxl.mail.push.PushRuntime
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class MailGraph(context: Context) {
    private val appContext = context.applicationContext
    private val mailSettings = MailSettings(appContext)
    private val imapSource: MailSource = ImapMailSource(MailGateway.fromSettings(appContext))
    private val mailCursorStore = MailCursorStore(appContext)
    private val relaySource: MailSource = RelayMailSource(
        // Non-suspend by design (MailSource is a blocking interface) but always fresh: reads the
        // same PushRepository-backed pairing state the push/pull path uses, never a stale copy.
        // Safe to block here — this only ever runs on a background executor thread, never main.
        pairingProvider = { runBlocking { PushRuntime.graph(appContext).repository.state.first().pairing } },
        cursorProvider = mailCursorStore,
    )

    val repository = MailRepository(
        emailDao = DataRuntime.graph(appContext).database.emailDao(),
        imapSource = imapSource,
        relaySource = relaySource,
        mailSettings = mailSettings,
    )
}

object MailRuntime {
    private val holder = SingletonGraph(::MailGraph)

    fun graph(context: Context): MailGraph = holder.get(context)
}
