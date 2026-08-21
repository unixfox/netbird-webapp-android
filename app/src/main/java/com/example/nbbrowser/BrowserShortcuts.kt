package com.example.nbbrowser

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import nbproxy.Nbproxy

/** Pins the current site to the launcher, using its favicon (fetched through the tunnel). */
internal fun MainActivity.addToHomeScreen() {
    val url = web.url
    if (url == null || url.startsWith("file://")) {
        toast("Open a site first")
        return
    }
    val host = Uri.parse(url).host ?: url
    val label = host.removePrefix("www.")

    Thread {
        var icon: IconCompat? = null
        try {
            val bytes = Nbproxy.fetchFavicon(url)
            if (bytes != null && bytes.isNotEmpty()) {
                decodeSquare(bytes, 192)?.let { icon = IconCompat.createWithBitmap(it) }
            }
        } catch (t: Throwable) {
            android.util.Log.e("Favicon", "fetch failed", t)
        }
        val favicon = icon
        runOnUiThread { pinShortcut(label, url, favicon) }
    }.start()
}

private fun MainActivity.pinShortcut(label: String, url: String, icon: IconCompat?) {
    val shortcut = ShortcutInfoCompat.Builder(
        this, "site_" + (Uri.parse(url).host ?: url).replace(".", "_")
    )
        .setIntent(
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.VIEW_URL_ACTION)
                .putExtra("targetUrl", url)
        )
        .setShortLabel(label)
        .setIcon(icon ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher))
        .build()
    if (!ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)) {
        toast("Launcher refused the shortcut")
    }
    ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
}

private fun decodeSquare(bytes: ByteArray, size: Int): Bitmap? {
    return try {
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(bmp.width, bmp.height)
        val cropped = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        Bitmap.createScaledBitmap(cropped, size, size, true)
    } catch (_: Throwable) {
        null
    }
}
