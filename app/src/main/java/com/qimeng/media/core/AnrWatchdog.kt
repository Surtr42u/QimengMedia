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
 * - idle 识别：主线程堆栈前 [IDLE_CHECK_LINES] 行内出现 nativePollOnce/MessageQueue.next/
 *   Looper.loop(Once) 时视为空闲等待消息，不报 ANR（常见于 App 后台/息屏，非真阻塞）。
 *   检查前若干行而非仅栈顶，可覆盖栈顶为 Looper.loopOnce 内部系统调用（如 PerfettoTrace 埋点）的 idle。
 * - 推进识别（高负载误报抑制）：主线程在高负载下做真实 UI 工作（列表绑定/滚动/渲染/View 构造/视频事件）
 *   时单次操作变慢，tick 无法及时执行导致误判阻塞。但此时主线程仍在推进（每个检查周期抓到的栈顶不同），
 *   非死锁。判定规则：连续 [CONSECUTIVE_SAME_TOP_LIMIT] 个检查周期栈顶相同（卡同一处不动）才报真 ANR；
 *   栈顶在变化（推进中）则降级为 debug 日志，不报 ANR。
 * - 硬阈值兜底：阻塞超过 [HARD_ANR_THRESHOLD_MS]（远超高负载抖动范围）无论栈顶是否变化都报 ANR，
 *   防止罕见的"栈顶循环变化的死循环"漏报。
 * - 冻结自检：守护线程被系统挂起（息屏/doze）时实际 sleep 远超预期，检测到则重置时间戳跳过本轮，
 *   避免冻结累积产生巨量误报（如 128s）
 *
 * v1.7 新增，v1.8 增强高负载误报抑制（推进识别 + 硬阈值兜底）。
 */
