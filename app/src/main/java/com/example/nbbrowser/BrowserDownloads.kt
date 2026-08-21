package com.example.nbbrowser

import android.content.Intent
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Downloads a URL through the tunnel proxy and offers to open it. DownloadManager
 * would use the system network and fail on overlay-only URLs, so we stream it
 * ourselves via the local proxy port, forwarding the page's cookies and UA.
 */
internal fun MainActivity.downloadThroughTunnel(url: String, disposition: String?, mime: String?) {
    val fileName = URLUtil.guessFileName(url, disposition, mime)
    toast("Downloading $fileName…")
    val cookies = CookieManager.getInstance().getCookie(url)
    val ua = web.settings.userAgentString
    val port = proxyPort
    Thread {
        try {
            val proxy = if (port > 0) {
                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", port))
            } else {
                Proxy.NO_PROXY
            }
            val conn = URL(url).openConnection(proxy) as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            if (!cookies.isNullOrEmpty()) conn.setRequestProperty("Cookie", cookies)
            if (!ua.isNullOrEmpty()) conn.setRequestProperty("User-Agent", ua)
            conn.connect()
            val dir = File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "")
            dir.mkdirs()
            val dest = File(dir, fileName)
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            conn.disconnect()
            runOnUiThread { onDownloadComplete(dest, mime) }
        } catch (t: Throwable) {
            runOnUiThread {
                android.widget.Toast.makeText(
                    this, "Download failed: ${t.message}", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        }
    }.start()
}

private fun MainActivity.onDownloadComplete(file: File, mime: String?) {
    AlertDialog.Builder(this)
        .setTitle("Download complete")
        .setMessage(file.name)
        .setPositiveButton("Open") { _, _ -> openDownloadedFile(file, mime) }
        .setNegativeButton("Close", null)
        .show()
}

private fun MainActivity.openDownloadedFile(file: File, mime: String?) {
    try {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime ?: "*/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(view)
    } catch (e: Exception) {
        toast("No app to open ${file.name}")
    }
}
