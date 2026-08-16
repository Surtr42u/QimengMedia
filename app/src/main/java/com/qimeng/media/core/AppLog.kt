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
    /** 崩溃专项日志保留条数（app_log.txt 会 2MB 滚动截断，崩溃记录可能被冲掉，单独文件保最近 N 次） */
    private const val MAX_CRASH_ENTRIES = 20
    private const val CRASH_MARKER = "==== CRASH "
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private val crashDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val lock = Any()

    fun init(context: Context) {
        logFile = File(context.filesDir, "app_log.txt")
        // 启动时写入分隔线
        writeLog("========== APP STARTED ==========")
    }

    /**
     * 崩溃专项记录（v1.16）：UncaughtExceptionHandler 调用，写 files/crash_log.txt。
     * 与 app_log.txt 分离——后者 2MB 滚动截断会把崩溃记录冲掉（尤其闪退重启后扫描日志量大）。
     * 仅崩溃时写入零日常开销；保留最近 [MAX_CRASH_ENTRIES] 次。
     * 读取：adb shell run-as com.qimeng.media cat files/crash_log.txt
     */
    fun recordCrash(threadName: String, throwable: Throwable) {
        val file = logFile?.parentFile?.let { File(it, "crash_log.txt") } ?: return
        synchronized(lock) {
            try {
                val entries = if (file.exists()) {
                    file.readText().split(CRASH_MARKER).filter { it.isNotBlank() }
                } else {
                    emptyList()
                }
                val kept = entries.takeLast(MAX_CRASH_ENTRIES - 1)
                val sb = StringBuilder()
                for (e in kept) sb.append(CRASH_MARKER).append(e)
                sb.append(CRASH_MARKER)
                    .append(crashDateFormat.format(Date()))
                    .append(" thread=").append(threadName).append('\n')
                    .append(Log.getStackTraceString(throwable)).append('\n')
                file.writeText(sb.toString())
            } catch (_: Exception) { /* 崩溃记录失败不再有更兜底的手段，静默 */ }
        }
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
