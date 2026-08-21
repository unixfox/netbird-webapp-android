package com.example.nbbrowser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors
import nbproxy.Nbproxy

class MainActivity : AppCompatActivity() {

    companion object {
        const val START_PAGE = "file:///android_asset/start.html"
        const val CONNECTING_PAGE = "file:///android_asset/connecting.html"
        const val VIEW_URL_ACTION = "com.example.nbbrowser.VIEW_URL"
        const val PREFS = "browser"
        const val DEFAULT_MGMT = "https://api.netbird.io"
        // A current desktop Chrome UA string used for "Request desktop site".
        const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    }

    private lateinit var web: WebView
    private lateinit var refreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private var retries = 0
    private val maxRetries = 15
    private var engineStarted = false
    private var engineStarting = false
    private var proxyLive = false
    private var pendingUrl: String? = null
    private var navToken = 0
    private var pendingRetry: Runnable? = null
    private var desktopMode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        refreshLayout = findViewById(R.id.refreshLayout)
        refreshLayout.setOnRefreshListener {
            if (engineStarted && proxyLive) {
                web.reload()
            } else {
                refreshLayout.isRefreshing = false
            }
        }
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progressBar)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnFwd = findViewById<Button>(R.id.btnFwd)
        val btnGo = findViewById<Button>(R.id.btnGo)
        val btnMenu = findViewById<Button>(R.id.btnMenu)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true

        // --- Accessibility / "real browser" behaviours ---
        // Pinch-to-zoom on every page (without the legacy on-screen +/- buttons).
        web.settings.setSupportZoom(true)
        web.settings.builtInZoomControls = true
        web.settings.displayZoomControls = false
        web.settings.loadWithOverviewMode = true
        web.settings.useWideViewPort = true
        // Honour the system font-size accessibility setting, like Chrome does.
        web.settings.textZoom = (resources.configuration.fontScale * 100).toInt()
        // Let pages that aren't dark-mode-aware be darkened when the device is
        // in dark theme, matching Chrome's "Force dark" behaviour.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(web.settings, true)
        }
        // "Request desktop site" preference, applied to the user-agent.
        desktopMode = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean("desktopMode", false)
        applyUserAgent()

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
                // Expose progress to TalkBack via the bar's live region.
                progressBar.contentDescription =
                    if (newProgress >= 100) null else "Loading, $newProgress percent"
            }

            override fun onReceivedTitle(view: WebView, title: String?) {
                // A meaningful label for the web area helps screen-reader users
                // know which page they are on.
                if (!title.isNullOrBlank()) web.contentDescription = title
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
                if (url.startsWith("http")) view.announceForAccessibility("Loading page")
            }

            override fun onPageFinished(view: WebView, url: String) {
                retries = 0
                progressBar.visibility = View.GONE
                refreshLayout.isRefreshing = false
                // Clear the address bar only on the local start page. The
                // connecting placeholder keeps the destination visible.
                if (url.startsWith(START_PAGE)) {
                    urlBar.setText("")
                } else if (url.startsWith("https://") || url.startsWith("http://")) {
                    urlBar.setText(url)
                    view.announceForAccessibility("Page loaded")
                    if (desktopMode) forceWideViewport(view)
                    getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putString("lastUrl", url).apply()
                }
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
                    val r = Runnable {
                        if (token == navToken) view.loadUrl(target)
                    }
                    pendingRetry = r
                    view.postDelayed(r, 3000)
                }
            }
        }
        WebView.setWebContentsDebuggingEnabled(true)

        btnBack.setOnClickListener {
            if (web.canGoBack()) web.goBack()
        }
        btnFwd.setOnClickListener {
            if (web.canGoForward()) web.goForward()
        }
        btnGo.setOnClickListener { navigate(urlBar.text.toString()) }
        urlBar.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                navigate(urlBar.text.toString())
                true
            } else {
                false
            }
        }
        urlBar.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                navigate(urlBar.text.toString())
                true
            } else {
                false
            }
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
        val url = web.url
        if (url != null && !url.startsWith("file://") && web.canGoBack()) {
            web.goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun navigate(raw: String) {
        val trimmed = raw.trim()
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
                if (url.startsWith("http")) {
                    showConnecting(url)
                }
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
                val port: Int
                if (setupKey != null) {
                    port = Nbproxy.startProxyWithSetupKey(
                        setupKey, mgmt, "android-embedded-browser", dir
                    ).toInt()
                } else {
                    port = Nbproxy.startProxy(jwt ?: "", mgmt, "android-embedded-browser", dir).toInt()
                }
                engineStarted = true
                runOnUiThread { waitForTunnelThenLoad(port) }
            } catch (t: Throwable) {
                engineStarting = false
                runOnUiThread {
                    web.loadData(
                        "StartProxy failed:\n" + t.message,
                        "text/plain",
                        "utf-8"
                    )
                }
            }
        }.start()
    }

    // Switches the WebView between the default mobile UA and a desktop UA.
    private fun applyUserAgent() {
        // A null string resets WebView to its built-in (mobile) user-agent.
        web.settings.userAgentString = if (desktopMode) DESKTOP_UA else null
        web.settings.useWideViewPort = true
        web.settings.loadWithOverviewMode = true
    }

    // Overrides a responsive site's viewport so it lays out at desktop width,
    // the way Chrome's "Request desktop site" does. UA alone only fools
    // server-side detection; this handles client-side responsive layouts.
    private fun forceWideViewport(view: WebView) {
        view.evaluateJavascript(
            "(function(){var m=document.querySelector('meta[name=viewport]');" +
                "if(!m){m=document.createElement('meta');m.name='viewport';" +
                "document.getElementsByTagName('head')[0].appendChild(m);}" +
                "m.setAttribute('content','width=1024');})();",
            null
        )
    }

    // Shows the themed "connecting" placeholder, passing the destination host
    // so the page can tell the user where they are being taken. Keeps the
    // full target URL in the address bar, like a browser loading a page.
    private fun showConnecting(target: String?) {
        val host = target?.takeIf { it.startsWith("http") }
            ?.let { runCatching { Uri.parse(it).host }.getOrNull() }
        if (target != null && target.startsWith("http")) urlBar.setText(target)
        web.loadUrl(
            if (host.isNullOrEmpty()) CONNECTING_PAGE
            else "$CONNECTING_PAGE?host=${Uri.encode(host)}"
        )
    }

    // Waits until at least one tunnel peer is up before applying the proxy
    // override and loading. Without this the WebView gives up after ~3s,
    // shows ERR_HTTP_RESPONSE_CODE_FAILURE, and the tunnel is rarely up yet
    // on a cold start (shortcut tap right after force-stop).
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
            // Callback = override is live. NOW it is safe to load.
            proxyLive = true
            runOnUiThread {
                cancelStaleRetries()
                web.loadUrl(pendingUrl ?: START_PAGE)
                pendingUrl = null
            }
        }
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        val menu = popup.menu
        val onHttp = web.url?.startsWith("http") == true

        val reload = menu.add("Reload").apply { isEnabled = onHttp }
        val home = menu.add("Home")
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
                reload -> { web.reload(); true }
                home -> { cancelStaleRetries(); web.loadUrl(START_PAGE); true }
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

                // Only bounce the tunnel when the server actually changed.
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

    private fun addToHomeScreen() {
        val url = web.url
        if (url == null || url.startsWith("file://")) {
            Toast.makeText(this, "Open a site first", Toast.LENGTH_SHORT).show()
            return
        }
        val host = Uri.parse(url).host ?: url
        val label = host.removePrefix("www.")

        // Fetch the site's favicon through the tunnel off the main thread.
        Thread {
            var icon: IconCompat? = null
            try {
                val bytes = Nbproxy.fetchFavicon(url)
                android.util.Log.i("Favicon", "fetched ${bytes?.size} bytes")
                if (bytes != null && bytes.isNotEmpty()) {
                    decodeSquare(bytes, 192)?.let {
                        icon = IconCompat.createWithBitmap(it)
                        android.util.Log.i("Favicon", "bitmap ok ${it.width}x${it.height}")
                    } ?: android.util.Log.e("Favicon", "decodeSquare returned null")
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
        // Keep the pinned copy in sync (icon/label refresh) with dynamic shortcuts.
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

    override fun onDestroy() {
        try {
            Nbproxy.stop()
        } catch (_: Exception) {
        }
        super.onDestroy()
    }
}