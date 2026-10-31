package com.apex.fit

import android.Manifest
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
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

// Setup screen (built in code, so no layout XML is needed).
class MainActivity : AppCompatActivity() {

    private lateinit var p: Prefs
    private lateinit var status: TextView
    private lateinit var toggle: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Prefs(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        setContentView(ScrollView(this).also { it.addView(root) })

        fun tv(t: String, size: Float = 16f) = TextView(this).apply {
            text = t; textSize = size; setPadding(0, 12, 0, 12)
        }.also { root.addView(it) }

        fun btn(t: String, f: () -> Unit) = Button(this).apply {
            text = t; setOnClickListener { f() }
        }.also { root.addView(it) }

        tv("Apex Fit: قفل التطبيقات لحد ما تتمرن", 22f)
        status = tv("")

        tv("1) اديني الصلاحيات (مرة واحدة)", 18f)
        btn("صلاحية بيانات الاستخدام") {
            startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        }
        btn("الظهور فوق التطبيقات") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        btn("الكاميرا والإشعارات") {
            val perms = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            ActivityCompat.requestPermissions(this, perms.toTypedArray(), 1)
        }

        tv("2) اظبط القفل", 18f)
        btn("وقت التمرين") {
            TimePickerDialog(this, { _, h, m -> p.hour = h; p.minute = m; refresh() }, p.hour, p.minute, false).show()
        }
        btn("نوع التمرين") {
            val names = arrayOf("سكوات", "ضغط", "بلانك (ثواني)")
            val values = arrayOf("squat", "pushup", "plank")
            AlertDialog.Builder(this).setItems(names) { _, i -> p.exercise = values[i]; refresh() }.show()
        }
        btn("عدد التكرارات (أو الثواني للبلانك)") {
            val input = EditText(this).apply {
                inputType = InputType.TYPE_CLASS_NUMBER; setText(p.reps.toString())
            }
            AlertDialog.Builder(this).setView(input)
                .setPositiveButton("حفظ") { _, _ ->
                    p.reps = (input.text.toString().toIntOrNull() ?: 10).coerceIn(1, 300); refresh()
                }.show()
        }
        btn("اختار التطبيقات اللي تتقفل") { pickApps() }

        toggle = btn("تشغيل القفل") {
            if (!p.enabled) {
                if (!hasUsage() || !Settings.canDrawOverlays(this)) {
                    Toast.makeText(this, "فعّل صلاحية بيانات الاستخدام والظهور فوق التطبيقات الأول", Toast.LENGTH_LONG).show()
                    return@btn
                }
                if (p.blocked.isEmpty()) {
                    Toast.makeText(this, "اختار تطبيق واحد على الأقل", Toast.LENGTH_LONG).show()
                    return@btn
                }
                p.enabled = true
                ContextCompat.startForegroundService(this, Intent(this, BlockerService::class.java))
            } else {
                p.enabled = false
                stopService(Intent(this, BlockerService::class.java))
            }
            refresh()
        }
        btn("جرّب شاشة القفل دلوقتي") { startActivity(Intent(this, LockActivity::class.java)) }

        tv(
            "ملاحظات: المكالمات والطوارئ والإعدادات عمرها ما تتقفل. في شاشة القفل زرار طوارئ (5 دقايق) " +
                "وزرار تخطي النهاردة. لو موبايلك شاومي أو سامسونج، سيب Apex Fit من توفير البطارية وفعّل التشغيل التلقائي.",
            14f
        )
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasUsage(): Boolean {
        val ops = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun refresh() {
        fun ok(b: Boolean) = if (b) "تمام" else "ناقص"
        val cam = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val exName = mapOf("squat" to "سكوات", "pushup" to "ضغط", "plank" to "بلانك")[p.exercise]
        status.text = "بيانات الاستخدام: ${ok(hasUsage())}\n" +
            "الظهور فوق التطبيقات: ${ok(Settings.canDrawOverlays(this))}\n" +
            "الكاميرا: ${ok(cam)}\n\n" +
            "الميعاد: %02d:%02d\nالتمرين: %s × %d\nالتطبيقات المقفولة: %d\nالقفل: %s".format(
                p.hour, p.minute, exName, p.reps, p.blocked.size, if (p.enabled) "شغال" else "واقف"
            )
        toggle.text = if (p.enabled) "إيقاف القفل" else "تشغيل القفل"
    }

    private fun pickApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val dialer = (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage
        // Never offer these: your own app, phone/dialer, settings, emergency.
        val never = listOfNotNull(packageName, dialer, "com.android.settings", "com.android.phone", "com.android.emergency")
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first !in never }
            .distinctBy { it.first }
            .sortedBy { it.second }
        val checked = BooleanArray(apps.size) { apps[it].first in p.blocked }
        AlertDialog.Builder(this)
            .setTitle("اختار التطبيقات")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, c -> checked[i] = c }
            .setPositiveButton("حفظ") { _, _ ->
                p.blocked = apps.filterIndexed { i, _ -> checked[i] }.map { it.first }.toSet()
                refresh()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }
}
