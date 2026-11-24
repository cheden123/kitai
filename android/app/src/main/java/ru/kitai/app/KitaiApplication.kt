package ru.kitai.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient

@HiltAndroidApp
class KitaiApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient {
            OkHttpClient.Builder().followRedirects(false).build()
        }
        .build()
}
