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
                // v1.12 修复：2MB 截断判定改为"仅当文件超限时截断一次再追加"。
                // 旧实现超限时固定保留末尾 500 行再整文件重写（readLines+takeLast+writeText），
                // 若日志单行较长（大堆栈/长路径），截断后仍可能超 2MB，导致每次追加都重复全量读写。
                // 现改为按字节保留文件尾部（约上限一半），保证一次截断后文件回到限内，后续追加仅 appendText。
                if (file.exists() && file.length() > MAX_LOG_SIZE) {
                    val keepBytes = (MAX_LOG_SIZE / 2).toInt()
                    val bytes = file.readBytes()
                    file.writeBytes(bytes.copyOfRange(bytes.size - keepBytes, bytes.size))
                }
                val timestamp = dateFormat.format(Date())
                file.appendText("$timestamp $line\n")
            } catch (_: Exception) { /* 日志写入失败不影响主流程 */ }
        }
    }
}
