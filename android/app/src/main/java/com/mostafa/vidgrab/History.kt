package com.mostafa.vidgrab

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

data class Done(val title: String, val artist: String, val thumb: String?, val uri: String, val mime: String, val time: Long)

object History {
    val items = MutableStateFlow<List<Done>>(emptyList())
    private fun p(c: Context) = c.getSharedPreferences("history", Context.MODE_PRIVATE)
    fun load(c: Context) {
        val a = JSONArray(p(c).getString("h", "[]"))
        items.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
            Done(it.getString("t"), it.optString("a"), it.optString("th").ifBlank { null }, it.getString("u"), it.getString("m"), it.getLong("ts")) }
    }
    @Synchronized fun add(c: Context, d: Done) {
        load(c)
        val l = (listOf(d) + items.value).take(200)
        p(c).edit().putString("h", JSONArray(l.map { JSONObject().put("t", it.title).put("a", it.artist).put("th", it.thumb ?: "")
            .put("u", it.uri).put("m", it.mime).put("ts", it.time) }).toString()).apply()
        items.value = l
    }
    fun remove(c: Context, d: Done) { load(c); val l = items.value - d
        p(c).edit().putString("h", JSONArray(l.map { JSONObject().put("t", it.title).put("a", it.artist).put("th", it.thumb ?: "")
            .put("u", it.uri).put("m", it.mime).put("ts", it.time) }).toString()).apply(); items.value = l }
}
