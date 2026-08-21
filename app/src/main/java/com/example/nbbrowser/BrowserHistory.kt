package com.example.nbbrowser

import android.text.TextUtils
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

internal fun MainActivity.addCurrentBookmark() {
    val url = web.url ?: return
    if (!url.startsWith("http")) return
    val added = prefs().addBookmark(MainActivity.KEY_BOOKMARKS, url, web.title ?: url)
    toast(if (added) "Bookmark added" else "Already bookmarked")
}

/** Shared list dialog for History and Bookmarks: tap to open, long-press to delete. */
internal fun MainActivity.showEntriesDialog(title: String, key: String) {
    val list = prefs().loadEntries(key)
    if (list.isEmpty()) {
        toast("No ${title.lowercase()} yet")
        return
    }
    val lv = ListView(this)
    // Two-line rows: bold title on top, dimmed URL below, both clipped to a
    // single line so long SPA URLs don't wrap into a wall of text.
    val adapter = object : ArrayAdapter<Entry>(
        this, android.R.layout.simple_list_item_2, android.R.id.text1, list
    ) {
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = super.getView(position, convertView, parent)
            val e = getItem(position)
            val t1 = v.findViewById<TextView>(android.R.id.text1)
            val t2 = v.findViewById<TextView>(android.R.id.text2)
            t1.text = e?.title?.takeIf { it.isNotBlank() } ?: e?.url
            t1.maxLines = 1
            t1.ellipsize = TextUtils.TruncateAt.END
            t2.text = e?.url
            t2.maxLines = 1
            t2.ellipsize = TextUtils.TruncateAt.END
            return v
        }
    }
    lv.adapter = adapter
    val dialog = AlertDialog.Builder(this)
        .setTitle(title)
        .setView(lv)
        .setNegativeButton("Close", null)
        .setNeutralButton("Clear all") { _, _ -> prefs().saveEntries(key, emptyList()) }
        .create()
    lv.setOnItemClickListener { _, _, pos, _ ->
        dialog.dismiss()
        navigate(list[pos].url)
    }
    lv.setOnItemLongClickListener { _, _, pos, _ ->
        list.removeAt(pos)
        prefs().saveEntries(key, list)
        adapter.notifyDataSetChanged()
        toast("Removed")
        true
    }
    dialog.show()
}
