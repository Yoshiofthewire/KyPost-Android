package com.urlxl.mail.contacts.device

import android.app.Service
import android.content.Intent
import android.os.IBinder

class LlamaContactAuthenticatorService : Service() {
    private lateinit var authenticator: LlamaContactAuthenticator

    override fun onCreate() {
        super.onCreate()
        authenticator = LlamaContactAuthenticator(this)
    }

    override fun onBind(intent: Intent?): IBinder = authenticator.iBinder
}
