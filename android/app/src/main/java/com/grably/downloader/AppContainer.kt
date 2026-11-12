package com.grably.downloader

import android.content.Context
import com.grably.downloader.data.DownloadRepository
import com.grably.downloader.data.GrablyApi
import com.grably.downloader.data.db.AppDatabase
import com.grably.downloader.util.Settings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Creates and holds the objects the whole app shares. */
class AppContainer(context: Context) {
    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        // The server prepares the whole video before it starts sending it, so allow a long wait
        .readTimeout(5, TimeUnit.MINUTES)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "Grabbly-Android/${BuildConfig.VERSION_NAME}")
                    .build(),
            )
        }
        .build()

    val api = GrablyApi(BuildConfig.API_BASE_URL, httpClient)
    val database: AppDatabase = AppDatabase.create(context)
    val downloads = DownloadRepository(context.applicationContext, database.downloadDao())
    val settings = Settings(context)
}
