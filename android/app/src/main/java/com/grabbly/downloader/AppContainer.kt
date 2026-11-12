package com.grabbly.downloader

import android.content.Context
import com.grabbly.downloader.data.DownloadRepository
import com.grabbly.downloader.data.GrabblyApi
import com.grabbly.downloader.data.db.AppDatabase
import com.grabbly.downloader.util.Settings
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

    val api = GrabblyApi(BuildConfig.API_BASE_URL, httpClient)
    val database: AppDatabase = AppDatabase.create(context)
    val downloads = DownloadRepository(context.applicationContext, database.downloadDao())
    val settings = Settings(context)
}
