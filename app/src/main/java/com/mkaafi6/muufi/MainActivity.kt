package com.mkaafi6.muufi

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader

    private val homeUrl = "https://appassets.androidplatform.net/assets/launcher.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = findViewById(R.id.webview)
        setupWebView()

        wireBottomBar()

        // Build the ad-blocking engine in the background, then reload the page.
        Thread {
            try {
                val dir = prepareFilters()
                AdBlocker.init(dir.absolutePath)
                runOnUiThread {
                    if (AdBlocker.isReady()) webView.reload()
                }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this, "Ad blocker failed to start", Toast.LENGTH_SHORT).show() }
            }
        }.start()

        webView.loadUrl(homeUrl)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        val s = webView.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.databaseEnabled = true
        s.loadWithOverviewMode = true
        s.useWideViewPort = true
        s.mediaPlaybackRequiresUserGesture = false
        s.cacheMode = WebSettings.LOAD_DEFAULT
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                if (request == null) return null
                val url = request.url

                // Local app assets (the home launcher).
                if (url.host == "appassets.androidplatform.net") {
                    return assetLoader.shouldInterceptRequest(url)
                }

                if (!AdBlocker.isReady()) return null

                return try {
                    val type = requestType(request)
                    val method = request.method ?: "GET"
                    val source = view?.url ?: ""
                    if (AdBlocker.nativeShouldBlock(url.toString(), source, type, method)) {
                        // Empty body => effectively blocked.
                        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                supportActionBar?.title = view?.title ?: getString(R.string.app_name)
                if (url != null && !url.startsWith("https://appassets.androidplatform.net")) {
                    injectCosmetics(url)
                }
            }
        }

        webView.setOnLongClickListener { true }
    }

    /** Maps a request to an adblock-rust request-type string. */
    private fun requestType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"
        val url = request.url.toString().lowercase()
        val accept = request.requestHeaders
            ?.entries
            ?.firstOrNull { it.key.equals("Accept", ignoreCase = true) }
            ?.value
            ?.lowercase() ?: ""
        return when {
            url.endsWith(".css") || accept.contains("text/css") -> "stylesheet"
            url.endsWith(".js") || accept.contains("javascript") -> "script"
            url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg") ||
                url.endsWith(".gif") || url.endsWith(".webp") || url.endsWith(".svg") ||
                url.endsWith(".ico") || accept.startsWith("image/") -> "image"
            url.endsWith(".woff") || url.endsWith(".woff2") || url.endsWith(".ttf") ||
                url.endsWith(".otf") || accept.contains("font") -> "font"
            url.endsWith(".mp4") || url.endsWith(".m3u8") || url.endsWith(".webm") ||
                accept.startsWith("video/") || accept.startsWith("audio/") -> "media"
            url.endsWith(".html") || accept.contains("text/html") -> "subdocument"
            request.requestHeaders?.keys?.any { it.equals("X-Requested-With", ignoreCase = true) } == true -> "xhr"
            else -> "other"
        }
    }

    private fun injectCosmetics(pageUrl: String) {
        Thread {
            val json = try {
                AdBlocker.nativeCosmetics(pageUrl)
            } catch (t: Throwable) {
                null
            }
            if (json.isNullOrBlank()) return@Thread
            try {
                val obj = JSONObject(json)
                val hide = obj.optJSONArray("hide")
                val script = obj.optString("script", "")
                if (hide != null && hide.length() > 0) {
                    val selectors = (0 until hide.length()).joinToString(",") { hide.getString(it) }
                    val css = "$selectors{display:none !important;}"
                    runOnUiThread { applyStyle(css) }
                }
                if (script.isNotEmpty()) {
                    runOnUiThread { webView.evaluateJavascript(script, null) }
                }
            } catch (t: Throwable) {
                // ignore
            }
        }.start()
    }

    private fun applyStyle(css: String) {
        val js = "(function(){var s=document.getElementById('muufi-cosmetic');" +
            "if(!s){s=document.createElement('style');s.id='muufi-cosmetic';" +
            "(document.head||document.documentElement).appendChild(s);}" +
            "s.textContent=" + JSONObject.quote(css) + ";})();"
        webView.evaluateJavascript(js, null)
    }

    private fun wireBottomBar() {
        findViewById<LinearLayout>(R.id.btnHome).setOnClickListener { webView.loadUrl(homeUrl) }
        findViewById<LinearLayout>(R.id.btnRefresh).setOnClickListener { webView.reload() }
        findViewById<LinearLayout>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<LinearLayout>(R.id.btnShare).setOnClickListener { share() }
        findViewById<LinearLayout>(R.id.btnInfo).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(R.string.about_body)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun share() {
        val url = webView.url ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, url)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.share)))
    }

    private fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else if (webView.url != homeUrl) {
            webView.loadUrl(homeUrl)
        }
    }

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    /** Decompresses the gzipped bundled filter lists into filesDir/filters once. */
    private fun prepareFilters(): File {
        val outDir = File(filesDir, "filters")
        if (!outDir.exists()) outDir.mkdirs()
        val names = assets.list("filters") ?: emptyArray()
        for (name in names) {
            val out = File(outDir, name.removeSuffix(".gz"))
            if (out.exists() && out.length() > 0) continue
            assets.open("filters/$name").use { input ->
                GZIPInputStream(input).use { gz ->
                    out.outputStream().use { gz.copyTo(it) }
                }
            }
        }
        return outDir
    }
}
