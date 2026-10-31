package com.apex.fit

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

// The lock screen. It shows your site in lock mode (camera coach with a target).
// When the site reports the workout is done, the day is marked done and this screen closes.
class LockActivity : AppCompatActivity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LockState.active = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val p = Prefs(this)

        web = WebView(this)
        setContentView(web)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = false
        web.settings.cacheMode = WebSettings.LOAD_DEFAULT
        web.addJavascriptInterface(Bridge(), "Android")

        val siteHost = Uri.parse(SITE).host
        web.webViewClient = object : WebViewClient() {
            // Never leave your site inside the lock screen.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.host != siteHost

            // No internet or page failed: give a 5 minute pause instead of trapping the user.
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    Prefs(this@LockActivity).graceUntil = System.currentTimeMillis() + 5 * 60 * 1000
                    finish()
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val granted = ContextCompat.checkSelfPermission(
                        this@LockActivity, Manifest.permission.CAMERA
                    ) == PackageManager.PERMISSION_GRANTED
                    if (granted) request.grant(request.resources) else request.deny()
                }
            }
        }
        web.loadUrl("$SITE?lock=1&ex=${p.exercise}&n=${p.reps}")
    }

    // Leaving the lock screen (Home, recents) closes it, so it can never get stuck in the background.
    override fun onStop() {
        super.onStop()
        if (!isFinishing) finish()
    }

    override fun onDestroy() {
        LockState.active = false
        web.destroy()
        super.onDestroy()
    }

    // Only your own page can call these.
    inner class Bridge {
        @JavascriptInterface
        fun workoutDone() {
            Prefs(this@LockActivity).markDoneToday()
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun grace() {
            Prefs(this@LockActivity).graceUntil = System.currentTimeMillis() + 5 * 60 * 1000
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun skipToday() {
            Prefs(this@LockActivity).markDoneToday()
            runOnUiThread { finish() }
        }
    }
}
