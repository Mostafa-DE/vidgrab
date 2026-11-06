package com.mostafa.vidgrab

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.util.Log
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.thread

class App : Application() {
    override fun onCreate() {
        super.onCreate(); MyApp.ctx = this
        if (BuildConfig.DEBUG) Timing.log("App.onCreate start ${Timing.sinceStart()} ms since process start")
        thread(name = "vidgrab-init") {  // ~1.9 s on the first start after install; kept off the main thread
            try {
                Timing.time("init YoutubeDL") { YoutubeDL.getInstance().init(this) }
                Timing.time("init FFmpeg") { FFmpeg.getInstance().init(this) }
                Timing.time("init Aria2c") { Aria2c.getInstance().init(this) }
            } catch (e: Exception) { Log.e("VidGrab", "init failed", e) } finally { ready.complete(Unit) }
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }
    companion object {
        const val CHANNEL = "downloads"
        private val ready = CompletableDeferred<Unit>()
        /** Every YoutubeDL / FFmpeg / Aria2c use awaits this first. Also completes when init failed, so callers fail as they did before. */
        suspend fun awaitInit() = ready.await()
    }
}
