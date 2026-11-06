package com.mostafa.vidgrab

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MediaInfoTest {
    private fun yt(vararg kv: Pair<String, String>) = JSONObject().apply { kv.forEach { (k, v) -> put(k, v) } }
    private fun names(i: MediaInfo) = listOf(i.artist, i.title, i.rawTitle, i.known)
    private val full = listOf(  // yt-dlp fields -> artist, title, rawTitle, known (as parse named them before the shared helper)
        yt("title" to "Kings Of Leon - Use Somebody (Official Video)", "uploader" to "kingsofleonVEVO") to
            listOf("Kings Of Leon", "Use Somebody", "Kings Of Leon - Use Somebody (Official Video)", true),
        yt("title" to "Elektronomia - Sky High | Progressive House | NCS - Copyright Free Music", "uploader" to "NoCopyrightSounds") to
            listOf("Elektronomia", "Sky High", "Elektronomia - Sky High | Progressive House | NCS - Copyright Free Music", true),
        yt("title" to "Amr Diab – Tamally Maak [Official Music Video]", "uploader" to "Amr Diab") to
            listOf("Amr Diab", "Tamally Maak", "Amr Diab – Tamally Maak [Official Music Video]", true),
        yt("title" to "Use Somebody (Lyrics)", "uploader" to "Kings of Leon - Topic") to
            listOf("Kings of Leon", "Use Somebody", "Use Somebody (Lyrics)", false),
        yt("title" to "Use Somebody", "artist" to "Kings of Leon", "track" to "Use Somebody (Remastered)", "uploader" to "Kings of Leon - Topic") to
            listOf("Kings of Leon", "Use Somebody (Remastered)", "Use Somebody", true),
        yt("title" to "Tamally Maak (Official Lyrics Video) | Amr Diab HD", "uploader" to "Rotana") to
            listOf("Rotana", "Tamally Maak", "Tamally Maak (Official Lyrics Video) | Amr Diab HD", false),
    )

    @Test fun parseNames() { full.forEach { (j, want) -> assertEquals(want, names(MediaInfo.parse("u", j))) } }

    private fun oembed(title: String, author: String) = MediaInfo.oembed("https://youtu.be/gnhXHvRoUd0?si=x", JSONObject()
        .put("title", title).put("author_name", author).put("thumbnail_url", "https://i.ytimg.com/vi/gnhXHvRoUd0/hqdefault.jpg").put("type", "video"))

    @Test fun oembedPartial() {
        val i = oembed("Kings Of Leon - Use Somebody (Official Video)", "kingsofleonVEVO")
        assertTrue(i.partial); assertFalse(MediaInfo.parse("u", yt("title" to "x")).partial)
        assertEquals(listOf("Kings Of Leon", "Use Somebody", "Kings Of Leon - Use Somebody (Official Video)", true), names(i))
        assertEquals("https://youtu.be/gnhXHvRoUd0?si=x", i.url)
        assertEquals("https://i.ytimg.com/vi/gnhXHvRoUd0/hqdefault.jpg", i.thumb)
        assertEquals(0, i.duration); assertNull(i.ytAlbum)
        assertEquals(listOf(VideoChoice(null, "Best", ""), VideoChoice(1080, "1080p", ""), VideoChoice(720, "720p", ""), VideoChoice(480, "480p", "")), i.choices)
    }
    @Test fun oembedTopicAndJunk() {
        assertEquals(listOf("Kings of Leon", "Use Somebody", "Use Somebody (Lyrics)", false), names(oembed("Use Somebody (Lyrics)", "Kings of Leon - Topic")))
        assertEquals("Sky High", oembed("Elektronomia - Sky High | Progressive House | NCS - Copyright Free Music", "NoCopyrightSounds").title)
        assertEquals("Tamally Maak", oembed("Amr Diab – Tamally Maak [Official Music Video]", "Amr Diab").title)
    }
    @Test fun oembedNamesLikeParse() {  // author_name plays yt-dlp's uploader role
        full.map { it.first }.filter { !it.has("artist") }.forEach { j ->
            assertEquals(names(MediaInfo.parse("u", j)), names(oembed(j.getString("title"), j.getString("uploader")))) }
    }
    @Test fun oembedWithoutTitleFails() { assertThrows(Exception::class.java) { MediaInfo.oembed("u", JSONObject().put("author_name", "x")) } }
}

class WorkerMatchTest {
    private fun m(score: Double) = Match("iTunes", "Kings of Leon", "Use Somebody", "Only By the Night", "2008", "Rock", 4, 1, "Kings of Leon", "c", score)
    private var searched = false
    private fun find(vararg r: Match): () -> List<Match> = { searched = true; r.toList() }

    @Test fun pendingComputes() {
        assertEquals(m(2.0), DownloadWorker.matchFor(true, null, find(m(2.0), m(1.8)))); assertTrue(searched)
    }
    @Test fun pendingNeedsConfidentMatch() {  // same rule as the share sheet: the top result only when it is >= CONFIDENT
        assertNull(DownloadWorker.matchFor(true, null, find(m(Matcher.CONFIDENT - .1))))
        assertNull(DownloadWorker.matchFor(true, null, find()))
    }
    @Test fun computedNoMatchSkips() { assertNull(DownloadWorker.matchFor(false, null, find(m(2.0)))); assertFalse(searched) }
    @Test fun givenMatchUsed() { val g = m(1.75); assertSame(g, DownloadWorker.matchFor(false, g, find(m(2.0)))); assertFalse(searched) }
}

class ListingTest {  // label, artist and thumbnail for the download list, notifications and history
    private fun m(cover: String) = Match("iTunes", "Kings of Leon", "Use Somebody", "Only By the Night", "2008", "Rock", 4, 1, "Kings of Leon", cover, 2.0)
    private val yt = "https://i.ytimg.com/vi/gnhXHvRoUd0/hqdefault.jpg"

    @Test fun officialMatch() {
        assertEquals(Triple("Kings of Leon - Use Somebody", "Kings of Leon", "https://is1-ssl.mzstatic.com/image/thumb/a/300x300bb.jpg"),
            DownloadWorker.listing(m("https://is1-ssl.mzstatic.com/image/thumb/a/1000x1000bb.jpg"), "kingsofleonVEVO", "Use Somebody (Official Video)", yt))
        assertEquals("https://coverartarchive.org/release/abc/front-250",
            DownloadWorker.listing(m("https://coverartarchive.org/release/abc/front-1200"), "x", "y", yt).third)
    }
    @Test fun youtubeInfo() {
        assertEquals(Triple("Kings Of Leon - Use Somebody", "Kings Of Leon", yt), DownloadWorker.listing(null, "Kings Of Leon", "Use Somebody", yt))
        assertNull(DownloadWorker.listing(null, "a", "t", null).third)
    }
}
