package com.apex.fit

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.AppOpsManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.telecom.TelecomManager
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var p: Prefs

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(WebAppBridge(), "Android")
            webViewClient = WebViewClient()
        }
        setContentView(webView)

        // Load the live site (which will automatically detect window.Android and show the app blocker card!)
        webView.loadUrl(SITE)
    }

    override fun onResume() {
        super.onResume()
        webView.evaluateJavascript("if(window.updateAppStatus) updateAppStatus();", null)
    }

    private fun hasUsage(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    inner class WebAppBridge {
        @JavascriptInterface
        fun getStatus(): String {
            fun ok(b: Boolean) = if (b) "تمام" else "ناقص"
            val cam = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            val exName = mapOf("squat" to "سكوات", "pushup" to "ضغط", "plank" to "بلانك")[p.exercise] ?: "سكوات"
            return "بيانات الاستخدام: ${ok(hasUsage())} | الظهور فوق التطبيقات: ${ok(Settings.canDrawOverlays(this@MainActivity))}\n" +
                   "الميعاد: %02d:%02d | التمرين: %s × %d | المقفولة: %d تطبيقات".format(
                       p.hour, p.minute, exName, p.reps, p.blocked.size
                   )
        }

        @JavascriptInterface
        fun isEnabled(): Boolean = p.enabled

        @JavascriptInterface
        fun openPerms() {
            runOnUiThread {
                startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                val perms = mutableListOf(Manifest.permission.CAMERA)
                if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
                ActivityCompat.requestPermissions(this@MainActivity, perms.toTypedArray(), 1)
            }
        }

        @JavascriptInterface
        fun pickApps() {
            runOnUiThread {
                val pm = packageManager
                val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                val dialer = (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
                val never = listOfNotNull(packageName, dialer, "com.android.settings", "com.android.phone", "com.android.emergency")
                val apps = pm.queryIntentActivities(launcher, 0)
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .filter { it.first !in never }
                    .distinctBy { it.first }
                    .sortedBy { it.second }
                val checked = BooleanArray(apps.size) { apps[it].first in p.blocked }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("اختار التطبيقات اللي تتقفل")
                    .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
                    .setPositiveButton("حفظ") { _, _ ->
                        p.blocked = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                        webView.evaluateJavascript("if(window.updateAppStatus) updateAppStatus();", null)
                    }
                    .setNegativeButton("إلغاء", null)
                    .show()
            }
        }

        @JavascriptInterface
        fun pickTime() {
            runOnUiThread {
                TimePickerDialog(this@MainActivity, { _, h, m ->
                    p.hour = h
                    p.minute = m
                    webView.evaluateJavascript("if(window.updateAppStatus) updateAppStatus();", null)
                }, p.hour, p.minute, false).show()
            }
        }

        @JavascriptInterface
        fun pickExercise() {
            runOnUiThread {
                val names = arrayOf("سكوات", "ضغط", "بلانك (ثواني)")
                val values = arrayOf("squat", "pushup", "plank")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("نوع التمرين")
                    .setItems(names) { _, i ->
                        p.exercise = values[i]
                        webView.evaluateJavascript("if(window.updateAppStatus) updateAppStatus();", null)
                    }.show()
            }
        }

        @JavascriptInterface
        fun toggleLock() {
            runOnUiThread {
                if (!p.enabled) {
                    if (!hasUsage() || !Settings.canDrawOverlays(this@MainActivity)) {
                        android.widget.Toast.makeText(this@MainActivity, "فعّل صلاحية بيانات الاستخدام والظهور فوق التطبيقات الأول", android.widget.Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    if (p.blocked.isEmpty()) {
                        android.widget.Toast.makeText(this@MainActivity, "اختار تطبيق واحد على الأقل", android.widget.Toast.LENGTH_LONG).show()
                        return@runOnUiThread
                    }
                    p.enabled = true
                    ContextCompat.startForegroundService(this@MainActivity, Intent(this@MainActivity, BlockerService::class.java))
                } else {
                    p.enabled = false
                    stopService(Intent(this@MainActivity, BlockerService::class.java))
                }
                webView.evaluateJavascript("if(window.updateAppStatus) updateAppStatus();", null)
            }
        }

        @JavascriptInterface
        fun testLock() {
            runOnUiThread {
                startActivity(Intent(this@MainActivity, LockActivity::class.java))
            }
        }
    }
}
