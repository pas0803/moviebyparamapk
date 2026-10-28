package com.example.movienova

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Message
import android.provider.Settings
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    // Ad/tracker domains from public block lists, downloaded once a week
    private val downloadedBlocklist: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        loadBlocklistInBackground()

        // Pop-up blocking: scripts may not open windows on their own,
        // and any window the page asks for is denied
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        webView.settings.setSupportMultipleWindows(true)
        webView.webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                Toast.makeText(this@MainActivity, "Pop-up blocked", Toast.LENGTH_SHORT).show()
                return false
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                // Remove fake "storage full" style overlays
                view?.evaluateJavascript(CLEAN_JS, null)
            }

            // Block redirects away from the site (ad pages, other apps)
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                if (!request.isForMainFrame) return false
                val uri = request.url
                val scheme = uri.scheme?.lowercase() ?: ""
                val host = uri.host?.lowercase() ?: ""
                val allowed = (scheme == "http" || scheme == "https") && isAllowedHost(host)
                if (!allowed) {
                    Toast.makeText(this@MainActivity, "Redirect blocked: $host", Toast.LENGTH_SHORT).show()
                }
                return !allowed
            }

            // Drop requests to known ad networks
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val host = request?.url?.host?.lowercase() ?: ""
                if (isBlockedHost(host)) {
                    return WebResourceResponse(
                        "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                    )
                }
                return null
            }
        }

        // Back button: tap = previous page, long-press = home page
        val backButton = findViewById<View>(R.id.backButton)
        backButton.setOnClickListener {
            if (webView.canGoBack()) {
                webView.goBack()
            } else {
                webView.loadUrl(START_URL)
            }
        }
        backButton.setOnLongClickListener {
            webView.loadUrl(START_URL)
            true
        }

        // Cast button: mirror the phone screen to a TV
        findViewById<View>(R.id.castButton).setOnClickListener {
            openScreenMirror()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    finish()
                }
            }
        })

        if (savedInstanceState == null) {
            webView.loadUrl(START_URL)
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    // Opens Android's screen cast picker so you can choose your TV
    private fun openScreenMirror() {
        Toast.makeText(
            this,
            "Pick your TV. If nothing opens, swipe down twice and tap Smart View or Cast.",
            Toast.LENGTH_LONG
        ).show()
        val attempts = listOf(
            Intent(Settings.ACTION_CAST_SETTINGS),
            Intent("android.settings.WIFI_DISPLAY_SETTINGS")
        )
        for (intent in attempts) {
            try {
                startActivity(intent)
                return
            } catch (e: Exception) {
                // try the next one
            }
        }
    }

    private fun isAllowedHost(host: String): Boolean =
        ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }

    private fun isBlockedHost(host: String): Boolean {
        if (host.isEmpty() || isAllowedHost(host)) return false
        if (BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        // Check the host and each parent domain against the downloaded block lists
        var h = host
        while (h.contains('.')) {
            if (downloadedBlocklist.contains(h)) return true
            h = h.substring(h.indexOf('.') + 1)
        }
        return false
    }

    private fun loadBlocklistInBackground() {
        Thread {
            try {
                val file = File(filesDir, "blocklist.txt")
                if (file.exists()) readBlocklist(file)
                val weekMs = 7L * 24 * 60 * 60 * 1000
                val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > weekMs
                if (stale) {
                    val tmp = File(filesDir, "blocklist.tmp")
                    tmp.bufferedWriter().use { writer ->
                        for (source in BLOCKLIST_URLS) {
                            try {
                                val conn = URL(source).openConnection() as HttpURLConnection
                                conn.connectTimeout = 15000
                                conn.readTimeout = 40000
                                conn.inputStream.bufferedReader().use { reader ->
                                    reader.forEachLine { line ->
                                        writer.write(line)
                                        writer.write("\n")
                                    }
                                }
                                conn.disconnect()
                            } catch (e: Exception) {
                                // skip this source
                            }
                        }
                    }
                    if (tmp.length() > 10000L) {
                        tmp.copyTo(file, overwrite = true)
                        readBlocklist(file)
                    }
                    tmp.delete()
                }
            } catch (e: Exception) {
                // block lists are optional; the built-in list still works
            }
        }.start()
    }

    private fun readBlocklist(file: File) {
        val whitespace = Regex("\\s+")
        file.forEachLine { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) return@forEachLine
            val parts = t.split(whitespace)
            val domain = (if (parts.size >= 2) parts[1] else parts[0]).lowercase()
            if (domain.contains('.') && !domain.contains('/') && domain != "localhost") {
                downloadedBlocklist.add(domain)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    companion object {
        private const val START_URL = "https://movienova.sc/"

        // Public ad/tracker block lists (downloaded by the app, refreshed weekly)
        private val BLOCKLIST_URLS = listOf(
            "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=nohtml&showintro=0&mimetype=plaintext",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        )

        // Websites the app is allowed to navigate to (add more here if the site needs them)
        private val ALLOWED_HOSTS = listOf("movienova.sc")

        // Common ad / pop-up networks whose requests are always dropped
        private val BLOCKED_HOSTS = listOf(
            "doubleclick.net", "googlesyndication.com", "googleadservices.com",
            "adservice.google.com", "popads.net", "popcash.net", "propellerads.com",
            "adsterra.com", "exoclick.com", "juicyads.com", "trafficjunky.com",
            "clickadu.com", "hilltopads.com", "onclickads.com", "adnxs.com",
            "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
            "ad-maven.com", "admaven.com", "a-ads.com", "adcash.com",
            "popunderjs.com", "pushwhy.com", "profitableratecpm.com",
            "highperformanceformat.com", "effectivegatecpm.com", "ads.yahoo.com",
            "saffronandsky.space"
        )

        // Removes full-screen scam-style overlays ("storage full", "Google Drive", etc.)
        private const val CLEAN_JS = """
(function(){
  if (window.__mbpClean) return;
  window.__mbpClean = true;
  var re = /(disk|storage)[^.]{0,40}(full|low|almost)|(full|low|almost)[^.]{0,40}(disk|storage)|google drive|icloud/i;
  var timer = null;
  function clean(){
    timer = null;
    var els = document.querySelectorAll('div,section,aside,iframe');
    for (var i = 0; i < els.length; i++) {
      var e = els[i];
      var cs = getComputedStyle(e);
      if (cs.position === 'fixed' || cs.position === 'absolute') {
        var z = parseInt(cs.zIndex, 10) || 0;
        if (z >= 100) {
          var t = (e.innerText || '').slice(0, 400);
          if (re.test(t)) { e.remove(); }
        }
      }
    }
  }
  function schedule(){ if (!timer) timer = setTimeout(clean, 400); }
  new MutationObserver(schedule).observe(document.documentElement, {childList: true, subtree: true});
  clean();
})();
"""
    }
}
