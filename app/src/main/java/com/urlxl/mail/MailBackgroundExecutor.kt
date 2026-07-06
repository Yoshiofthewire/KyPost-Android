package com.urlxl.mail

import java.util.concurrent.Executors

// State-changing mail actions (mark read, archive, delete, move) are fired here instead of an
// Activity-scoped executor so the IMAP round trip keeps running after the screen that triggered
// it finishes, letting the UI update optimistically instead of waiting on the network.
object MailBackgroundExecutor {
    private val executor = Executors.newFixedThreadPool(2)

    fun submit(task: () -> Unit) {
        executor.execute(task)
    }
}
