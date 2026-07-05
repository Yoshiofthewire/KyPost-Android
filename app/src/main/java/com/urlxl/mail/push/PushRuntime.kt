package com.urlxl.mail.push

import android.content.Context

class PushGraph(context: Context) {
    private val appContext = context.applicationContext
    val repository = PushRepository(appContext)
    val pullCoordinator = PullSyncCoordinator(
        appContext = appContext,
        repository = repository,
    )
    val syncCoordinator = PushSyncCoordinator(
        repository = repository,
        registrationClient = NativeRegistrationClient(),
    )
}

object PushRuntime {
    @Volatile
    private var graphInstance: PushGraph? = null

    fun graph(context: Context): PushGraph {
        return graphInstance ?: synchronized(this) {
            graphInstance ?: PushGraph(context.applicationContext).also { graphInstance = it }
        }
    }
}

