package com.example.data.settings

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import java.time.LocalDate
import java.util.Calendar

/**
 * Lightweight reading-time and daily-streak tracker backed by SharedPreferences ("mk_pdf_stats").
 * All mutating calls use SharedPreferences.Editor.apply() and should be invoked off the main thread.
 */
object ReadingStats {
    private const val PREFS_NAME = "mk_pdf_stats"
    private const val PREFIX_DOC = "doc_"
    private const val PREFIX_DAY = "day_"
    private const val STREAK_THRESHOLD_MS = 60_000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    internal fun currentEpochDay(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            LocalDate.now().toEpochDay()
        } else {
            val cal = Calendar.getInstance()
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val day = cal.get(Calendar.DAY_OF_MONTH)
            val y = year - if (month <= 2) 1 else 0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (month + if (month > 2) -3 else 9) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            (era * 146097 + doe - 719468).toLong()
        }
    }

    @Synchronized
    fun addTime(context: Context, docKey: String, ms: Long) {
        if (ms <= 0L) return
        val p = prefs(context)
        val docPrefKey = "$PREFIX_DOC$docKey"
        val dayPrefKey = "$PREFIX_DAY${currentEpochDay()}"
        val newDocTotal = p.getLong(docPrefKey, 0L) + ms
        val newDayTotal = p.getLong(dayPrefKey, 0L) + ms
        p.edit()
            .putLong(docPrefKey, newDocTotal)
            .putLong(dayPrefKey, newDayTotal)
            .apply()
    }

    fun totalFor(context: Context, docKey: String): Long {
        return prefs(context).getLong("$PREFIX_DOC$docKey", 0L)
    }

    fun todayMs(context: Context): Long {
        return prefs(context).getLong("$PREFIX_DAY${currentEpochDay()}", 0L)
    }

    fun streakDays(context: Context): Int {
        val p = prefs(context)
        var day = currentEpochDay()
        var streak = 0
        while (p.getLong("$PREFIX_DAY$day", 0L) >= STREAK_THRESHOLD_MS) {
            streak++
            day--
        }
        return streak
    }

    fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "0m"
        val totalMinutes = ms / 60_000L
        if (totalMinutes <= 0L) return "< 1m"
        val hours = totalMinutes / 60L
        val mins = totalMinutes % 60L
        return if (hours > 0L) {
            "${hours}h ${mins}m"
        } else {
            "${totalMinutes}m"
        }
    }
}
