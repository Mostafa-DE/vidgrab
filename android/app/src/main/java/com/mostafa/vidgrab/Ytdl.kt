package com.mostafa.vidgrab

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONObject
import java.io.File

enum class AudioMode(val label: String) { MP3_320("MP3 320 kbps (CBR)"), MP3_V0("MP3 V0 (~245 kbps VBR)"), MP3_192("MP3 192 kbps"), ORIGINAL("Best original (M4A/Opus, no re-encode)") }

class Settings(ctx: Context) {
    private val p = ctx.getSharedPreferences("vidgrab", Context.MODE_PRIVATE)
    var audioMode: AudioMode
        get() = runCatching { AudioMode.valueOf(p.getString("audioMode", "")!!) }.getOrDefault(AudioMode.MP3_320)
        set(v) = p.edit().putString("audioMode", v.name).apply()
    var originalLang: Boolean
        get() = p.getBoolean("orig", true); set(v) = p.edit().putBoolean("orig", v).apply()
    /** "Music" or "Download" — top-level public folder for audio. */
    var audioFolder: String
        get() = p.getString("audioFolder", "Music")!!; set(v) = p.edit().putString("audioFolder", v).apply()
    var autoPaste: Boolean
        get() = p.getBoolean("autoPaste", true); set(v) = p.edit().putBoolean("autoPaste", v).apply()
    var matchMeta: Boolean
        get() = p.getBoolean("matchMeta", true); set(v) = p.edit().putBoolean("matchMeta", v).apply()
    var dupCheck: Boolean
        get() = p.getBoolean("dupCheck", true); set(v) = p.edit().putBoolean("dupCheck", v).apply()
    var lastUpdate: Long
        get() = p.getLong("updated", 0); set(v) = p.edit().putLong("updated", v).apply()
}

object Ytdl {
    private fun sort(origLang: Boolean) = if (origLang) "lang,abr,asr" else "abr,asr"

    /** Exactly the argument set verified on desktop (see /workspace/vidgrab/mp3-args-tested.txt). */
    fun audioArgs(mode: AudioMode, origLang: Boolean): List<String> {
        val a = mutableListOf("-f", "bestaudio/best", "-S", sort(origLang), "-x")
        when (mode) {
            AudioMode.MP3_320 -> a += listOf("--audio-format", "mp3", "--audio-quality", "320K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 320k -ar 44100")
            AudioMode.MP3_192 -> a += listOf("--audio-format", "mp3", "--audio-quality", "192K", "--ppa", "ExtractAudio+ffmpeg_o:-b:a 192k -ar 44100")
            AudioMode.MP3_V0 -> a += listOf("--audio-format", "mp3", "--audio-quality", "0", "--ppa", "ExtractAudio+ffmpeg_o:-ar 44100")
            AudioMode.ORIGINAL -> a += listOf("--audio-format", "best")
        }
        a += MetaArgs.tagging
        a += listOf("-o", MetaArgs.OUTPUT)
        return a
    }

    fun videoArgs(height: Int?, origLang: Boolean): List<String> {
        val ba = if (origLang) "ba[format_note*=original]/ba" else "ba"
        val f = if (height == null) "bv*+($ba)/b" else "bv*[height<=$height]+($ba)/b[height<=$height]"
        return listOf("-f", f, "-S", "res,fps,vcodec:vp9,acodec:m4a" + if (origLang) ",lang" else "",
            "--merge-output-format", "mp4", "--embed-metadata", "--embed-thumbnail", "--convert-thumbnails", "jpg",
            "-o", "%(title).150B.%(ext)s")
    }
}

/** Every yt-dlp request keeps its cache between runs (youtubedl-android passes --no-cache-dir otherwise); saves ~0.3 s per lookup. */
fun YoutubeDLRequest.cached() = apply { addOption("--cache-dir", File(MyApp.ctx.cacheDir, "yt-dlp").absolutePath) }

data class VideoChoice(val height: Int?, val label: String, val detail: String)

