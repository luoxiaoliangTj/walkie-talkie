package com.example.walkie

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CrashHandler private constructor() : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
    private var context: Context? = null

    companion object {
        private const val TAG = "WalkieCrash"
        private const val CRASH_FILE = "crash_log.txt"

        fun init(context: Context) {
            val instance = CrashHandler()
            instance.context = context.applicationContext
            Thread.setDefaultUncaughtExceptionHandler(instance)
        }

        fun getCrashLog(context: Context): String? {
            val file = File(context.filesDir, CRASH_FILE)
            return if (file.exists()) file.readText() else null
        }

        fun clearCrashLog(context: Context) {
            File(context.filesDir, CRASH_FILE).delete()
        }
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        val pw = PrintWriter(sw)
        throwable.printStackTrace(pw)
        val stackTrace = sw.toString()

        Log.e(TAG, "CRASH: ${throwable.message}", throwable)

        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val log = """
                |=== Walkie-Talkie Crash Log ===
                |Time: $timestamp
                |Thread: ${thread.name}
                |
                |$stackTrace
                |
                |Device: ${android.os.Build.PRODUCT} (${android.os.Build.MODEL})
                |Android: ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT})
                |App Package: com.example.walkie
                |
                |=== END ===
            """.trimMargin()
            context?.let {
                val file = File(it.filesDir, CRASH_FILE)
                file.writeText(log)
            }
        } catch (e: Exception) {
            // Ignore
        }

        defaultHandler?.uncaughtException(thread, throwable)
    }
}
