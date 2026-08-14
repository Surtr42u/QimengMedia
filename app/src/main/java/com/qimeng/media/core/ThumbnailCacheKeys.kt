package com.qimeng.media.core

/**
 * 缩略图缓存文件名的纯 JVM 键逻辑（无 Android 依赖，可单元测试）。
 *
 * 缓存文件名格式：`{hash(recordKey)}.webp`；历史遗留过 `{hash(recordKey)}_frame.webp`
 * （v1.16 前详情帧缓存命名，写入方已移除，仅遗留旧文件需在孤儿清理时兼容识别）。
 */
object ThumbnailCacheKeys {

    /**
     * recordKey → 缓存文件 hash 部分：SHA-256 前 8 字节的十六进制（16 字符）。
     *
     * 编码显式固定 UTF-8：`toByteArray()` 无参重载使用平台默认字符集（Android 恒为
     * UTF-8，但 JVM 单元测试环境随平台变化，如 Windows 默认 GBK），显式指定可保证
     * 缓存文件名与运行环境无关、测试结果可复现（v1.16 明确化）。
     *
     * v1.15 修复：Byte 为负数（如 -1）时 `"%02x".format(Byte)` 会符号扩展输出 8 位
     * "ffffffff"（每字节长度不定），导致缓存文件名长度不一致/碰撞；
     * 先 `toInt() and 0xFF` 掩码再格式化，保证每字节恒为 2 位 hex。
     */
    fun hashKey(key: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(key.toByteArray(Charsets.UTF_8))
        return hash.take(8).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }

    /**
     * 从缓存文件名提取 hash 部分（供孤儿对账使用）：
     * 1. 去掉 `.webp` 后缀
     * 2. 兼容历史遗留 `{hash}_frame.webp`，去掉结尾 `_frame`
     *
     * 只 removeSuffix 结尾，文件名中间的 `_frame`（不存在的合法命名）不会被误伤。
     */
    fun hashFromFileName(fileName: String): String {
        val base = fileName.removeSuffix(".webp")
        return base.removeSuffix("_frame")
    }
}
