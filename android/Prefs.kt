package com.apex.fit

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Your site. The lock screen opens this page in "lock mode".
const val SITE = "https://apex-recruitment-agency.github.io/APEX-FIT/"

object LockState {
    @Volatile var active = false
}

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("apexfit", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    var hour: Int
        get() = sp.getInt("hour", 18)
        set(v) { sp.edit().putInt("hour", v).apply() }

    var minute: Int
        get() = sp.getInt("minute", 0)
        set(v) { sp.edit().putInt("minute", v).apply() }

    var exercise: String
        get() = sp.getString("exercise", "squat") ?: "squat"
        set(v) { sp.edit().putString("exercise", v).apply() }

    var reps: Int
        get() = sp.getInt("reps", 10)
        set(v) { sp.edit().putInt("reps", v).apply() }

    var blocked: Set<String>
        get() = sp.getStringSet("blocked", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("blocked", HashSet(v)).apply() }

    var doneDate: String
        get() = sp.getString("done", "") ?: ""
        set(v) { sp.edit().putString("done", v).apply() }

    var graceUntil: Long
        get() = sp.getLong("grace", 0L)
        set(v) { sp.edit().putLong("grace", v).apply() }

    fun markDoneToday() { doneDate = today() }

    companion object {
        fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    }
}
