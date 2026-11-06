package com.mostafa.vidgrab

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer

/** An official-metadata match for a song (iTunes or MusicBrainz). */
data class Match(
    val src: String, val artist: String, val title: String, val album: String, val year: String,
    val genre: String?, val track: Int?, val disc: Int?, val albumArtist: String, val cover: String, val score: Double,
) {
    fun toJson() = JSONObject().put("src", src).put("artist", artist).put("title", title).put("album", album).put("year", year)
        .put("genre", genre ?: "").put("track", track ?: 0).put("disc", disc ?: 0).put("albumArtist", albumArtist).put("cover", cover).put("score", score)
    companion object {
        fun fromJson(s: String?): Match? = s?.let { runCatching { JSONObject(it) }.getOrNull() }?.let { j ->
            Match(j.getString("src"), j.getString("artist"), j.getString("title"), j.getString("album"), j.getString("year"),
                j.optString("genre").ifBlank { null }, j.optInt("track").takeIf { it > 0 }, j.optInt("disc").takeIf { it > 0 },
                j.getString("albumArtist"), j.getString("cover"), j.optDouble("score"))
        }
    }
}

object Matcher {
    const val UA = "VidGrab/2.1 ( https://github.com/mostafa/vidgrab )"
    const val CONFIDENT = 1.7
    private var lastMb = 0L

    fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000; c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept", "application/json")
        return c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    fun norm(s: String): String {
        var t = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFKD).replace(Regex("""\p{Mn}+"""), "")
        t = t.replace(Regex("""\(.*?\)|\[.*?\]"""), "").replace(Regex("""\b(feat|ft)\b.*"""), "").replace(Regex("""^the\s+"""), "")
        return t.replace(Regex("""[^\p{L}\p{N}]+"""), "")
    }
    /** Transliteration-tolerant skeleton: no vowels, no doubled letters ("Tamally Ma'ak" ≈ "Tamly Maak"). */
    fun skel(s: String) = norm(s).replace(Regex("[aeiouy]"), "").replace(Regex("(.)\\1+"), "$1")
    private fun lev(a: String, b: String): Int {
        var p = IntArray(b.length + 1) { it }
        for (i in 1..a.length) { val c = IntArray(b.length + 1); c[0] = i
            for (j in 1..b.length) c[j] = minOf(p[j] + 1, c[j - 1] + 1, p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1); p = c }
        return p[b.length]
    }
    fun ratio(a: String, b: String) = if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - lev(a, b).toDouble() / maxOf(a.length, b.length)
    fun partial(n: String, h: String): Double {
        if (n.isEmpty() || h.isEmpty()) return 0.0
        if (h.contains(n)) return 1.0
        if (h.length <= n.length) return ratio(n, h)
        return (0..h.length - n.length).maxOf { ratio(n, h.substring(it, it + n.length)) }
    }
    private val BAD = Regex("""(?i)greatest hits|best of|\bhits\b|collection|anthology|essential|\blive\b|now that|compilation|karaoke|tribute|\bvol\b|volume|top \d+|songs for|\bcover|mixtape|\d0s\b|playlist""")
    private val VERSION = Regex("""(?i)\b(mix|remix|live|acoustic|instrumental|edit|version|demo|remaster\w*|karaoke|sped|slowed|cover)\b""")

    /** Returns null when it isn't a plausible match. [known] = artist/title came from a reliable split. */
    fun score(artist: String, title: String, raw: String, dur: Int, ar: String, tr: String, al: String, tsec: Double, va: Boolean, known: Boolean): Double? {
        val sa = maxOf(ratio(norm(artist), norm(ar)), partial(norm(ar), norm(raw)))
        var st = maxOf(ratio(norm(title), norm(tr)), ratio(skel(title), skel(tr)) * .95)
        if (!known || st < .85) st = maxOf(st, partial(skel(tr), skel(raw)) * .95)
        if (sa < .8 || st < .85) return null
        val d = if (dur > 0 && tsec > 0) Math.abs(tsec - dur) else 0.0
        if (d > 5) return null
        var s = sa + st - d * .01
        if (BAD.containsMatchIn(al) || va) s -= .4
        if (Regex("""(?i) - (single|ep)$""").containsMatchIn(al)) s -= .1
        if (VERSION.containsMatchIn(tr) && !VERSION.containsMatchIn(raw)) s -= .3
        return s
    }

    fun itunes(artist: String, title: String, raw: String, dur: Int, known: Boolean): List<Match> {
        val res = LinkedHashMap<Long, Match>()
        val latin = Regex("""[A-Za-z0-9']+""").findAll(raw.replace(Regex("""\(.*?\)|\[.*?\]"""), "")).joinToString(" ") { it.value }.replace("'", "")
        for (q in linkedSetOf("$artist $title", latin).filter { it.isNotBlank() }) {
            val arr = JSONObject(get("https://itunes.apple.com/search?term=${enc(q)}&entity=song&limit=25")).optJSONArray("results") ?: continue
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                val s = score(artist, title, raw, dur, r.optString("artistName"), r.optString("trackName"), r.optString("collectionName"),
                    r.optDouble("trackTimeMillis", 0.0) / 1000, r.optString("collectionArtistName") == "Various Artists", known) ?: continue
                res[r.optLong("trackId")] = Match("iTunes", r.getString("artistName"), r.getString("trackName"), r.optString("collectionName"),
                    r.optString("releaseDate").take(4), r.optString("primaryGenreName").ifBlank { null }, r.optInt("trackNumber").takeIf { it > 0 },
                    r.optInt("discNumber").takeIf { it > 0 }, r.optString("collectionArtistName").ifBlank { r.getString("artistName") },
                    r.optString("artworkUrl100").replace("100x100bb", "1000x1000bb"), s)
            }
            if (res.values.any { it.score >= CONFIDENT }) break
        }
        return res.values.sortedByDescending { it.score }
    }

    @Synchronized private fun mbGet(url: String): String {
        val wait = 1100 - (System.currentTimeMillis() - lastMb); if (wait > 0) Thread.sleep(wait)
        return try { get(url) } finally { lastMb = System.currentTimeMillis() }
    }

    fun musicbrainz(artist: String, title: String, raw: String, dur: Int, known: Boolean): List<Match> {
        val q = "recording:\"${title.replace("\"", "")}\" AND artist:\"${artist.replace("\"", "")}\""
        val arr = JSONObject(mbGet("https://musicbrainz.org/ws/2/recording?fmt=json&limit=15&query=${enc(q)}")).optJSONArray("recordings") ?: return emptyList()
        val out = LinkedHashMap<String, Match>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val ac = r.optJSONArray("artist-credit")
            val ar = buildString { if (ac != null) for (k in 0 until ac.length()) ac.getJSONObject(k).let { append(it.optString("name")).append(it.optString("joinphrase")) } }
            val rels = r.optJSONArray("releases") ?: continue
            for (k in 0 until rels.length()) {
                val rel = rels.getJSONObject(k)
                val rg = rel.optJSONObject("release-group") ?: JSONObject()
                val va = rel.optJSONArray("artist-credit")?.optJSONObject(0)?.optString("name") == "Various Artists"
                var s = score(artist, title, raw, dur, ar, r.optString("title"), rel.optString("title"), r.optDouble("length", 0.0) / 1000, va, known) ?: continue
                if ((rg.optJSONArray("secondary-types")?.length() ?: 0) > 0) s -= .4
                if (rg.optString("primary-type") == "Single") s -= .1
                if (rel.optString("status") != "Official") s -= .2
                val year = rel.optString("date").take(4)
                if (year.isEmpty()) s -= .05
                val key = norm(rel.optString("title"))
                if ((out[key]?.score ?: -9.0) < s)
                    out[key] = Match("MusicBrainz", ar, r.optString("title"), rel.optString("title"), year, null, null, null, ar,
                        "https://coverartarchive.org/release/${rel.optString("id")}/front-1200", s)
            }
        }
        return out.values.sortedByDescending { it.score }.take(6)
    }

    /** iTunes first, MusicBrainz fallback. Never throws. */
    fun find(artist: String, title: String, raw: String, dur: Int, known: Boolean): List<Match> {
        val it = runCatching { itunes(artist, title, raw, dur, known) }.getOrDefault(emptyList())
        if (it.any { m -> m.score >= CONFIDENT }) return it.take(6)
        val mb = runCatching { musicbrainz(artist, title, raw, dur, known) }.getOrDefault(emptyList())
        return (it + mb).sortedByDescending { m -> m.score }.take(6)
    }
}
