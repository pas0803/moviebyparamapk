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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var castContext: CastContext? = null

    // Latest video stream address seen in the page's network traffic
    @Volatile
    private var detectedUrl: String? = null
    private var castedThisPage = false

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
                    Toast.makeText(this@MainActivity, "Redirect blocked", Toast.LENGTH_SHORT).show()
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
                    val path = uri.path?.lowercase() ?: ""
                    if (path.endsWith(".m3u8") || path.endsWith(".mp4") || path.endsWith(".mpd")) {
                        detectedUrl = uri.toString()
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

        // Backup: mirror the whole phone screen
        findViewById<View>(R.id.castButton).setOnClickListener {
            openScreenMirror()
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

        val sniffed = detectedUrl
        if (sniffed != null) {
            sendToCast(session, sniffed)
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

    private fun sendToCast(session: CastSession, url: String) {
        val client = session.remoteMediaClient ?: return
        castedThisPage = true

        val path = Uri.parse(url).path?.lowercase() ?: ""
        val type = when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            else -> "video/mp4"
        }

        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE)
        metadata.putString(MediaMetadata.KEY_TITLE, webView.title ?: "MovieNova")

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
        Toast.makeText(this, "Sending video to TV...", Toast.LENGTH_SHORT).show()
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
            "highperformanceformat.com", "effectivegatecpm.com", "ads.yahoo.com"
        )
    }
}
