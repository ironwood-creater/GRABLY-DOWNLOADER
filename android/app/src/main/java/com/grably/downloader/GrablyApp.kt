package com.grably.downloader

import android.app.Application
import android.content.Context
import com.grably.downloader.download.Notifications

class GrablyApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}

/** App-wide objects: API client, database, download queue and settings. */
val Context.appContainer: AppContainer
    get() = (applicationContext as GrablyApp).container
