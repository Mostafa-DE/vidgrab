package com.mostafa.vidgrab

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    private val notifId = id.hashCode()
    private val procId = id.toString()

    private fun notification(title: String, text: String, progress: Int, ongoing: Boolean) =
        NotificationCompat.Builder(applicationContext, App.CHANNEL)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title).setContentText(text)
            .setOnlyAlertOnce(true).setOngoing(ongoing)
            .apply {
                if (ongoing) {
                    setProgress(100, progress.coerceIn(0, 100), progress < 0)
                    addAction(0, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
                }
            }.build()

    private fun fg(title: String, text: String, p: Int) = ForegroundInfo(notifId, notification(title, text, p, true),
        if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)

    override suspend fun getForegroundInfo() = fg("VidGrab", "Starting…", -1)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = Settings(applicationContext)
        val url = inputData.getString(K_URL)!!
        val title = inputData.getString(K_TITLE) ?: "Download"
        val artist = inputData.getString(K_ARTIST) ?: ""
        val thumb = inputData.getString(K_THUMB)
        val audio = inputData.getBoolean(K_AUDIO, true)
        val height = inputData.getInt(K_HEIGHT, 0).takeIf { it > 0 }
        val dir = File(applicationContext.cacheDir, "dl/$procId").apply { mkdirs() }
        setForeground(fg(title, "Starting…", -1))
        coroutineContext.job.invokeOnCompletion { if (it != null) runCatching { YoutubeDL.getInstance().destroyProcessById(procId) } }
        try {
            val match = if (audio) Match.fromJson(inputData.getString(K_MATCH)) else null
            val infoFile = match?.let { m -> runCatching { applyMatch(url, m, dir) }.getOrNull() }
            val req = (if (infoFile != null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)).apply {
                if (infoFile != null) { addOption("--load-info-json", infoFile.absolutePath) }
                val args = (if (audio) Ytdl.audioArgs(s.audioMode, s.originalLang) else Ytdl.videoArgs(height, s.originalLang)).toMutableList()
                val o = args.indexOf("-o"); args[o + 1] = dir.absolutePath + "/" + args[o + 1]
                addCommands(args)  // passed verbatim, multi-value options included
                addOption("--no-playlist"); addOption("--no-mtime"); addOption("--newline")
            }
            var last = 0L
            YoutubeDL.getInstance().execute(req, procId) { progress, eta, line ->
                val now = System.currentTimeMillis()
                if (now - last > 500) {
                    last = now
                    val speed = Regex("""at\s+(\S+/s)""").find(line)?.groupValues?.get(1) ?: ""
                    val stage = when { line.startsWith("[ExtractAudio]") -> "Converting to MP3…"; line.startsWith("[Merger]") -> "Merging…"
                        line.startsWith("[EmbedThumbnail]") || line.startsWith("[Metadata]") -> "Adding cover & tags…"; else -> "" }
                    val p = progress.toInt()
                    val text = stage.ifEmpty { "$p% · $speed · ETA ${dur(eta.toInt().coerceAtLeast(0))}" }
                    runCatching { NotificationManagerCompat.from(applicationContext).notify(notifId, notification(title, text, if (stage.isEmpty()) p else -1, true)) }
                    setProgressAsync(workDataOf("p" to p, "speed" to speed, "eta" to eta, "stage" to stage))
                }
            }
            val files = dir.walkTopDown().filter { it.isFile && it.extension !in setOf("part", "ytdl", "jpg", "webp", "png", "json") }.toList()
            if (files.isEmpty()) error("yt-dlp produced no file")
            setProgressAsync(workDataOf("p" to 100, "stage" to "Saving…"))
            val f = files.maxBy { it.length() }
            val uri = Saver.save(applicationContext, f, s.audioFolder)
            History.add(applicationContext, Done(f.nameWithoutExtension, artist, thumb, uri.toString(), Saver.mime(f), System.currentTimeMillis()))
            done(title, "Saved ${f.name}")
            Result.success(workDataOf("msg" to f.name, "uri" to uri.toString()))
        } catch (e: Exception) {
            if (isStopped) Result.failure(workDataOf("msg" to "Cancelled"))
            else { val m = e.message?.lines()?.lastOrNull { it.contains("ERROR") }?.take(300) ?: e.message?.takeLast(300) ?: e.toString()
                done(title, "Failed: $m"); Result.failure(workDataOf("msg" to m)) }
        } finally { dir.deleteRecursively() }
    }

    /** Re-extracts the video, injects the official tags + cover and returns an info.json for --load-info-json. */
    private fun applyMatch(url: String, m: Match, dir: File): File {
        val req = YoutubeDLRequest(url).apply { addOption("-J"); addOption("--no-playlist") }
        val j = org.json.JSONObject(YoutubeDL.getInstance().execute(req).out)
        val ytHasAll = listOf("artist", "track", "album").all { j.optString(it).isNotBlank() }
        if (!ytHasAll) {  // YouTube Music/Topic tags win when present; otherwise use the official match
            j.put("artist", m.artist).put("track", m.title).put("album", m.album).put("album_artist", m.albumArtist)
            if (m.year.isNotBlank()) j.put("release_year", m.year.toInt())
        }
        m.genre?.let { if (j.optString("genre").isBlank()) j.put("genre", it) }
        m.track?.let { j.put("track_number", it) }; m.disc?.let { j.put("disc_number", it) }
        if (coverOk(m.cover)) {
            j.put("thumbnails", org.json.JSONArray().put(org.json.JSONObject().put("id", "official").put("url", m.cover)))
            j.remove("thumbnail")
        }
        return File(dir, "info.json").apply { writeText(j.toString()) }
    }
    private fun coverOk(u: String) = runCatching {
        val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
        c.instanceFollowRedirects = true; c.requestMethod = "HEAD"; c.connectTimeout = 8000; c.setRequestProperty("User-Agent", Matcher.UA)
        (c.responseCode in 200..399).also { c.disconnect() }
    }.getOrDefault(false)

    private fun done(title: String, text: String) {
        try { NotificationManagerCompat.from(applicationContext).notify(notifId + 1, notification(title, text, 100, false)) } catch (_: SecurityException) {}
    }

    companion object {
        const val K_URL = "url"; const val K_TITLE = "title"; const val K_ARTIST = "artist"; const val K_THUMB = "thumb"
        const val K_AUDIO = "audio"; const val K_HEIGHT = "height"; const val TAG = "vidgrab"; const val K_MATCH = "match"
        fun enqueue(ctx: Context, info: MediaInfo, audio: Boolean, height: Int? = null, match: Match? = null) {
            val label = if (audio) "${match?.artist ?: info.artist} - ${match?.title ?: info.title}" else info.title
            val thumb = match?.cover?.replace("1000x1000bb", "300x300bb")?.replace("front-1200", "front-250") ?: info.thumb
            val req = OneTimeWorkRequestBuilder<DownloadWorker>()
                .setInputData(workDataOf(K_URL to info.url, K_TITLE to label, K_ARTIST to (match?.artist ?: info.artist), K_THUMB to thumb,
                    K_AUDIO to audio, K_HEIGHT to (height ?: 0), K_MATCH to match?.toJson()?.toString()))
                .addTag(TAG).addTag("t:" + label.take(100)).addTag("th:" + (thumb ?: "")).addTag(if (audio) "k:mp3" else "k:video")
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(ctx).enqueue(req)
        }
    }
}
