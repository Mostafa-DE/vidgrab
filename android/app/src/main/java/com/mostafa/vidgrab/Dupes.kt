package com.mostafa.vidgrab

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.text.Normalizer

data class LibSong(val id: Long, val title: String, val artist: String, val album: String, val durationSec: Int, val path: String, val albumId: Long) {
    val uri get() = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
    val artUri get() = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), albumId)
}
data class Dupe(val song: LibSong, val score: Double)

/** Fuzzy "do I already have this song?" matcher. Pure functions are unit-tested on the box. */
object Dupes {
    const val THRESHOLD = 0.86
    private val JUNK = Regex("""(?i)[\(\[][^\)\]]*\b(official|lyrics?|video|audio|visuali[sz]er|hd|hq|4k|mv|m/v|remaster\w*|live|explicit|clean|radio edit|music|ncs|copyright)\b[^\)\]]*[\)\]]""")
    private val TRAIL = Regex("""(?i)\s+[-–—]\s*(\d{4}\s+)?remaster\w*(\s+\d{4})?.*$|\s+\b(remaster\w*\s*\d{0,4}|live|hd|hq|4k)\s*$""")
    private val FEAT = Regex("""(?i)[\(\[]?\s*\b(feat|ft|featuring)\b\.?.*$""")
    private val UNKNOWN = Regex("""(?i)^\s*(<unknown>|unknown( artist)?|various artists|)\s*$""")

    fun norm(s: String): String {
        var t = s.replace(JUNK, " ").replace(TRAIL, " ").replace(FEAT, " ")
        t = Normalizer.normalize(t.lowercase(), Normalizer.Form.NFKD).replace(Regex("""\p{Mn}+"""), "")  // accents + Arabic tashkeel
        t = t.replace('\u0640', ' ')  // tatweel
            .replace(Regex("[أإآٱ]"), "ا").replace('ة', 'ه').replace('ى', 'ي').replace('ؤ', 'و').replace('ئ', 'ي')
        t = t.replace("&", " and ").replace(Regex("""[^\p{L}\p{N}]+"""), " ")
        return t.split(' ').filter { it.isNotBlank() && it != "the" && it != "vevo" && it != "topic" }.joinToString(" ")
    }

    fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0; if (a.isEmpty() || b.isEmpty()) return 0.0
        val win = maxOf(0, maxOf(a.length, b.length) / 2 - 1)
        val am = BooleanArray(a.length); val bm = BooleanArray(b.length); var m = 0
        for (i in a.indices) for (j in maxOf(0, i - win) until minOf(b.length, i + win + 1)) if (!bm[j] && a[i] == b[j]) { am[i] = true; bm[j] = true; m++; break }
        if (m == 0) return 0.0
        var t = 0; var k = 0
        for (i in a.indices) if (am[i]) { while (!bm[k]) k++; if (a[i] != b[k]) t++; k++ }
        val j = (m.toDouble() / a.length + m.toDouble() / b.length + (m - t / 2.0) / m) / 3
        var p = 0; while (p < minOf(4, a.length, b.length) && a[p] == b[p]) p++
        return j + p * .1 * (1 - j)
    }
    private fun sortTok(s: String) = s.split(' ').filter { it.isNotEmpty() }.sorted().joinToString(" ")
    /** Token-sort Jaro-Winkler, plus a token-set bonus when one side's tokens are all in the other. */
    fun tokenSim(a: String, b: String): Double {
        val na = norm(a); val nb = norm(b); if (na.isEmpty() || nb.isEmpty()) return 0.0
        val ta = na.split(' ').toSet(); val tb = nb.split(' ').toSet()
        val inter = ta.intersect(tb)
        val sortSim = jaroWinkler(sortTok(na), sortTok(nb))
        val setSim = if (inter.isEmpty()) 0.0 else {
            val i = inter.sorted().joinToString(" ")
            maxOf(jaroWinkler(i, sortTok(ta.joinToString(" "))), jaroWinkler(i, sortTok(tb.joinToString(" "))))
                .let { if (inter.size == minOf(ta.size, tb.size)) it else it * .9 }
        }
        return maxOf(sortSim, setSim * if (inter.size == minOf(ta.size, tb.size)) 1.0 else .9)
    }
    private fun unknown(s: String) = UNKNOWN.matches(s)

    fun score(title: String, artist: String, dur: Int, lib: LibSong): Double {
        val durOk = dur <= 0 || lib.durationSec <= 0 || Math.abs(dur - lib.durationSec) <= 8
        if (!durOk) return 0.0
        val combined = tokenSim("$artist $title", if (unknown(lib.artist)) lib.title else "${lib.artist} ${lib.title}")
        val ts = tokenSim(title, lib.title)
        // title must match strongly on its own, or as a combined "artist title" string
        val tset = norm(title).split(' ').toSet(); val lset = norm(lib.title).split(' ').toSet()
        val exactish = tset.isNotEmpty() && (lset.containsAll(tset) || tset.containsAll(lset))
        var s = if (unknown(lib.artist)) combined
                else { val a = tokenSim(artist, lib.artist); if (a < .75 && combined < .9) return 0.0; maxOf(.65 * ts + .35 * a, combined) }
        if (!exactish && ts < .9 && combined < .93) s *= .85
        if (dur > 0 && lib.durationSec > 0) s += .04 * (1 - Math.abs(dur - lib.durationSec) / 8.0)
        return s.coerceAtMost(1.0)
    }

    fun find(title: String, artist: String, dur: Int, lib: List<LibSong>) =
        lib.map { Dupe(it, score(title, artist, dur, it)) }.filter { it.score >= THRESHOLD }.sortedByDescending { it.score }.take(3)

    fun library(ctx: Context): List<LibSong> {
        val out = ArrayList<LibSong>()
        val proj = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.ALBUM_ID)
        runCatching {
            ctx.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) out += LibSong(c.getLong(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getString(3) ?: "",
                    (c.getLong(4) / 1000).toInt(), c.getString(5) ?: "", c.getLong(6))
            }
        }
        return out
    }
    fun permission() = if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_AUDIO else android.Manifest.permission.READ_EXTERNAL_STORAGE
}
