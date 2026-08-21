package com.example.nbbrowser

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebView
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.appbar.AppBarLayout
import java.util.concurrent.Executors
import nbproxy.Nbproxy

/**
 * The browser activity. It owns the WebView, the NetBird engine/proxy bring-up,
 * and the shared UI state; feature code (menus, find, history, downloads,
 * settings, shortcuts, the WebView clients) lives in the `Browser*.kt` files as
 * extension functions on this class, which is why the shared members below are
 * `internal` rather than `private`.
 */
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

    internal lateinit var web: NestedScrollWebView
    internal lateinit var refreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    internal lateinit var urlBar: EditText
    internal lateinit var progressBar: ProgressBar
    internal lateinit var btnReload: ImageButton
    internal lateinit var appBar: AppBarLayout
    internal lateinit var findBar: android.widget.LinearLayout
    internal lateinit var findInput: EditText
    internal lateinit var findCount: android.widget.TextView
    internal lateinit var fullscreenContainer: android.widget.FrameLayout

    internal var retries = 0
    internal val maxRetries = 15
    internal var engineStarted = false
    internal var engineStarting = false
    internal var proxyLive = false
    internal var proxyPort = 0
    internal var pendingUrl: String? = null
    internal var navToken = 0
    internal var pendingRetry: Runnable? = null
    internal var desktopMode = false
    internal var loading = false

    // Fullscreen (HTML5 video) state.
    internal var customView: View? = null
    internal var customViewCallback: android.webkit.WebChromeClient.CustomViewCallback? = null
    internal var savedOrientation = 0

    // Pending web-originated permission prompts.
    internal var pendingWebPermRequest: PermissionRequest? = null
    internal var pendingGeoOrigin: String? = null
    internal var pendingGeoCallback: GeolocationPermissions.Callback? = null

    // <input type=file> upload callback.
    internal var filePathCallback: ValueCallback<Array<Uri>>? = null

    internal val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val cb = filePathCallback
        filePathCallback = null
        cb?.onReceiveValue(
            android.webkit.WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    internal val webPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val req = pendingWebPermRequest
        pendingWebPermRequest = null
        if (req != null) {
            if (grants.values.all { it }) req.grant(req.resources) else req.deny()
        }
    }

    internal val geoLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val cb = pendingGeoCallback
        val origin = pendingGeoOrigin
        pendingGeoCallback = null
        pendingGeoOrigin = null
        cb?.invoke(origin, granted, false)
    }

    internal fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#245734")
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        refreshLayout = findViewById(R.id.refreshLayout)
        appBar = findViewById(R.id.appBar)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        refreshLayout.setOnRefreshListener {
            if (engineStarted && proxyLive) web.reload() else refreshLayout.isRefreshing = false
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
        desktopMode = prefs().getBoolean("desktopMode", false)
        applyUserAgent()

        web.webChromeClient = browserChromeClient()
        web.webViewClient = browserViewClient()
        WebView.setWebContentsDebuggingEnabled(true)

        // Downloads are routed through the tunnel proxy (DownloadManager would
        // bypass it and fail on overlay-only URLs).
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
            val saved = prefs().getString("lastUrl", null)
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
            customView != null -> hideCustomView()
            findBar.visibility == View.VISIBLE -> closeFind()
            web.url != null && !web.url!!.startsWith("file://") && web.canGoBack() -> web.goBack()
            else -> super.onBackPressed()
        }
    }

    // ---- Loading / reload-stop button ----

    internal fun updateLoading(value: Boolean) {
        if (loading == value) return
        loading = value
        btnReload.setImageResource(if (value) R.drawable.ic_stop else R.drawable.ic_reload)
        btnReload.contentDescription = if (value) "Stop" else "Reload"
    }

    // ---- Fullscreen system UI ----

    internal fun setSystemUiFullscreen(full: Boolean) {
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

    internal fun applyUserAgent() {
        web.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
    }

    internal fun forceWideViewport(view: WebView) {
        view.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "document.getElementsByTagName('head')[0].appendChild(m);}" +
                "m.setAttribute('content','width=1024');})();",
            null
        )
    }

    internal fun toggleDesktopMode() {
        desktopMode = !desktopMode
        prefs().edit().putBoolean("desktopMode", desktopMode).apply()
        applyUserAgent()
        toast(if (desktopMode) "Desktop site" else "Mobile site")
        if (web.url?.startsWith("http") == true) web.reload()
    }

    // ---- Connecting placeholder ----

    internal fun showConnecting(target: String?) {
        val host = target?.takeIf { it.startsWith("http") }
            ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        if (target != null && target.startsWith("http")) urlBar.setText(target)
        web.loadUrl(
            if (host.isNullOrEmpty()) CONNECTING_PAGE
            else "$CONNECTING_PAGE?host=${Uri.encode(host)}"
        )
    }

    // ---- Navigation & engine bring-up ----

    internal fun navigate(raw: String) {
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

    internal fun cancelStaleRetries() {
        navToken++
        pendingRetry?.let { web.removeCallbacks(it) }
        pendingRetry = null
    }

    internal fun startEngineThenLoad(target: String) {
        if (engineStarted) {
            web.loadUrl(target)
            return
        }
        if (engineStarting) {
            pendingUrl = target
            return
        }
        val prefs = prefs()
        val mgmt = prefs.getString("mgmtUrl", null)
            ?: intent.getStringExtra("mgmtUrl")
            ?: DEFAULT_MGMT
        val setupKey = prefs.getString("setupKey", null)?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra("setupKey")
        val jwt = intent.getStringExtra("jwt")
        pendingUrl = target
        engineStarting = true
        if (!target.startsWith("http")) web.loadUrl(target) else showConnecting(target)

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

    internal fun hasPerm(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    internal fun toast(msg: String) =
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        try {
            Nbproxy.stop()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}
