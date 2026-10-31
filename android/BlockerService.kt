package com.apex.fit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.util.Calendar

// Runs while the lock is enabled. Every second it checks which app is in front.
// If it is one of your blocked apps, the workout time has started and today's workout
// is not done yet, it opens the lock screen (LockActivity).
class BlockerService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var current: String? = null

    private val tick = object : Runnable {
        override fun run() {
            try { check() } catch (e: Exception) { /* keep running */ }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "apexfit_lock"
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(channelId, "Apex Fit", NotificationManager.IMPORTANCE_LOW)
        )
        val n = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("قفل Apex Fit شغال")
            .setContentText("التطبيقات المختارة هتتفتح بعد ما تخلّص تمرينك")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, n)
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        super.onDestroy()
    }

    // Remember the last app that came to the front. We keep the value between checks,
    // so an empty result does not make the lock flicker.
    private fun foreground(): String? {
        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val events = usm.queryEvents(end - 10_000, end)
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) current = e.packageName
        }
        return current
    }

    private fun check() {
        val p = Prefs(this)
        if (!p.enabled) { stopSelf(); return }
        val pkg = foreground() ?: return
        if (p.doneDate == Prefs.today()) return                       // workout done today
        if (System.currentTimeMillis() < p.graceUntil) return         // emergency pause
        val c = Calendar.getInstance()
        val nowMin = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        if (nowMin < p.hour * 60 + p.minute) return                   // not workout time yet
        if (pkg == packageName || pkg !in p.blocked || LockState.active) return
        startActivity(Intent(this, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
