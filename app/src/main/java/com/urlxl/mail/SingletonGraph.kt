package com.urlxl.mail

import android.content.Context

/**
 * Thread-safe, context-scoped lazy holder shared by each package's `XGraph`/`XRuntime` pair
 * (mail/MailGraph, contacts/ContactsGraph, data/DataRuntime, push/PushRuntime) so the
 * double-checked-locking singleton logic lives in one place instead of four.
 */
class SingletonGraph<T>(private val factory: (Context) -> T) {
    @Volatile
    private var instance: T? = null

    fun get(context: Context): T {
        return instance ?: synchronized(this) {
            instance ?: factory(context.applicationContext).also { instance = it }
        }
    }
}
