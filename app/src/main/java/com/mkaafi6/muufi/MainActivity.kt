package com.mkaafi6.muufi

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebChromeClient.CustomViewCallback
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var assetLoader: WebViewAssetLoader
    private lateinit var toolbar: Toolbar
    private lateinit var fullscreenContainer: FrameLayout

    private val homeUrl = "https://mkaafi6.github.io/muufi/"
    private val offlineUrl = "https://appassets.androidplatform.net/assets/offline.html"

    private val prefsName = "muufi"
    private val prefBlockAds = "block_ads"

    /** Human-readable ad-blocker state shown in the About dialog / toast. */
    @Volatile
    private var adsState = "not started"

    /** URLs already cleaned once, to avoid rewrite/redirect loops. */
    private val recentlyRewritten = HashSet<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.title = getString(R.string.app_name)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)

        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView = findViewById(R.id.webview)
        setupWebView()

        wireBottomBar()

        // Build the ad-blocking engine in the background (unless disabled), then
        // pop up the install result and reload the page.
        if (adsEnabled()) {
            startAdBlocker()
        } else {
            adsState = "disabled"
            Toast.makeText(this, "Ad blocker: OFF", Toast.LENGTH_SHORT).show()
        }

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

                if (!adsEnabled() || !AdBlocker.isReady()) return null

                return try {
                    val type = requestType(request)
                    val method = request.method ?: "GET"
                    val source = view?.url ?: ""
                    when (val result = AdBlocker.nativeCheck(url.toString(), source, type, method)) {
                        "B" -> WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                        else -> if (result.startsWith("R")) {
                            val body = result.substring(1)
                            WebResourceResponse(
                                redirectMime(body),
                                "utf-8",
                                ByteArrayInputStream(body.toByteArray(Charsets.UTF_8))
                            )
                        } else {
                            null
                        }
                    }
                } catch (t: Throwable) {
                    null
                }
            }

            // `$removeparam` — strip tracking query parameters from top-level
            // navigations (Brave's "aggressive" behaviour).
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val req = request ?: return false
                if (!adsEnabled() || !AdBlocker.isReady()) return false
                // Only clean GET navigations; never turn a form POST into a GET.
                if (!req.method.equals("GET", ignoreCase = true)) return false
                val url = req.url?.toString() ?: return false
                if (url.startsWith("https://appassets.androidplatform.net")) return false
                if (recentlyRewritten.contains(url)) return false
                return try {
                    val rewritten = AdBlocker.nativeRewrite(
                        url, view?.url ?: "", "document", req.method ?: "GET"
                    )
                    if (rewritten.isNotEmpty() && rewritten != url) {
                        recentlyRewritten.add(url)
                        if (recentlyRewritten.size > 256) recentlyRewritten.clear()
                        view?.loadUrl(rewritten)
                        true
                    } else {
                        false
                    }
                } catch (t: Throwable) {
                    false
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

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true && view?.url != offlineUrl) {
                    view?.loadUrl(offlineUrl)
                }
            }
        }

        webView.setOnLongClickListener { true }

        // JS bridge + document-start script for early, uBO-style element hiding.
        webView.addJavascriptInterface(MuufiBridge(), "MuufiBridge")
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, BOOTSTRAP_JS, setOf("*"))
        }

        // Handles HTML5 fullscreen video (e.g. YouTube).
        webView.webChromeClient = object : WebChromeClient() {
            private var customView: View? = null

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                fullscreenContainer.addView(view)
                fullscreenContainer.visibility = View.VISIBLE
                toolbar.visibility = View.GONE
                findViewById<View>(R.id.bottombar).visibility = View.GONE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                enterImmersive()
            }

            override fun onHideCustomView() {
                val view = customView ?: return
                fullscreenContainer.removeView(view)
                fullscreenContainer.visibility = View.GONE
                customView = null
                toolbar.visibility = View.VISIBLE
                findViewById<View>(R.id.bottombar).visibility = View.VISIBLE
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                exitImmersive()
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun enterImmersive() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    @Suppress("DEPRECATION")
    private fun exitImmersive() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    /** Maps a request to an adblock-rust request-type string. */
    private fun requestType(request: WebResourceRequest): String {
        if (request.isForMainFrame) return "document"

        val headers = request.requestHeaders

        // Chromium sends Sec-Fetch-Dest, which maps almost 1:1 to adblock request
        // types. This is far more accurate than guessing from the URL/extension.
        val dest = headers
            ?.entries
            ?.firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value
            ?.lowercase()
        when (dest) {
            "document" -> return "document"
            "iframe", "frame" -> return "subdocument"
            "script", "worker", "sharedworker", "serviceworker" -> return "script"
            "style" -> return "stylesheet"
            "image" -> return "image"
            "font" -> return "font"
            "audio", "video", "track" -> return "media"
            "object", "embed" -> return "object"
            "empty" -> return "xhr"
        }

        // Fallback heuristics.
        val url = request.url.toString().lowercase()
        val accept = headers
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
            headers?.keys?.any { it.equals("X-Requested-With", ignoreCase = true) } == true -> "xhr"
            else -> "other"
        }
    }

    private fun injectCosmetics(pageUrl: String) {
        if (!adsEnabled()) return
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
        findViewById<LinearLayout>(R.id.btnRefresh).setOnClickListener {
            val current = webView.url
            if (current == null || current.startsWith("https://appassets.androidplatform.net")) {
                webView.loadUrl(homeUrl)
            } else {
                webView.reload()
            }
        }
        findViewById<LinearLayout>(R.id.btnBack).setOnClickListener { goBack() }
        findViewById<LinearLayout>(R.id.btnInfo).setOnClickListener {
            val adsLine = "Ad blocker: " + if (adsEnabled()) "ON" else "OFF"
            AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setMessage(getString(R.string.about_body) + "\n\n$adsLine\nStatus: $adsState")
                .setPositiveButton("Toggle ads") { _, _ -> toggleAds() }
                .setNeutralButton("Reload") { _, _ -> webView.reload() }
                .setNegativeButton("Close", null)
                .show()
        }
    }

    private fun prefs() = getSharedPreferences(prefsName, Context.MODE_PRIVATE)

    private fun adsEnabled(): Boolean = prefs().getBoolean(prefBlockAds, true)

    private fun toggleAds() {
        val nowEnabled = !adsEnabled()
        prefs().edit().putBoolean(prefBlockAds, nowEnabled).apply()
        if (nowEnabled) {
            Toast.makeText(this, "Ad blocker: ON", Toast.LENGTH_SHORT).show()
            if (!AdBlocker.isReady()) startAdBlocker()
        } else {
            adsState = "disabled"
            Toast.makeText(this, "Ad blocker: OFF", Toast.LENGTH_SHORT).show()
        }
        webView.reload()
    }

    /**
     * Builds the ad-blocking engine off the main thread and reports the result
     * with a toast (mirrors the GeckoView edition's "uBO: installed" popup).
     */
    private fun startAdBlocker() {
        adsState = "installing…"
        Toast.makeText(this, "Ad blocker: installing…", Toast.LENGTH_SHORT).show()
        Thread {
            val status = try {
                val dir = prepareFilters()
                val resources = prepareResources()
                AdBlocker.init(dir.absolutePath, resources.absolutePath)
            } catch (t: Throwable) {
                AdBlocker.failure(t.message ?: t.javaClass.simpleName)
            }
            val ok = try {
                JSONObject(status).optBoolean("ok", false)
            } catch (t: Throwable) {
                false
            }
            runOnUiThread {
                adsState = if (ok) {
                    val obj = try { JSONObject(status) } catch (t: Throwable) { JSONObject() }
                    val lists = obj.optInt("lists", 0)
                    val resources = obj.optInt("resources", 0)
                    "installed ($lists lists, $resources resources)"
                } else {
                    val err = try {
                        JSONObject(status).optString("error", "unknown error")
                    } catch (t: Throwable) {
                        "unknown error"
                    }
                    "INSTALL FAILED: $err"
                }
                Toast.makeText(
                    this@MainActivity,
                    "Ad blocker: $adsState",
                    if (ok) Toast.LENGTH_SHORT else Toast.LENGTH_LONG
                ).show()
                if (ok) webView.reload()
            }
        }.start()
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

    /** Exposed to page JavaScript as `window.MuufiBridge`. */
    private inner class MuufiBridge {
        @JavascriptInterface
        fun payload(url: String): String = try {
            AdBlocker.nativeCosmetics(url)
        } catch (t: Throwable) {
            "{}"
        }

        @JavascriptInterface
        fun generic(url: String, classesJson: String, idsJson: String): String = try {
            AdBlocker.nativeGenericSelectors(url, classesJson, idsJson)
        } catch (t: Throwable) {
            "[]"
        }
    }

    private fun redirectMime(body: String): String = when {
        body.startsWith("GIF8") -> "image/gif"
        body.length >= 8 && body.regionMatches(4, "ftyp", 0, 4) -> "video/mp4"
        else -> "application/javascript"
    }

    // Document-start bootstrap: hides ads before the page paints, and keeps
    // hiding dynamically-added ones (uBO-style), via the MuufiBridge.
    private val BOOTSTRAP_JS = """
        (function () {
          if (window.__muufiBootstrap) return;
          window.__muufiBootstrap = true;
          var bridge = window.MuufiBridge;
          var applied = {};

          function styleEl() {
            var s = document.getElementById('muufi-cosmetic');
            if (!s) {
              s = document.createElement('style');
              s.id = 'muufi-cosmetic';
              (document.head || document.documentElement).appendChild(s);
            }
            return s;
          }

          function apply(selectors) {
            if (!selectors || !selectors.length) return;
            var fresh = [];
            for (var i = 0; i < selectors.length; i++) {
              if (selectors[i] && !applied[selectors[i]]) {
                applied[selectors[i]] = 1;
                fresh.push(selectors[i]);
              }
            }
            if (!fresh.length) return;
            styleEl().textContent += fresh.join(',') + '{display:none !important;}';
          }

          try {
            if (bridge) {
              var res = JSON.parse(bridge.payload(location.href) || '{}');
              if (res.hide) apply(res.hide);
              if (res.script) { try { (0, eval)(res.script); } catch (e) {} }
            }
          } catch (e) {}

          var pc = {}, pi = {}, timer = null;
          function flush() {
            timer = null;
            if (!bridge) return;
            var c = Object.keys(pc), i = Object.keys(pi);
            pc = {}; pi = {};
            if (!c.length && !i.length) return;
            try {
              var sel = JSON.parse(bridge.generic(location.href, JSON.stringify(c), JSON.stringify(i)) || '[]');
              apply(sel);
            } catch (e) {}
          }

          var scanned = 0;
          function collect(roots) {
            for (var r = 0; r < roots.length; r++) {
              var el = roots[r];
              if (!el || el.nodeType !== 1) continue;
              var list;
              try { list = [el].concat(Array.prototype.slice.call(el.querySelectorAll('[class],[id]'))); }
              catch (e) { list = [el]; }
              for (var k = 0; k < list.length; k++) {
                if (scanned++ > 4000) break;
                var e = list[k];
                if (e.id) pi[e.id] = 1;
                if (e.classList) for (var c = 0; c < e.classList.length; c++) pc[e.classList[c]] = 1;
              }
            }
            if (timer) return;
            timer = setTimeout(flush, 700);
          }

          try {
            new MutationObserver(function (muts) {
              var added = [];
              for (var m = 0; m < muts.length; m++) {
                var an = muts[m].addedNodes;
                for (var a = 0; a < an.length; a++) added.push(an[a]);
              }
              if (added.length) collect(added);
            }).observe(document.documentElement || document, { childList: true, subtree: true });
            collect([document.documentElement]);
          } catch (e) {}
        })();
    """.trimIndent()

    /**
     * Copies the bundled filter lists into filesDir/filters once.
     *
     * Note: the Android build (AAPT) automatically decompresses `*.gz` assets
     * and strips the extension, so the files here may be plain text. We sniff
     * the gzip magic bytes so this works either way.
     */
    private fun prepareFilters(): File {
        val outDir = File(filesDir, "filters")
        if (!outDir.exists()) outDir.mkdirs()
        val names = assets.list("filters") ?: emptyArray()
        for (name in names) {
            val out = File(outDir, name.removeSuffix(".gz"))
            if (out.exists() && out.length() > 0) continue
            assets.open("filters/$name").use { input ->
                val buffered = BufferedInputStream(input)
                buffered.mark(2)
                val b0 = buffered.read()
                val b1 = buffered.read()
                buffered.reset()
                val gzipped = b0 == 0x1f && b1 == 0x8b
                val source = if (gzipped) GZIPInputStream(buffered) else buffered
                out.outputStream().use { dest -> source.copyTo(dest) }
            }
        }
        return outDir
    }

    /**
     * Copies the bundled adblock-rust resources (Brave + uBO `$redirect`
     * replacements and scriptlets) out of assets once, and returns the file.
     */
    private fun prepareResources(): File {
        val out = File(filesDir, "adblock-resources.json")
        if (out.exists() && out.length() > 0) return out
        assets.open("adblock/resources.json").use { input ->
            out.outputStream().use { dest -> input.copyTo(dest) }
        }
        return out
    }
}
