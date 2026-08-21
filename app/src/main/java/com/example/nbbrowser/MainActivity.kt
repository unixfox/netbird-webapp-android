package com.example.nbbrowser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.AppBarLayout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import nbproxy.Nbproxy

class MainActivity : AppCompatActivity() {

    companion object {
        const val START_PAGE = "file:///android_asset/start.html"
        const val CONNECTING_PAGE = "file:///android_asset/connecting.html"
        const val VIEW_URL_ACTION = "com.example.nbbrowser.VIEW_URL"
        const val PREFS = "browser"
        const val DEFAULT_MGMT = "https://api.netbird.io"
        const val KEY_HISTORY = "history"
        const val KEY_BOOKMARKS = "bookmarks"
        const val MAX_HISTORY = 300
        // A current desktop Chrome UA string used for "Request desktop site".
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    }

    private data class Entry(val url: String, val title: String)

    private lateinit var web: NestedScrollWebView
    private lateinit var refreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var btnReload: ImageButton
    private lateinit var appBar: AppBarLayout
    private lateinit var findBar: LinearLayout
    private lateinit var findInput: EditText
    private lateinit var findCount: android.widget.TextView
    private lateinit var fullscreenContainer: android.widget.FrameLayout

    private var retries = 0
    private val maxRetries = 15
    private var engineStarted = false
    private var engineStarting = false
    private var proxyLive = false
    private var proxyPort = 0
    private var pendingUrl: String? = null
    private var navToken = 0
    private var pendingRetry: Runnable? = null
    private var desktopMode = false
    private var loading = false

    // Fullscreen (HTML5 video) state.
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var savedOrientation = 0

