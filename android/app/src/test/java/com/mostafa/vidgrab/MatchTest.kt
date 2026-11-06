package com.mostafa.vidgrab

import org.junit.Assert.*
import org.junit.Test

class DupesTest {
    private fun lib(t: String, a: String, al: String = "", d: Int = 231) = LibSong(1, t, a, al, d, "/x", 0)
    private fun s(t: String, a: String, d: Int, l: LibSong) = Dupes.score(t, a, d, l).also { println("DUPE %.3f  [%s / %s %ds] vs [%s / %s %ds]".format(it, t, a, d, l.title, l.artist, l.durationSec)) }
    @Test fun cases() {
        assertTrue(s("Use Somebody", "Kings of Leon", 231, lib("Kings Of Leon - Use Somebody (Official Video)", "<unknown>")) >= Dupes.THRESHOLD)
        assertTrue(s("Use Somebody", "Kings of Leon", 231, lib("Kings Of Leon - Use Somebody...", "Unknown artist")) >= Dupes.THRESHOLD)
        assertTrue(s("Use Somebody (Remastered)", "Kings of Leon", 231, lib("Use Somebody", "Kings Of Leon", "Only By the Night", 230)) >= Dupes.THRESHOLD)
        assertTrue(s("Use Somebody - Remastered 2009", "Kings of Leon feat. X", 233, lib("Use Somebody", "Kings Of Leon")) >= Dupes.THRESHOLD)
        assertTrue(s("تملّي معاك", "عمرو دياب", 271, lib("تملي معاك", "عمرو دياب", d = 269)) >= Dupes.THRESHOLD)
        assertTrue(s("Use Somebody", "Kings of Leon", 231, lib("Somebody That I Used to Know", "Gotye", d = 244)) < Dupes.THRESHOLD)
        assertTrue(s("Somebody That I Used to Know", "Gotye", 244, lib("Use Somebody", "Kings of Leon", d = 240)) < Dupes.THRESHOLD)
        assertTrue(s("Use Somebody", "Kings of Leon", 231, lib("Use Somebody", "Kings of Leon", d = 300)) < Dupes.THRESHOLD)  // duration off
        assertTrue(s("Sex on Fire", "Kings of Leon", 203, lib("Use Somebody", "Kings of Leon", d = 205)) < Dupes.THRESHOLD)
        assertTrue(s("Use Somebody", "Kings of Leon", 231, lib("Use Somebody", "Pixie Lott", d = 232)) < Dupes.THRESHOLD)
    }
}

class OnlineMatchTest {
    private fun run(artist: String, title: String, raw: String, dur: Int, known: Boolean): Match? {
        val r = Matcher.find(artist, title, raw, dur, known)
        println("MATCH [$raw] -> ${r.size} candidates")
        r.forEach { println("   %.2f %s | %s | %s | %s | %s | genre=%s trk=%s disc=%s aa=%s | %s".format(it.score, it.src, it.artist, it.title, it.album, it.year, it.genre, it.track, it.disc, it.albumArtist, it.cover)) }
        return r.firstOrNull()?.takeIf { it.score >= Matcher.CONFIDENT }
    }
    @Test fun kol() { val m = run("Kings Of Leon", "Use Somebody", "Kings Of Leon - Use Somebody (Official Video)", 231, true)!!
        assertEquals("Kings of Leon", m.artist); assertEquals("only by the night", m.album.lowercase()); assertEquals("2008", m.year) }
    @Test fun sky() { assertNotNull(run("Elektronomia", "Sky High", "Elektronomia - Sky High | Progressive House | NCS - Copyright Free Music", 238, true)) }
    @Test fun amr() { val raw = "Tamally Ma'ak   Amr Diab   تملي معاك نسخة أصلية   جودة عالية"
        assertNotNull(run("Amr Diab Official", raw, raw, 271, false)) }
    @Test fun mbOnly() { val r = Matcher.musicbrainz("Kings Of Leon", "Use Somebody", "Kings Of Leon - Use Somebody (Official Video)", 231, true)
        r.take(3).forEach { println("MB %.2f %s | %s | %s | %s".format(it.score, it.artist, it.album, it.year, it.cover)) }; assertTrue(r.isNotEmpty()) }
}
