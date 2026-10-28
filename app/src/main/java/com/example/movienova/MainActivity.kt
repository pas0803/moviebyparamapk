package com.example.movienova

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
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
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import androidx.appcompat.app.AlertDialog
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var castContext: CastContext? = null

    // Third-party websites contacted by the current page (for troubleshooting)
    private val seenHosts: MutableSet<String> =
        java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private var lastCastUrl: String? = null
    private var currentClient: RemoteMediaClient? = null

    private val mediaCallback = object : RemoteMediaClient.Callback() {
        override fun onStatusUpdated() {
            val status = currentClient?.mediaStatus ?: return
            if (status.playerState == MediaStatus.PLAYER_STATE_IDLE &&
                status.idleReason == MediaStatus.IDLE_REASON_ERROR
            ) {
                castedThisPage = false
                val host = Uri.parse(lastCastUrl ?: "").host ?: "unknown"
                Toast.makeText(
                    this@MainActivity,
                    "TV could not load this video (from $host). Try the mirror button.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Latest video stream address seen in the page's network traffic
    @Volatile
    private var detectedUrl: String? = null
    private var castedThisPage = false

    // All video addresses seen on this page (the user picks which one to cast)
    private val candidates: MutableList<String> =
        java.util.Collections.synchronizedList(ArrayList<String>())
    private var pickerShowing = false

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) {
            castedThisPage = false
            castCurrentVideo()
        }

        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) {}
        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionStartFailed(session: CastSession, error: Int) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true

        // Pop-up blocking: scripts may not open windows on their own,
        // and any window the page asks for is denied below
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
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                detectedUrl = null
                castedThisPage = false
                candidates.clear()
                seenHosts.clear()
            }

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

            // Watch network requests to spot the video stream address
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val uri = request?.url
                if (uri != null) {
                    // Drop requests to known ad networks
                    val host = uri.host?.lowercase() ?: ""
                    if (isBlockedHost(host)) {
                        return WebResourceResponse(
                            "text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))
                        )
                    }
                    if (host.isNotEmpty() && !isAllowedHost(host) && seenHosts.size < 80) {
                        seenHosts.add(host)
                    }
                    val path = uri.path?.lowercase() ?: ""
                    if (path.endsWith(".m3u8") || path.endsWith(".mp4") ||
                        path.endsWith(".mpd") || path.contains("videoplayback")
                    ) {
                        val found = uri.toString()
                        detectedUrl = found
                        if (!candidates.contains(found) && candidates.size < 15) {
                            candidates.add(found)
                        }
                        runOnUiThread { maybeAutoCast() }
                    }
                }
                return null
            }
        }

        // Real Chromecast button
        val routeButton = findViewById<MediaRouteButton>(R.id.mediaRouteButton)
        try {
            castContext = CastContext.getSharedInstance(this)
            CastButtonFactory.setUpMediaRouteButton(applicationContext, routeButton)
            routeButton.setAlwaysVisible(true)
        } catch (e: Exception) {
            // Google Play services missing: hide the Chromecast button
            routeButton.visibility = View.GONE
        }

        // Long-press the cast button: troubleshooting info
        routeButton.setOnLongClickListener {
            showDiagnostics()
            true
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

    private fun maybeAutoCast() {
        if (!castedThisPage) {
            castCurrentVideo()
        }
    }

    // Sends the current video to the connected Chromecast
    private fun castCurrentVideo() {
        val session = castContext?.sessionManager?.currentCastSession
        if (session == null || !session.isConnected) return

        // Let the user choose which detected video to send (ads can show up here too)
        if (candidates.isNotEmpty()) {
            showPicker(session)
            return
        }

        // Fallback: ask the page for its <video> address
        val js = "(function(){var v=document.querySelector('video');" +
            "return v?(v.currentSrc||v.src||''):'';})()"
        webView.evaluateJavascript(js) { raw ->
            val found = try {
                JSONTokener(raw ?: "null").nextValue() as? String
            } catch (e: Exception) {
                null
            }
            if (!found.isNullOrEmpty() && found.startsWith("http")) {
                sendToCast(session, found)
            } else {
                Toast.makeText(
                    this,
                    "Connected. Press play on the video and it will show on your TV.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    // Lists the detected videos so the user can pick the real movie
    private fun showPicker(session: CastSession) {
        if (pickerShowing) return
        val urls = synchronized(candidates) { candidates.toList() }
        if (urls.isEmpty()) return
        val labels = urls.map { u ->
            val uri = Uri.parse(u)
            val file = uri.lastPathSegment ?: "video"
            "${uri.host ?: "?"}  -  $file"
        }.toTypedArray()
        pickerShowing = true
        AlertDialog.Builder(this)
            .setTitle("Choose the video to cast")
            .setItems(labels) { _, index -> sendToCast(session, urls[index]) }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { pickerShowing = false }
            .show()
    }

    private fun sendToCast(session: CastSession, url: String) {
        val client = session.remoteMediaClient ?: return
        castedThisPage = true
        lastCastUrl = url

        // Report playback errors from the TV
        currentClient?.unregisterCallback(mediaCallback)
        currentClient = client
        client.registerCallback(mediaCallback)

        val path = Uri.parse(url).path?.lowercase() ?: ""
        val type = when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            else -> "video/mp4"
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        metadata.putString(MediaMetadata.KEY_TITLE, webView.title ?: "MoviesByParam")

        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
            .setContentType(type)
            .setMetadata(metadata)
            .build()

        client.load(
            MediaLoadRequestData.Builder()
                .setMediaInfo(info)
                .setAutoplay(true)
                .build()
        )

        // Pause the video on the phone so only the TV plays
        webView.evaluateJavascript(
            "document.querySelectorAll('video').forEach(function(v){v.pause();})",
            null
        )
        val shortName = Uri.parse(url).host ?: "video"
        Toast.makeText(this, "Sending video to TV (from $shortName)...", Toast.LENGTH_LONG).show()
    }

    // Shows the detected video address and other sites the page contacted
    private fun showDiagnostics() {
        val hosts = synchronized(seenHosts) { seenHosts.toList() }
        val text = StringBuilder()
        text.append("Video found:\n").append(detectedUrl ?: "none").append("\n\n")
        text.append("Last sent to TV:\n").append(lastCastUrl ?: "nothing").append("\n\n")
        text.append("Other sites contacted:\n")
        if (hosts.isEmpty()) text.append("none") else hosts.forEach { text.append(it).append("\n") }
        AlertDialog.Builder(this)
            .setTitle("Troubleshooting info")
            .setMessage(text.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    // Opens the current page in Chrome, where the site's own cast button works
    private fun openInChrome() {
        val url = webView.url ?: START_URL
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        try {
            startActivity(Intent(intent).setPackage("com.android.chrome"))
        } catch (e: Exception) {
            try {
                startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(this, "No browser found", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Opens Android's built-in "Cast screen" picker (mirrors the whole screen)
    private fun openScreenMirror() {
        try {
            startActivity(Intent(Settings.ACTION_CAST_SETTINGS))
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Swipe down and use 'Cast' or 'Screen Cast' in Quick Settings",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResume() {
        super.onResume()
        castContext?.sessionManager?.addSessionManagerListener(
            sessionListener, CastSession::class.java
        )
    }

    override fun onPause() {
        castContext?.sessionManager?.removeSessionManagerListener(
            sessionListener, CastSession::class.java
        )
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    private fun isAllowedHost(host: String): Boolean =
        ALLOWED_HOSTS.any { host == it || host.endsWith(".$it") }

    private fun isBlockedHost(host: String): Boolean =
        BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") }

    companion object {
        private const val START_URL = "https://movienova.sc/"

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

        // Websites the app is allowed to navigate to (add more here if the site needs them)
        private val ALLOWED_HOSTS = listOf("movienova.sc")

        // Common ad / pop-up networks whose requests are dropped
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
    }
}