class AnrWatchdog(
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val checkIntervalMs: Long = CHECK_INTERVAL_MS,
    private val anrThresholdMs: Long = ANR_THRESHOLD_MS
) {
    private var tickTimestamp: Long = 0L
    private var lastAnrTimestamp: Long = 0L
    /** 上一次判定阻塞时主线程栈顶，用于跨周期比较判断是否卡同一处 */
    private var lastBlockStackTop: String? = null
    /** 连续判定阻塞且栈顶相同的周期数，达到 [CONSECUTIVE_SAME_TOP_LIMIT] 报真 ANR */
    private var consecutiveSameTopCount: Int = 0
    @Volatile private var running: Boolean = false
    private lateinit var watchdogThread: Thread

    /** 阻塞分类：idle（空闲）/ WORKING_SLOW（推进中变慢，非死锁）/ STUCK_ANR（卡死，真ANR） */
    private enum class BlockKind { IDLE, WORKING_SLOW, STUCK_ANR }

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
                    resetBlockTracking()
                    continue
                }

                val elapsed = now - tickTimestamp
                if (elapsed > anrThresholdMs) {
                    val stackTrace = dumpMainThreadStackTrace()
                    when (classifyBlock(elapsed, stackTrace)) {
                        BlockKind.IDLE -> {
                            // 主线程空闲等待消息（非真阻塞），重置时间戳与阻塞跟踪
                            tickTimestamp = now
                            mainHandler.post(tickRunnable)
                            resetBlockTracking()
                            continue
                        }
                        BlockKind.WORKING_SLOW -> {
                            // 高负载下主线程推进中变慢（栈顶在变化），非死锁。
                            // 重置时间戳避免单次慢操作被重复累积判定；保留连续计数以累积判断是否卡死。
                            tickTimestamp = now
                            mainHandler.post(tickRunnable)
                            AppLog.d(
                                "ANRWatchdog",
                                "主线程慢 ${elapsed}ms（高负载推进中，栈顶变化），非死锁，降级记录"
                            )
                        }
                        BlockKind.STUCK_ANR -> {
                            // 连续多次栈顶相同或超硬阈值：主线程卡死，报真 ANR
                            if (now - lastAnrTimestamp > RESET_AFTER_MS) {
                                lastAnrTimestamp = now
                                AppLog.e(
                                    "ANRWatchdog",
                                    "主线程阻塞 ${elapsed}ms（连续 ${consecutiveSameTopCount} 次栈顶相同" +
                                        "或超硬阈值 ${HARD_ANR_THRESHOLD_MS}ms），疑似 ANR\n主线程堆栈：\n$stackTrace"
                                )
                            }
                        }
                    }
                } else {
                    // 主线程健康（未阻塞），重置阻塞跟踪
                    resetBlockTracking()
                }
            }
        }
        // 启动后立即 post 第一次 tick
        mainHandler.post(tickRunnable)
    }

    /**
     * 判定主线程是否处于 idle（等待消息）状态。
     * 堆栈前 [IDLE_CHECK_LINES] 行内出现 nativePollOnce / MessageQueue.next /
     * Looper.loop(Once) 时，主线程在 MessageQueue 上阻塞等待新消息，属于空闲而非业务阻塞。
     * 检查前若干行而非仅栈顶，可覆盖栈顶为 Looper.loopOnce 内部系统调用（如 PerfettoTrace 埋点）的 idle。
     */
    private fun isMainThreadIdle(stackTrace: String): Boolean {
        val topLines = stackTrace.lineSequence().filter { it.isNotBlank() }.take(IDLE_CHECK_LINES)
        return topLines.any { line -> IDLE_MARKERS.any { marker -> line.contains(marker) } }
    }

    /**
     * 对主线程阻塞进行分类，区分真 ANR 与高负载下的推进变慢。
     *
     * - [BlockKind.IDLE]：主线程空闲等待消息（isMainThreadIdle 命中）
     * - [BlockKind.WORKING_SLOW]：主线程在推进（栈顶在变化），高负载下单次 UI 操作变慢，非死锁
     * - [BlockKind.STUCK_ANR]：连续 [CONSECUTIVE_SAME_TOP_LIMIT] 个周期栈顶相同（卡同一处），
     *   或阻塞超过 [HARD_ANR_THRESHOLD_MS] 硬阈值兜底，判定为真 ANR
     *
     * @param elapsedMs 本次阻塞时长（now - tickTimestamp）
     * @param stackTrace 主线程当前堆栈
     * @return 阻塞分类，调用方据此决定跳过/降级debug/报error
     */
    private fun classifyBlock(elapsedMs: Long, stackTrace: String): BlockKind {
        if (isMainThreadIdle(stackTrace)) return BlockKind.IDLE
        val currentTop = stackTrace.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        // 跨周期比较栈顶：相同则累积计数，变化则重置计数并记录新栈顶
        if (currentTop == lastBlockStackTop) {
            consecutiveSameTopCount++
        } else {
            consecutiveSameTopCount = 1
            lastBlockStackTop = currentTop
        }
        // 卡同一处不动（连续栈顶相同）或阻塞极长（硬阈值兜底，防栈顶循环变化的死循环漏报）
        val stuck = consecutiveSameTopCount >= CONSECUTIVE_SAME_TOP_LIMIT ||
            elapsedMs > HARD_ANR_THRESHOLD_MS
        return if (stuck) BlockKind.STUCK_ANR else BlockKind.WORKING_SLOW
    }

    /** 重置阻塞跟踪状态（主线程恢复健康/idle/冻结时调用，重新开始观察） */
    private fun resetBlockTracking() {
        lastBlockStackTop = null
        consecutiveSameTopCount = 0
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
        private const val ANR_THRESHOLD_MS = 5000L   // 主线程阻塞 5 秒进入判定流程
        private const val RESET_AFTER_MS = 30_000L   // 同一次 ANR 30 秒内不重复记录
        // 冻结自检：实际 sleep 时长超过预期的 3 倍，判定为守护线程被系统冻结
        private const val FREEZE_RATIO = 3.0
        // 主线程 idle 标记：堆栈前若干行内出现这些字符串时视为空闲等待消息，不报 ANR
        private val IDLE_MARKERS = arrayOf("nativePollOnce", "MessageQueue.next", "Looper.loop")
        // idle 识别检查堆栈前 N 行（覆盖栈顶为 Looper.loopOnce 内部系统调用的 idle）
        private const val IDLE_CHECK_LINES = 3
        // 推进识别：连续 N 个检查周期栈顶相同才判定卡死（真 ANR）；栈顶变化视为推进中变慢
        // 阈值 3 约等于连续 9 秒卡同一处，单次慢操作（通常 6 秒内完成）到不了 3 次，不会被误判
        private const val CONSECUTIVE_SAME_TOP_LIMIT = 3
        // 硬阈值兜底：阻塞超过此值无论栈顶是否变化都报 ANR，防罕见的"栈顶循环变化死循环"漏报
        // 远高于高负载抖动范围（实测误报均 < 6.5 秒），不影响推进变慢的降级
        private const val HARD_ANR_THRESHOLD_MS = 30_000L
    }
}
