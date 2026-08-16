package com.qimeng.media.core

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 扫描源级互斥锁：串行化同一 sourceUri 的「读 existingKeys → upsert → 删 stale」库临界区。
 *
 * 手动扫描（scanDirectory/refreshScanSource）、自动刷新（autoRefreshAllSources）与
 * MediaStoreObserver 增量刷新可并发执行同一源，后完成方会把先完成方新 upsert 的行
 * 当 stale 删除。所有扫描入口的库临界区必须经 [withSourceLock] 包裹。
 *
 * 锁条目不淘汰：扫描源数量有限（个位数级），Map 常驻无泄漏风险。
 * 只包单源「读-改-写」临界区，禁止把多源循环整体包进一把锁（会退化为全源串行）。
 */
object ScanSourceLocks {

    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withSourceLock(sourceUri: String, block: suspend () -> T): T =
        locks.getOrPut(sourceUri) { Mutex() }.withLock { block() }
}
