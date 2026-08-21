package com.example.nbbrowser

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** A visited page or bookmark: its URL and page title. */
data class Entry(val url: String, val title: String)

/**
 * History and bookmarks are stored as JSON arrays of {u,t} in SharedPreferences.
 * These extensions are the only place that (de)serialisation lives.
 */
fun SharedPreferences.loadEntries(key: String): MutableList<Entry> {
    val raw = getString(key, "[]") ?: "[]"
    val list = ArrayList<Entry>()
    try {
        val arr = JSONArray(raw)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val u = o.getString("u")
            list.add(Entry(u, o.optString("t", u)))
        }
    } catch (_: Exception) {
    }
    return list
}

fun SharedPreferences.saveEntries(key: String, list: List<Entry>) {
    val arr = JSONArray()
    list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
    edit().putString(key, arr.toString()).apply()
}

/** Prepends [url] to history, de-duplicating and capping at [max] entries. */
fun SharedPreferences.addHistory(key: String, url: String, title: String, max: Int) {
    val list = loadEntries(key)
    list.removeAll { it.url == url }
    list.add(0, Entry(url, title))
    while (list.size > max) list.removeAt(list.size - 1)
    saveEntries(key, list)
}

/** Adds a bookmark unless one with the same URL already exists. Returns false if duplicate. */
fun SharedPreferences.addBookmark(key: String, url: String, title: String): Boolean {
    val list = loadEntries(key)
    if (list.any { it.url == url }) return false
    list.add(0, Entry(url, title))
    saveEntries(key, list)
    return true
}
