package com.voicepad.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Remembers why the app last closed unexpectedly, so the next launch can
 * show it and it can be copied into a bug report: the stack trace of an
 * uncaught exception, or (Android 11+) the system's record of a native
 * crash or an app-not-responding.
 */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "last-crash.txt")
    private var version = "?"

    fun install(context: Context) {
        val app = context.applicationContext
        version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                file(app).writeText(header() + "Thread: ${thread.name}\n\n$trace")
            }
            previous?.uncaughtException(thread, error)
        }
        recordSystemExit(app)
    }

    /** A native crash or a freeze leaves no Java trace; Android keeps its own record. */
    private fun recordSystemExit(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val prefs = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
            val am = context.getSystemService(ActivityManager::class.java)
            val last = am.getHistoricalProcessExitReasons(null, 0, 1).firstOrNull() ?: return
            if (last.timestamp <= prefs.getLong("seenExit", 0)) return
            prefs.edit().putLong("seenExit", last.timestamp).apply()
            val kind = when (last.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "Native crash"
                ApplicationExitInfo.REASON_ANR -> "Not responding (ANR)"
                ApplicationExitInfo.REASON_CRASH -> "Crash"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed for low memory"
                else -> return
            }
            // A Java crash already wrote its own, fuller report.
            if (last.reason == ApplicationExitInfo.REASON_CRASH && file(context).exists()) return
            val trace = if (last.reason == ApplicationExitInfo.REASON_ANR) {
                last.traceInputStream?.bufferedReader()?.use { it.readText().take(20_000) }
            } else {
                null
            }
            file(context).writeText(
                header(last.timestamp) + "$kind\n${last.description.orEmpty()}\n" +
                    "Memory: ${last.pss / 1024} MB\n" + (trace?.let { "\n$it" } ?: "")
            )
        }
    }

    private fun header(time: Long = System.currentTimeMillis()) =
        "VoicePad $version · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · " +
            "${Build.MANUFACTURER} ${Build.MODEL}\n" +
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time)) + "\n"

    /** The report from the last unexpected close, if it hasn't been dismissed. */
    fun pending(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) { file(context).delete() }
}
