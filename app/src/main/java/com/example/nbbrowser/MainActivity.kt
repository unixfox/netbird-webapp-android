package com.example.nbbrowser

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
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import java.util.concurrent.Executors
import nbproxy.Nbproxy

class MainActivity : AppCompatActivity() {

    companion object {
        const val START_PAGE = "file:///android_asset/start.html"
        const val VIEW_URL_ACTION = "com.example.nbbrowser.VIEW_URL"
        const val PREFS = "browser"
        const val DEFAULT_MGMT = "https://api.netbird.io"
    }

    private lateinit var web: WebView
    private lateinit var urlBar: EditText
    private lateinit var progressBar: ProgressBar
    private var retries = 0
    private val maxRetries = 15
    private var engineStarted = false
    private var proxyLive = false
    private var pendingUrl: String? = null
    private var navToken = 0
    private var pendingRetry: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        web = findViewById(R.id.webView)
        urlBar = findViewById(R.id.urlBar)
        progressBar = findViewById(R.id.progressBar)
        val btnBack = findViewById<Button>(R.id.btnBack)
        val btnFwd = findViewById<Button>(R.id.btnFwd)
        val btnGo = findViewById<Button>(R.id.btnGo)
        val btnAdd = findViewById<Button>(R.id.btnAdd)
        val btnSettings = findViewById<Button>(R.id.btnSettings)

        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                if (newProgress >= 100) {
                    progressBar.visibility = View.GONE
                } else {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            }
        }
        web.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progressBar.progress = 0
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                retries = 0
                progressBar.visibility = View.GONE
                if (url.startsWith("file://")) {
                    urlBar.setText("")
                } else if (url.startsWith("https://") || url.startsWith("http://")) {
                    urlBar.setText(url)
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
        btnAdd.setOnClickListener { addToHomeScreen() }
        btnSettings.setOnClickListener { openSettings() }

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
            startEngineThenLoad(url)
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
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val mgmt = prefs.getString("mgmtUrl", null)
            ?: intent.getStringExtra("mgmtUrl")
            ?: DEFAULT_MGMT
        val setupKey = prefs.getString("setupKey", null)?.takeIf { it.isNotEmpty() }
            ?: intent.getStringExtra("setupKey")
        val jwt = intent.getStringExtra("jwt")
        pendingUrl = target

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

    private val CONNECTING_HTML = """
        <html><body style="background:#f8fafc;display:flex;align-items:center;justify-content:center;height:100%;margin:0;font-family:sans-serif;color:#334155">
        <div style="text-align:center">
        <p style="font-size:18px">Connecting to NetBird&hellip;</p>
        </div></body></html>
    """.trimIndent()

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
        web.loadData(CONNECTING_HTML, "text/html", "utf-8")
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

        val container = LinearLayout(this)
        container.orientation = LinearLayout.VERTICAL
        container.setPadding(48, 8, 48, 8)
        container.addView(mgmtInput)
        container.addView(keyInput)

        AlertDialog.Builder(this)
            .setTitle("NetBird settings")
            .setMessage("Applied on save. The tunnel restarts to pick up the new server.")
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
                engineStarted = false
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