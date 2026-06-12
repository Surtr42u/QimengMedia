package com.qimeng.media.core

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具，用于在 logcat 不可读的设备上（如 Android 16）通过 adb run-as 读取日志。
 *
 * 使用方式：
 *   AppLog.d("CosScan", "scanCosMedia: files=1234")
 *
 * 读取方式：
 *   adb shell run-as com.qimeng.media cat files/app_log.txt
 *   或拉取到本地：
 *   adb shell run-as com.qimeng.media cat files/app_log.txt > log.txt
 */
object AppLog {
    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        logFile = File(context.filesDir, "app_log.txt")
        // 启动时写入分隔线
        writeLog("========== APP STARTED ==========")
    }

    fun d(tag: String, msg: String) {
        Log.d(tag, msg)
        writeLog("D/$tag: $msg")
    }

    fun e(tag: String, msg: String, throwable: Throwable? = null) {
        Log.e(tag, msg, throwable)
        val stackTrace = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        writeLog("E/$tag: $msg$stackTrace")
    }

    fun w(tag: String, msg: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(tag, msg, throwable) else Log.w(tag, msg)
        val stackTrace = throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: ""
        writeLog("W/$tag: $msg$stackTrace")
    }

    fun i(tag: String, msg: String) {
        Log.i(tag, msg)
        writeLog("I/$tag: $msg")
    }

    private fun writeLog(line: String) {
        val file = logFile ?: return
        synchronized(lock) {
            try {
                // 超过大小限制时截断
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val content = file.readLines().takeLast(500).joinToString("\n") + "\n"
                    file.writeText(content)
                }
                val timestamp = dateFormat.format(Date())
                file.appendText("$timestamp $line\n")
            } catch (_: Exception) { /* 日志写入失败不影响主流程 */ }
        }
    }
}
