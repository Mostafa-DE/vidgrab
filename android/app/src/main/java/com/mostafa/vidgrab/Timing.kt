package com.mostafa.vidgrab

import android.os.Process
import android.os.SystemClock
import android.util.Log

/** Debug builds only ("VidGrab test"): timing logs under [TAG]. Callers gate on BuildConfig.DEBUG. */
object Timing {
    const val TAG = "VidGrabTiming"
    private var shareAt = 0L

    fun now() = SystemClock.elapsedRealtime()
    fun sinceStart() = now() - Process.getStartElapsedRealtime()
    fun log(m: String) = m.chunked(1000).forEach { Log.i(TAG, it) }  // logcat truncates long entries
    inline fun <T> time(name: String, block: () -> T): T {
        if (!BuildConfig.DEBUG) return block()
        val t = now(); try { return block() } finally { log("$name ${now() - t} ms") }
    }
    fun shareCreated() { shareAt = now(); log("ShareActivity.onCreate ${sinceStart()} ms since process start") }
    fun since(what: String) = log("$what ${now() - shareAt} ms after ShareActivity.onCreate (${sinceStart()} ms since process start)")
}
