package com.apex.fit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

// Restarts the lock after the phone reboots (only if you had it enabled).
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Prefs(context).enabled) {
            ContextCompat.startForegroundService(context, Intent(context, BlockerService::class.java))
        }
    }
}