/** [partial] = quick oEmbed info: no duration or formats yet, default [choices] without sizes. */
data class MediaInfo(val url: String, val title: String, val artist: String, val duration: Int, val thumb: String?, val choices: List<VideoChoice>,
                     val rawTitle: String = title, val known: Boolean = true, val ytAlbum: String? = null, val partial: Boolean = false) {
    companion object {
        private val junk = Regex("""(?i)\s*[\(\[][^\)\]]*\b(official|lyrics?|video|audio|visuali[sz]er|hd|hq|4k|mv|ncs|copyright|music)\b[^\)\]]*[\)\]]""")
        /** Artist, title and known (= reliable split) from YouTube's tags, else "Artist - Title", else the uploader without " - Topic". */
        private fun names(rawTitle: String, artistTag: String, trackTag: String, uploader: String): Triple<String, String, Boolean> {
            val dash = Regex("""^(.+?)\s+[-–—]\s+(.+)$""").find(rawTitle)
            val artist = artistTag.ifBlank { null } ?: dash?.groupValues?.get(1) ?: uploader.replace(Regex("""\s*-\s*Topic$"""), "")
            val title = (trackTag.ifBlank { null } ?: dash?.groupValues?.get(2) ?: rawTitle)
                .replace(Regex("""\s*\|.*$"""), "").replace(junk, "").trim()
            return Triple(artist, title, artistTag.isNotBlank() || dash != null)
        }
        fun parse(url: String, j: JSONObject): MediaInfo {
            val rawTitle = j.optString("title")
            val (artist, title, known) = names(rawTitle, j.optString("artist"), j.optString("track"), j.optString("uploader"))
            val fmts = j.optJSONArray("formats")
            var audio = 0.0
            val best = HashMap<Int, Double>()
            val dur = j.optDouble("duration", 0.0)
            if (fmts != null) for (i in 0 until fmts.length()) {
                val f = fmts.getJSONObject(i)
                val size = f.optDouble("filesize", Double.NaN).takeIf { !it.isNaN() } ?: f.optDouble("filesize_approx", Double.NaN).takeIf { !it.isNaN() }
                    ?: (f.optDouble("tbr", 0.0) * 125 * dur)
                val vc = f.optString("vcodec", "none"); val h = f.optInt("height", 0)
                if (vc == "none" && f.optString("acodec", "none") != "none") audio = maxOf(audio, size)
                else if (vc != "none" && h > 0 && f.optString("protocol") != "mhtml") best[h] = maxOf(best[h] ?: 0.0, size)
            }
            val heights = best.keys.sortedDescending()
            fun name(h: Int) = when { h >= 2160 -> "4K"; h >= 1440 -> "1440p"; else -> "${h}p" }
            val choices = mutableListOf<VideoChoice>()
            heights.firstOrNull()?.let { choices += VideoChoice(null, "Best", "${name(it)} · ${human(best[it]!! + audio)}") }
            heights.filter { it in listOf(2160, 1440, 1080, 720, 480) || it == heights.first() }.distinctBy { name(it) }
                .forEach { choices += VideoChoice(it, name(it), human(best[it]!! + audio)) }
            return MediaInfo(url, title, artist, j.optInt("duration"),
                j.optString("thumbnail").ifBlank { null }, choices, rawTitle,
                known = known, ytAlbum = j.optString("album").ifBlank { null })
        }
        /** YouTube oEmbed (title, author_name = uploader, thumbnail_url) as partial info; throws without a title. */
        fun oembed(url: String, j: JSONObject): MediaInfo {
            val rawTitle = j.getString("title")
            val (artist, title, known) = names(rawTitle, "", "", j.optString("author_name"))
            return MediaInfo(url, title, artist, 0, j.optString("thumbnail_url").ifBlank { null },
                listOf(VideoChoice(null, "Best", "")) + listOf(1080, 720, 480).map { VideoChoice(it, "${it}p", "") }, rawTitle, known, partial = true)
        }
        fun human(b: Double) = when { b <= 0 -> "size ?"; b > 1e9 -> "%.2f GB".format(b / 1e9); else -> "%.0f MB".format(b / 1e6) }
    }
}

fun dur(s: Int) = if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
