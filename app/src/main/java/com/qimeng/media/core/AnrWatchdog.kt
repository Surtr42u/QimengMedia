package com.qimeng.media.core

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.concurrent.thread

/**
 * ANR（Application Not Responding）监控。
 *
 * 工作原理：
 * 1. 后台线程每 [CHECK_INTERVAL_MS] 毫秒向主线程 post 一个 tick 任务，记录时间戳
 * 2. 后台线程 sleep [CHECK_INTERVAL_MS] 后检查时间戳是否更新
 * 3. 若主线程阻塞超过 [ANR_THRESHOLD_MS] 未更新时间戳，判定为 ANR，记录堆栈到 AppLog
 *
 * 设计要点：
 * - 仅记录日志，不弹窗、不杀进程（避免干扰用户）
 * - 使用 SystemClock.uptimeMillis() 不受系统时间调整影响
 * - 后台线程为守护线程，不阻止 JVM 退出
 * - 同一次 ANR 只记录一次，避免日志刷屏（需 [RESET_AFTER_MS] 毫秒后才再次记录）
 * - idle 识别：主线程栈顶为 nativePollOnce/MessageQueue.next/Looper.loop 时视为空闲等待消息，
 *   不报 ANR（常见于 App 后台/息屏，非真阻塞）
 * - 冻结自检：守护线程被系统挂起（息屏/doze）时实际 sleep 远超预期，检测到则重置时间戳跳过本轮，
 *   避免冻结累积产生巨量误报（如 128s）
 *
 * v1.7 新增。
 */
class AnrWatchdog(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS,
    private val anrThresholdMs: Long = ANR_THRESHOLD_MS
) {
    private var tickTimestamp: Long = 0L
    private var lastAnrTimestamp: Long = 0L
    @Volatile private var running: Boolean = false
    private lateinit var watchdogThread: Thread

    private val tickRunnable = Runnable {
        tickTimestamp = SystemClock.uptimeMillis()
    }

    fun start() {
        if (running) return
        running = true
        tickTimestamp = SystemClock.uptimeMillis()
        watchdogThread = thread(start = true, isDaemon = true, name = "AnrWatchdog") {
            while (running) {
                val sleepStart = SystemClock.uptimeMillis()
                try {
                    Thread.sleep(checkIntervalMs)
                } catch (_: InterruptedException) {
                    if (!running) return@thread
                }

                val now = SystemClock.uptimeMillis()
                // 冻结自检：守护线程被系统挂起（息屏/doze/后台）时实际 sleep 远超预期，
                // 此时 elapsed（now - tickTimestamp）会因冻结累积产生巨量误报（如 128s）。
                // 检测到冻结则重置 tickTimestamp 并跳过本轮判定，避免误报刷屏。
                val actualSleep = now - sleepStart
                if (actualSleep > checkIntervalMs * FREEZE_RATIO) {
                    tickTimestamp = now
                    mainHandler.post(tickRunnable)
                    continue
                }

                val elapsed = now - tickTimestamp
                if (elapsed > anrThresholdMs) {
                    val stackTrace = dumpMainThreadStackTrace()
                    // idle 识别：栈顶若为 nativePollOnce/next/Looper.loop* 说明主线程空闲等待消息，
                    // 并非真阻塞（常见于 App 后台/息屏），不报 ANR，只重置时间戳。
                    if (isMainThreadIdle(stackTrace)) {
                        tickTimestamp = now
                        mainHandler.post(tickRunnable)
                        continue
                    }
                    // 距上次 ANR 记录超过 RESET_AFTER_MS 才再次记录，避免刷屏
                    if (now - lastAnrTimestamp > RESET_AFTER_MS) {
                        lastAnrTimestamp = now
                        AppLog.e(
                            "ANRWatchdog",
                            "主线程阻塞 ${elapsed}ms（超过阈值 ${anrThresholdMs}ms），疑似 ANR\n主线程堆栈：\n$stackTrace"
                        )
                    }
                }
            }
        }
        // 启动后立即 post 第一次 tick
        mainHandler.post(tickRunnable)
    }

    /**
     * 判定主线程是否处于 idle（等待消息）状态。
     * 栈顶为 nativePollOnce / next / nextLegacy / loop / loopOnce 时，
     * 主线程在 MessageQueue 上阻塞等待新消息，属于空闲而非业务阻塞。
     */
    private fun isMainThreadIdle(stackTrace: String): Boolean {
        val firstLine = stackTrace.lineSequence().firstOrNull { it.isNotBlank() } ?: return false
        return IDLE_MARKERS.any { marker -> firstLine.contains(marker) }
    }

    fun stop() {
        running = false
        if (::watchdogThread.isInitialized) {
            watchdogThread.interrupt()
        }
        mainHandler.removeCallbacks(tickRunnable)
    }

    /** 获取主线程当前堆栈（用于 ANR 日志诊断） */
    private fun dumpMainThreadStackTrace(): String {
        val mainThread = Looper.getMainLooper().thread
        val sb = StringBuilder()
        for (ste in mainThread.stackTrace) {
            sb.append("    at ").append(ste.toString()).append('\n')
        }
        return sb.toString().trimEnd()
    }

    companion object {
        private const val CHECK_INTERVAL_MS = 3000L  // 每 3 秒检查一次
        private const val ANR_THRESHOLD_MS = 5000L   // 主线程阻塞 5 秒判定为 ANR
        private const val RESET_AFTER_MS = 30_000L   // 同一次 ANR 30 秒内不重复记录
        // 冻结自检：实际 sleep 时长超过预期的 3 倍，判定为守护线程被系统冻结
        private const val FREEZE_RATIO = 3.0
        // 主线程 idle 标记：栈顶包含这些字符串时视为空闲等待消息，不报 ANR
        private val IDLE_MARKERS = arrayOf("nativePollOnce", "MessageQueue.next", "Looper.loop")
    }
}