    // Pending web-originated permission prompts.
    private var pendingWebPermRequest: PermissionRequest? = null
    private var pendingGeoOrigin: String? = null
    private var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // <input type=file> upload callback.
    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        cb?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    private val webPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingWebPermRequest
        pendingWebPermRequest = null
        if (req != null) {
            if (grants.values.all { it }) req.grant(req.resources) else req.deny()
        }
    }

    private val geoLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        cb?.invoke(origin, granted, false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#245734")
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        refreshLayout = findViewById(R.id.refreshLayout)
        appBar = findViewById(R.id.appBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        refreshLayout.setOnRefreshListener {
            if (engineStarted && proxyLive) {
                web.reload()
            } else {
                refreshLayout.isRefreshing = false
            }
        }
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progressBar)
        btnReload = findViewById(R.id.btnReload)
        val btnBack = findViewById<ImageButton>(R.id.btnBack)
        val btnFwd = findViewById<ImageButton>(R.id.btnFwd)
        val btnMenu = findViewById<ImageButton>(R.id.btnMenu)

        setupFindBar()

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.setSupportMultipleWindows(true)
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.setGeolocationEnabled(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        // --- Accessibility / "real browser" behaviours ---
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        web.settings.textZoom = (resources.configuration.fontScale * 100).toInt()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        }
        desktopMode = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("desktopMode", false)
        applyUserAgent()

        web.webChromeClient = browserChromeClient()
        web.webViewClient = browserViewClient()
        WebView.setWebContentsDebuggingEnabled(true)

        // Save files that the page hands off as downloads, routed through the
        // tunnel proxy (DownloadManager would bypass it and fail on overlay URLs).
        web.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            downloadThroughTunnel(url, contentDisposition, mimeType)
        }

        // Long-press link/image context menu, like Chrome's.
        web.setOnLongClickListener {
            val result = web.hitTestResult
            when (result.type) {
                WebView.HitTestResult.SRC_ANCHOR_TYPE,
                WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE,
                WebView.HitTestResult.IMAGE_TYPE -> { showHitMenu(result); true }
                else -> false
            }
        }

        btnBack.setOnClickListener { if (web.canGoBack()) web.goBack() }
        btnFwd.setOnClickListener { if (web.canGoForward()) web.goForward() }
        btnReload.setOnClickListener { if (loading) web.stopLoading() else web.reload() }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(urlBar.text.toString()); true
            } else false
        }
        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                navigate(urlBar.text.toString()); true
            } else false
        }
        btnMenu.setOnClickListener { showMenu(it) }

        val target = intent.getStringExtra("targetUrl")
        if (target != null) {
            startEngineThenLoad(target)
        } else {
            val saved = getSharedPreferences("browser", MODE_PRIVATE)
                .getString("lastUrl", null)
            startEngineThenLoad(saved ?: START_PAGE)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val target = intent.getStringExtra("targetUrl")
        if (target != null) {
            urlBar.setText(target)
            if (engineStarted && proxyLive) {
                cancelStaleRetries()
                web.loadUrl(target)
            } else {
                startEngineThenLoad(target)
            }
        }
    }

    override fun onBackPressed() {
        when {
            customView != null -> browserChromeClientHideCustomView()
            findBar.visibility == View.VISIBLE -> closeFind()
            web.url != null && !web.url!!.startsWith("file://") && web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    // ---- WebChromeClient ----

    private fun browserChromeClient() = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            if (newProgress >= 100) {
                progressBar.visibility = View.GONE
                setLoading(false)
            } else {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = newProgress
                setLoading(true)
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
                Toast.makeText(this@MainActivity, "No file picker available", Toast.LENGTH_SHORT).show()
                false
            }
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            runOnUiThread {
                val needed = mutableListOf<String>()
                for (res in request.resources) {
                    when (res) {
                        PermissionRequest.RESOURCE_VIDEO_CAPTURE ->
                            if (!hasPerm(Manifest.permission.CAMERA)) needed.add(Manifest.permission.CAMERA)
                        PermissionRequest.RESOURCE_AUDIO_CAPTURE ->
                            if (!hasPerm(Manifest.permission.RECORD_AUDIO)) needed.add(Manifest.permission.RECORD_AUDIO)
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
            if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION)) {
                callback.invoke(origin, true, false)
            } else {
                pendingGeoOrigin = origin
                pendingGeoCallback = callback
                geoLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            fullscreenContainer.visibility = View.VISIBLE
            setSystemUiFullscreen(true)
        }

        override fun onHideCustomView() {
            browserChromeClientHideCustomView()
        }

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: android.os.Message
        ): Boolean {
            // Open target=_blank / window.open() links in the same WebView.
            val transport = resultMsg.obj as WebView.WebViewTransport
            val temp = WebView(this@MainActivity)
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

    private fun browserChromeClientHideCustomView() {
        val v = customView ?: return
        fullscreenContainer.removeView(v)
        fullscreenContainer.visibility = View.GONE
        customView = null
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        setSystemUiFullscreen(false)
        requestedOrientation = savedOrientation
    }

    // ---- WebViewClient ----

    private fun browserViewClient() = object : WebViewClient() {
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
                Toast.makeText(this@MainActivity, "No app to open this link", Toast.LENGTH_SHORT).show()
                true
            }
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE
            setLoading(true)
            if (url.startsWith("http")) view.announceForAccessibility("Loading page")
        }

        override fun onPageFinished(view: WebView, url: String) {
            retries = 0
            progressBar.visibility = View.GONE
            refreshLayout.isRefreshing = false
            setLoading(false)
            if (url.startsWith(START_PAGE)) {
                urlBar.setText("")
            } else if (url.startsWith("https://") || url.startsWith("http://")) {
                urlBar.setText(url)
                view.announceForAccessibility("Page loaded")
                if (desktopMode) forceWideViewport(view)
                addHistory(url, view.title ?: url)
                getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putString("lastUrl", url).apply()
            }
        }

        override fun onReceivedSslError(
            view: WebView,
            handler: SslErrorHandler,
            error: android.net.http.SslError
        ) {
            AlertDialog.Builder(this@MainActivity)
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

    private fun sslErrorText(code: Int): String = when (code) {
        android.net.http.SslError.SSL_EXPIRED -> "expired"
        android.net.http.SslError.SSL_IDMISMATCH -> "hostname mismatch"
        android.net.http.SslError.SSL_NOTYETVALID -> "not yet valid"
        android.net.http.SslError.SSL_UNTRUSTED -> "untrusted authority"
        else -> "invalid"
    }

    // ---- Loading / reload-stop button ----

    private fun setLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        btnReload.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_reload)
        btnReload.contentDescription = if (value) "Stop" else "Reload"
    }

    // ---- Fullscreen system UI ----

    private fun setSystemUiFullscreen(full: Boolean) {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (full) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // ---- User agent / desktop site ----

    private fun applyUserAgent() {
        web.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
    }

    private fun forceWideViewport(view: WebView) {
        view.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "document.getElementsByTagName('head')[0].appendChild(m);}" +
                "m.setAttribute('content','width=1024');})();",
            null
        )
    }

    private fun toggleDesktopMode() {
        desktopMode = !desktopMode
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit().putBoolean("desktopMode", desktopMode).apply()
        applyUserAgent()
        Toast.makeText(
            this,
            if (desktopMode) "Desktop site" else "Mobile site",
            Toast.LENGTH_SHORT
        ).show()
        if (web.url?.startsWith("http") == true) web.reload()
    }

    // ---- Connecting placeholder ----

    private fun showConnecting(target: String?) {
        val host = target?.takeIf { it.startsWith("http") }
            ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        if (target != null && target.startsWith("http")) urlBar.setText(target)
        web.loadUrl(
            if (host.isNullOrEmpty()) CONNECTING_PAGE
            else "$CONNECTING_PAGE?host=${Uri.encode(host)}"
        )
    }

    // ---- Navigation ----

    private fun navigate(raw: String) {
        val trimmed = raw.trim()
        appBar.setExpanded(true, true)
        if (trimmed.isEmpty()) {
            cancelStaleRetries()
            web.loadUrl(START_PAGE)
            return
        }
        val url = if (trimmed.contains("://")) trimmed else "https://$trimmed"
        cancelStaleRetries()
        if (!engineStarted) {
            if (engineStarting) {
                pendingUrl = url
                if (url.startsWith("http")) showConnecting(url)
            } else {
                startEngineThenLoad(url)
            }
        } else if (!proxyLive) {
            pendingUrl = url
        } else {
            web.loadUrl(url)
        }
    }

    private fun cancelStaleRetries() {
        navToken++
        pendingRetry?.let { web.removeCallbacks(it) }
        pendingRetry = null
    }

    private fun startEngineThenLoad(target: String) {
        if (engineStarted) {
            web.loadUrl(target)
            return
        }
        if (engineStarting) {
            pendingUrl = target
            return
        }
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mgmt = prefs.getString("mgmtUrl", null)
            ?: intent.getStringExtra("mgmtUrl")
            ?: DEFAULT_MGMT
        val setupKey = prefs.getString("setupKey", null)?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra("setupKey")
        val jwt = intent.getStringExtra("jwt")
        pendingUrl = target
        engineStarting = true
        if (!target.startsWith("http")) {
            web.loadUrl(target)
        } else {
            showConnecting(target)
        }

        Thread {
            try {
                val dir = filesDir.absolutePath
                val port: Int = if (setupKey != null) {
                    Nbproxy.startProxyWithSetupKey(
                        setupKey, mgmt, "android-embedded-browser", dir
                    ).toInt()
                } else {
                    Nbproxy.startProxy(jwt ?: "", mgmt, "android-embedded-browser", dir).toInt()
                }
                proxyPort = port
                engineStarted = true
                runOnUiThread { waitForTunnelThenLoad(port) }
            } catch (t: Throwable) {
                engineStarting = false
                runOnUiThread {
                    web.loadData("StartProxy failed:\n" + t.message, "text/plain", "utf-8")
                }
            }
        }.start()
    }

    private fun waitForTunnelThenLoad(port: Int) {
        val pending = pendingUrl ?: START_PAGE
        if (!pending.startsWith("http")) {
            applyProxyThenLoad(port)
            return
        }
        showConnecting(pending)
        Thread {
            var waited = 0
            while (!Nbproxy.ready() && waited < 240) {
                Thread.sleep(500)
                waited++
            }
            runOnUiThread { applyProxyThenLoad(port) }
        }.start()
    }

    private fun applyProxyThenLoad(port: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            web.loadData("PROXY_OVERRIDE unsupported on this WebView", "text/plain", "utf-8")
            return
        }
        val config = ProxyConfig.Builder()
            .addProxyRule("http://127.0.0.1:$port")
            .addProxyRule("http://127.0.0.1:$port")
            .removeImplicitRules()
            .build()
        ProxyController.getInstance().setProxyOverride(
            config,
            Executors.newSingleThreadExecutor()
        ) {
            proxyLive = true
            runOnUiThread {
                cancelStaleRetries()
                web.loadUrl(pendingUrl ?: START_PAGE)
                pendingUrl = null
            }
        }
    }

    // ---- Overflow menu ----

    private fun showMenu(anchor: View) {
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
                home -> { cancelStaleRetries(); web.loadUrl(START_PAGE); true }
                bookmarks -> { showEntriesDialog("Bookmarks", KEY_BOOKMARKS); true }
                addBookmark -> { addBookmark(); true }
                history -> { showEntriesDialog("History", KEY_HISTORY); true }
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

    private fun copyLink() {
        val url = web.url ?: return
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("URL", url))
        Toast.makeText(this, "Link copied", Toast.LENGTH_SHORT).show()
    }

    private fun shareLink() {
        val url = web.url ?: return
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(send, "Share link"))
    }

    // ---- Long-press context menu ----

    private fun showHitMenu(result: WebView.HitTestResult) {
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

    private fun buildHitDialog(link: String?, image: String?) {
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

    private fun copyText(text: String, toast: String) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clip.setPrimaryClip(ClipData.newPlainText("text", text))
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
    }

    private fun shareText(text: String) {
        startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
                },
                "Share"
            )
        )
    }

    // ---- Find in page ----

    private fun setupFindBar() {
        findBar = findViewById(R.id.findBar)
        findInput = findViewById(R.id.findInput)
        findCount = findViewById(R.id.findCount)
        val prev = findViewById<Button>(R.id.findPrev)
        val next = findViewById<Button>(R.id.findNext)
        val close = findViewById<Button>(R.id.findClose)

        web.setFindListener { activeMatchOrdinal, numberOfMatches, _ ->
            findCount.text = if (numberOfMatches > 0) "${activeMatchOrdinal + 1}/$numberOfMatches" else "0"
        }
        findInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString().orEmpty()
                if (q.isEmpty()) { web.clearMatches(); findCount.text = "" } else web.findAllAsync(q)
            }
        })
        findInput.setOnEditorActionListener { _, _, _ -> web.findNext(true); true }
        prev.setOnClickListener { web.findNext(false) }
        next.setOnClickListener { web.findNext(true) }
        close.setOnClickListener { closeFind() }
    }

    private fun openFind() {
        appBar.setExpanded(true, true)
        findBar.visibility = View.VISIBLE
        findInput.setText("")
        findInput.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(findInput, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun closeFind() {
        web.clearMatches()
        findBar.visibility = View.GONE
        findCount.text = ""
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(findInput.windowToken, 0)
    }

    // ---- History & bookmarks ----

    private fun loadEntries(key: String): MutableList<Entry> {
        val raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(key, "[]") ?: "[]"
        val list = ArrayList<Entry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val u = o.getString("u")
                list.add(Entry(u, o.optString("t", u)))
            }
        } catch (_: Exception) {}
        return list
    }

    private fun saveEntries(key: String, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title)) }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(key, arr.toString()).apply()
    }

    private fun addHistory(url: String, title: String) {
        val list = loadEntries(KEY_HISTORY)
        list.removeAll { it.url == url }
        list.add(0, Entry(url, title))
        while (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        saveEntries(KEY_HISTORY, list)
    }

    private fun addBookmark() {
        val url = web.url ?: return
        if (!url.startsWith("http")) return
        val list = loadEntries(KEY_BOOKMARKS)
        if (list.any { it.url == url }) {
            Toast.makeText(this, "Already bookmarked", Toast.LENGTH_SHORT).show()
            return
        }
        list.add(0, Entry(url, web.title ?: url))
        saveEntries(KEY_BOOKMARKS, list)
        Toast.makeText(this, "Bookmark added", Toast.LENGTH_SHORT).show()
    }

    private fun showEntriesDialog(title: String, key: String) {
        val list = loadEntries(key)
        if (list.isEmpty()) {
            Toast.makeText(this, "No ${title.lowercase()} yet", Toast.LENGTH_SHORT).show()
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
            .setNeutralButton("Clear all") { _, _ -> saveEntries(key, emptyList()) }
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            navigate(list[pos].url)
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            list.removeAt(pos)
            saveEntries(key, list)
            adapter.notifyDataSetChanged()
            Toast.makeText(this, "Removed", Toast.LENGTH_SHORT).show()
            true
        }
        dialog.show()
    }

    // ---- Downloads (routed through the tunnel proxy) ----

    private fun downloadThroughTunnel(url: String, disposition: String?, mime: String?) {
        val fileName = URLUtil.guessFileName(url, disposition, mime)
        Toast.makeText(this, "Downloading $fileName…", Toast.LENGTH_SHORT).show()
        val cookies = CookieManager.getInstance().getCookie(url)
        val ua = web.settings.userAgentString
        Thread {
            try {
                val proxy = if (proxyPort > 0) {
                    Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))
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
                val dir = File(getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS), "")
                dir.mkdirs()
                val dest = File(dir, fileName)
                conn.inputStream.use { input ->
                    FileOutputStream(dest).use { out -> input.copyTo(out) }
                }
                conn.disconnect()
                runOnUiThread { onDownloadComplete(dest, mime) }
            } catch (t: Throwable) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun onDownloadComplete(file: File, mime: String?) {
        AlertDialog.Builder(this)
            .setTitle("Download complete")
            .setMessage(file.name)
            .setPositiveButton("Open") { _, _ -> openDownloadedFile(file, mime) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openDownloadedFile(file: File, mime: String?) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(view)
        } catch (e: Exception) {
            Toast.makeText(this, "No app to open ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    // ---- Settings ----

    private fun openSettings() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mgmtInput = EditText(this)
        mgmtInput.hint = "Management URL"
        mgmtInput.setText(prefs.getString("mgmtUrl", null) ?: DEFAULT_MGMT)
        mgmtInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_URI
        val keyInput = EditText(this)
        keyInput.hint = "Setup key (leave empty for JWT)"
        keyInput.setText(prefs.getString("setupKey", null) ?: "")
        keyInput.inputType = android.text.InputType.TYPE_CLASS_TEXT or
            android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD

        val origMgmt = prefs.getString("mgmtUrl", null) ?: DEFAULT_MGMT
        val origKey = prefs.getString("setupKey", null) ?: ""

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(48, 8, 48, 8)
        container.addView(mgmtInput)
        container.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("NetBird settings")
            .setMessage("Applied on save. Changing the server restarts the tunnel.")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val mgmt = mgmtInput.text.toString().trim()
                val key = keyInput.text.toString().trim()
                if (mgmt.isEmpty()) {
                    Toast.makeText(this, "Management URL cannot be empty", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().apply {
                    putString("mgmtUrl", mgmt)
                    if (key.isEmpty()) remove("setupKey") else putString("setupKey", key)
                }.apply()

                if (mgmt == origMgmt && key == origKey) return@setPositiveButton

                engineStarted = false
                engineStarting = false
                proxyLive = false
                Thread {
                    try {
                        Nbproxy.stop()
                    } catch (_: Exception) {
                    }
                }.start()
                Toast.makeText(this, "Restarting tunnel…", Toast.LENGTH_SHORT).show()
                web.postDelayed({
                    val current = web.url
                    startEngineThenLoad(current ?: START_PAGE)
                }, 1500)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Home-screen shortcuts ----

    private fun addToHomeScreen() {
        val url = web.url
        if (url == null || url.startsWith("file://")) {
            Toast.makeText(this, "Open a site first", Toast.LENGTH_SHORT).show()
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

    private fun pinShortcut(label: String, url: String, icon: IconCompat?) {
        val shortcut = ShortcutInfoCompat.Builder(this, "site_" + (Uri.parse(url).host ?: url).replace(".", "_"))
            .setIntent(
                Intent(this, MainActivity::class.java)
                    .setAction(VIEW_URL_ACTION)
                    .putExtra("targetUrl", url)
            )
            .setShortLabel(label)
            .setIcon(icon ?: IconCompat.createWithResource(this, R.mipmap.ic_launcher))
            .build()
        val ok = ShortcutManagerCompat.requestPinShortcut(this, shortcut, null)
        if (!ok) {
            Toast.makeText(this, "Launcher refused the shortcut", Toast.LENGTH_SHORT).show()
        }
        ShortcutManagerCompat.pushDynamicShortcut(this, shortcut)
    }

    private fun decodeSquare(bytes: ByteArray, size: Int): Bitmap? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val side = minOf(bmp.width, bmp.height)
            val cropX = (bmp.width - side) / 2
            val cropY = (bmp.height - side) / 2
            val cropped = Bitmap.createBitmap(bmp, cropX, cropY, side, side)
            Bitmap.createScaledBitmap(cropped, size, size, true)
        } catch (_: Throwable) {
            null
        }
    }

    private fun hasPerm(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {
        try {
            Nbproxy.stop()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
