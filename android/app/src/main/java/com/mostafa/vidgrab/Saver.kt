package com.mostafa.vidgrab

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

object Saver {
    fun mime(f: File) = when (f.extension.lowercase()) {
        "mp4" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "m4a" -> "audio/mp4"; "opus", "ogg" -> "audio/ogg"; "mp3" -> "audio/mpeg"
        else -> "application/octet-stream"
    }

    /** Saves audio to <Music|Download>/VidGrab as a music item, video to Movies/VidGrab. Returns a content Uri. */
    fun save(ctx: Context, f: File, audioFolder: String): Uri {
        val audio = mime(f).startsWith("audio")
        val top = if (audio) (if (audioFolder == "Download") Environment.DIRECTORY_DOWNLOADS else Environment.DIRECTORY_MUSIC) else Environment.DIRECTORY_MOVIES
        if (Build.VERSION.SDK_INT >= 29) {
            val coll = if (audio) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                       else MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime(f))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$top/VidGrab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (audio) put(MediaStore.Audio.Media.IS_MUSIC, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(coll, cv) ?: error("MediaStore insert failed")
            r.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out, 1 shl 20) } }
            // Clearing IS_PENDING makes MediaProvider scan the file (ID3 tags + embedded cover).
            r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(top), "VidGrab").apply { mkdirs() }
            val dest = File(dir, f.name); f.copyTo(dest, overwrite = true)
            MediaScannerConnection.scanFile(ctx, arrayOf(dest.path), arrayOf(mime(f)), null)
            return Uri.fromFile(dest)
        }
    }
}
