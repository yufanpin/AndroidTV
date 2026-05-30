package com.tivimatelite.web

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogStore {
    private const val TAG = "FileLogStore"
    private const val MAX_SIZE = 512 * 1024
    private const val FILE_NAME = "tivimate_diag.txt"

    private var logFile: File? = null
    private var initialized = false

    fun init(context: Context) {
        logFile = File(context.cacheDir, FILE_NAME)
        logFile?.delete()
        initialized = true
        i(TAG, "File log initialized")
        i(TAG, "Device: ${android.os.Build.MODEL}, SDK: ${android.os.Build.VERSION.SDK_INT}")
        i(TAG, "App version: ${context.packageManager.getPackageInfo(context.packageName, 0).versionName}")

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e(TAG, "UNCAUGHT EXCEPTION on thread: ${thread.name}", throwable)
            try {
                throwable.printStackTrace(java.io.PrintStream(logFile?.outputStream() ?: return@setDefaultUncaughtExceptionHandler))
            } catch (_: Exception) {}
            if (prevHandler != null && prevHandler !== Thread.getDefaultUncaughtExceptionHandler()) {
                prevHandler.uncaughtException(thread, throwable)
            }
        }
        i(TAG, "Uncaught exception handler installed")
    }

    fun i(tag: String, msg: String) {
        write("I", tag, msg)
        android.util.Log.i(tag, msg)
    }

    fun w(tag: String, msg: String, tr: Throwable? = null) {
        write("W", tag, if (tr != null) "$msg: ${tr.message}" else msg)
        if (tr != null) android.util.Log.w(tag, msg, tr) else android.util.Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        write("E", tag, if (tr != null) "$msg: ${tr.message}" else msg)
        if (tr != null) android.util.Log.e(tag, msg, tr) else android.util.Log.e(tag, msg)
    }

    private val dateFmt = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private fun write(level: String, tag: String, msg: String) {
        if (!initialized) return
        val file = logFile ?: return
        try {
            val line = "${dateFmt.format(Date())} $level/$tag: $msg\n"
            if (file.exists() && file.length() > MAX_SIZE) {
                file.delete()
            }
            file.appendText(line)
        } catch (_: Exception) {
        }
    }
}
