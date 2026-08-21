package com.example.nbbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.WebView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu

/** The overflow (⋮) menu. */
internal fun MainActivity.showMenu(anchor: View) {
    val popup = PopupMenu(this, anchor)
    val menu = popup.menu
    val onHttp = web.url?.startsWith("http") == true

    val home = menu.add("Home")
    val bookmarks = menu.add("Bookmarks")
    val addBookmark = menu.add("Add bookmark").apply { isEnabled = onHttp }
    val history = menu.add("History")
    val find = menu.add("Find in page").apply { isEnabled = onHttp }
    val desktop = menu.add("Request desktop site").apply {
        isCheckable = true
        isChecked = desktopMode
    }
    val addShortcut = menu.add("Add to home screen").apply { isEnabled = onHttp }
    val copy = menu.add("Copy link").apply { isEnabled = onHttp }
    val share = menu.add("Share…").apply { isEnabled = onHttp }
    val settings = menu.add("NetBird settings")

    popup.setOnMenuItemClickListener { item ->
        when (item) {
            home -> { cancelStaleRetries(); web.loadUrl(MainActivity.START_PAGE); true }
            bookmarks -> { showEntriesDialog("Bookmarks", MainActivity.KEY_BOOKMARKS); true }
            addBookmark -> { addCurrentBookmark(); true }
            history -> { showEntriesDialog("History", MainActivity.KEY_HISTORY); true }
            find -> { openFind(); true }
            desktop -> { toggleDesktopMode(); true }
            addShortcut -> { addToHomeScreen(); true }
            copy -> { copyLink(); true }
            share -> { shareLink(); true }
            settings -> { openSettings(); true }
            else -> false
        }
    }
    popup.show()
}

internal fun MainActivity.copyLink() {
    val url = web.url ?: return
    copyText(url, "Link copied")
}

internal fun MainActivity.shareLink() {
    val url = web.url ?: return
    shareText(url)
}

// ---- Long-press link/image context menu ----

internal fun MainActivity.showHitMenu(result: WebView.HitTestResult) {
    val imageUrl = result.extra?.takeIf {
        result.type == WebView.HitTestResult.IMAGE_TYPE ||
            result.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
    }
    if (result.type == WebView.HitTestResult.IMAGE_TYPE) {
        buildHitDialog(null, imageUrl)
        return
    }
    // Anchor (or image-anchor): fetch the href asynchronously.
    val handler = Handler(Looper.getMainLooper()) { msg ->
        buildHitDialog(msg.data.getString("url"), imageUrl)
        true
    }
    web.requestFocusNodeHref(handler.obtainMessage())
}

private fun MainActivity.buildHitDialog(link: String?, image: String?) {
    val labels = ArrayList<String>()
    val actions = ArrayList<() -> Unit>()
    if (!link.isNullOrEmpty()) {
        labels.add("Open link"); actions.add { navigate(link) }
        labels.add("Copy link"); actions.add { copyText(link, "Link copied") }
        labels.add("Share link"); actions.add { shareText(link) }
    }
    if (!image.isNullOrEmpty()) {
        labels.add("Open image"); actions.add { navigate(image) }
        labels.add("Download image"); actions.add { downloadThroughTunnel(image, null, null) }
        labels.add("Copy image link"); actions.add { copyText(image, "Image link copied") }
    }
    if (labels.isEmpty()) return
    AlertDialog.Builder(this)
        .setTitle(link ?: image)
        .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
        .show()
}

internal fun MainActivity.copyText(text: String, msg: String) {
    val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clip.setPrimaryClip(ClipData.newPlainText("text", text))
    toast(msg)
}

internal fun MainActivity.shareText(text: String) {
    startActivity(
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
            },
            "Share"
        )
    )
}
