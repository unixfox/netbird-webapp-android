package com.example.nbbrowser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog

/** WebChromeClient wiring: progress, uploads, permissions, fullscreen, new windows. */
internal fun MainActivity.browserChromeClient(): WebChromeClient {
    val activity = this
    return object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 100) {
                progressBar.visibility = View.GONE
                updateLoading(false)
            } else {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = newProgress
                updateLoading(true)
            }
            progressBar.contentDescription =
                if (newProgress >= 100) null else "Loading, $newProgress percent"
        }

        override fun onReceivedTitle(view: WebView, title: String?) {
            if (!title.isNullOrBlank()) web.contentDescription = title
        }

        override fun onShowFileChooser(
            view: WebView,
            callback: ValueCallback<Array<Uri>>,
            params: FileChooserParams
        ): Boolean {
            filePathCallback?.onReceiveValue(null)
            filePathCallback = callback
            return try {
                fileChooserLauncher.launch(params.createIntent())
                true
            } catch (e: ActivityNotFoundException) {
                filePathCallback = null
                toast("No file picker available")
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val needed = mutableListOf<String>()
                for (res in request.resources) {
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            if (!hasPerm(android.Manifest.permission.CAMERA))
                                needed.add(android.Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            if (!hasPerm(android.Manifest.permission.RECORD_AUDIO))
                                needed.add(android.Manifest.permission.RECORD_AUDIO)
                    }
                }
                if (needed.isEmpty()) {
                    request.grant(request.resources)
                } else {
                    pendingWebPermRequest = request
                    webPermLauncher.launch(needed.toTypedArray())
                }
            }
        }

        override fun onPermissionRequestCanceled(request: PermissionRequest) {
            pendingWebPermRequest = null
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String,
            callback: GeolocationPermissions.Callback
        ) {
            if (hasPerm(android.Manifest.permission.ACCESS_FINE_LOCATION)) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                geoLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            if (customView != null) {
                callback.onCustomViewHidden()
                return
            }
            customView = view
            customViewCallback = callback
            savedOrientation = requestedOrientation
            fullscreenContainer.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.visibility = View.VISIBLE
            setSystemUiFullscreen(true)
        }

        override fun onHideCustomView() {
            hideCustomView()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            // Open target=_blank / window.open() links in the same WebView.
            val transport = resultMsg.obj as WebView.WebViewTransport
            val temp = WebView(activity)
            temp.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    v: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    navigate(request.url.toString())
                    temp.destroy()
                    return true
                }
            }
            transport.webView = temp
            resultMsg.sendToTarget()
            return true
        }
    }
}

internal fun MainActivity.hideCustomView() {
    val v = customView ?: return
    fullscreenContainer.removeView(v)
    fullscreenContainer.visibility = View.GONE
    customView = null
    customViewCallback?.onCustomViewHidden()
    customViewCallback = null
    setSystemUiFullscreen(false)
    requestedOrientation = savedOrientation
}

/** WebViewClient wiring: scheme handling, load lifecycle, SSL errors, retry-on-error. */
internal fun MainActivity.browserViewClient(): WebViewClient {
    val activity = this
    return object : WebViewClient() {
        override fun shouldOverrideUrlLoading(
            view: WebView,
            request: WebResourceRequest
        ): Boolean {
            val url = request.url.toString()
            if (url.startsWith("http://") || url.startsWith("https://") ||
                url.startsWith("file://") || url.startsWith("about:")
            ) {
                return false // let the WebView load it through the tunnel
            }
            // Hand off mailto:, tel:, intent://, geo:, custom schemes to the OS.
            return try {
                val intent = if (url.startsWith("intent:")) {
                    Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                } else {
                    Intent(Intent.ACTION_VIEW, request.url)
                }
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } catch (e: Exception) {
                toast("No app to open this link")
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE
            updateLoading(true)
            if (url.startsWith("http")) view.announceForAccessibility("Loading page")
        }

        override fun onPageFinished(view: WebView, url: String) {
            retries = 0
            progressBar.visibility = View.GONE
            refreshLayout.isRefreshing = false
            updateLoading(false)
            if (url.startsWith(MainActivity.START_PAGE)) {
                urlBar.setText("")
            } else if (url.startsWith("https://") || url.startsWith("http://")) {
                urlBar.setText(url)
                view.announceForAccessibility("Page loaded")
                if (desktopMode) forceWideViewport(view)
                prefs().addHistory(MainActivity.KEY_HISTORY, url, view.title ?: url, MainActivity.MAX_HISTORY)
                prefs().edit().putString("lastUrl", url).apply()
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: SslError
        ) {
            AlertDialog.Builder(activity)
                .setTitle("Connection is not private")
                .setMessage(
                    "The certificate for ${error.url} could not be verified " +
                        "(${sslErrorText(error.primaryError)}).\n\nProceed anyway?"
                )
                .setPositiveButton("Proceed") { _, _ -> handler.proceed() }
                .setNegativeButton("Back") { _, _ -> handler.cancel() }
                .setOnCancelListener { handler.cancel() }
                .show()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError
        ) {
            if (request.isForMainFrame && retries < maxRetries &&
                !request.url.toString().startsWith("file://")
            ) {
                retries++
                val token = navToken
                val target = request.url.toString()
                val r = Runnable { if (token == navToken) view.loadUrl(target) }
                pendingRetry = r
                view.postDelayed(r, 3000)
            }
        }
    }
}

private fun sslErrorText(code: Int): String = when (code) {
    SslError.SSL_EXPIRED -> "expired"
    SslError.SSL_IDMISMATCH -> "hostname mismatch"
    SslError.SSL_NOTYETVALID -> "not yet valid"
    SslError.SSL_UNTRUSTED -> "untrusted authority"
    else -> "invalid"
}
