package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.core.ThumbnailCacheKeys
import org.junit.Test

/**
 * ThumbnailCacheKeys 纯 JVM 逻辑单元测试（v1.16 新增）
 *
 * 覆盖：缓存文件名 hash 生成（负字节格式化 / UTF-8 编码 / 长度）、
 * 文件名 → hash 提取（普通 .webp、历史遗留 _frame.webp）
 *
 * 期望值由外部工具按 SHA-256 前 8 字节 hex 预计算，避免测试自证自。
 */
class ThumbnailCacheKeysTest {

    // ==================== hashKey：SHA-256 前 8 字节 hex ====================

    @Test
    fun hashKey_ascii_returnsExpectedHex() {
        // 期望值 = SHA-256("a") 前 8 字节 hex（含负字节，验证掩码格式化）
        assertThat(ThumbnailCacheKeys.hashKey("a")).isEqualTo("ca978112ca1bbdca")
    }

    @Test
    fun hashKey_hello_returnsExpectedHex() {
        assertThat(ThumbnailCacheKeys.hashKey("hello")).isEqualTo("2cf24dba5fb0a30e")
    }

    @Test
    fun hashKey_withExtension_returnsExpectedHex() {
        assertThat(ThumbnailCacheKeys.hashKey("test.jpg")).isEqualTo("0d407ee6406a1216")
    }

    @Test
    fun hashKey_chineseFileName_returnsExpectedHex() {
        // 中文 recordKey：UTF-8 编码下 SHA-256 前 8 字节 hex
        assertThat(ThumbnailCacheKeys.hashKey("中文文件名测试")).isEqualTo("8cc010346752a8bf")
    }

    @Test
    fun hashKey_duplicateFileNameKey_returnsExpectedHex() {
        // 重名文件高级区分 key（RecordKeyFactory.fromDuplicateFileName 输出格式）
        assertThat(ThumbnailCacheKeys.hashKey("a.jpg @ folder #a1b2c3d4")).isEqualTo("3def0327a43284d9")
    }

    @Test
    fun hashKey_empty_returnsExpectedHex() {
        assertThat(ThumbnailCacheKeys.hashKey("")).isEqualTo("e3b0c44298fc1c14")
    }

    @Test
    fun hashKey_alwaysReturns16CharHex() {
        // 任意 key 结果恒为 16 位小写 hex（负字节格式化回归防护）
        listOf("a", "中文", "\u0000\u0001", "x".repeat(1000)).forEach { key ->
            val hash = ThumbnailCacheKeys.hashKey(key)
            assertThat(hash.length).isEqualTo(16)
            assertThat(Regex("[0-9a-f]{16}").matches(hash)).isTrue()
        }
    }

    @Test
    fun hashKey_deterministic() {
        // 同 key 多次计算恒等（缓存命中/孤儿对账依赖）
        assertThat(ThumbnailCacheKeys.hashKey("中文文件名测试"))
            .isEqualTo(ThumbnailCacheKeys.hashKey("中文文件名测试"))
    }

    // ==================== hashFromFileName：缓存文件名 → hash ====================

    @Test
    fun hashFromFileName_plainWebp_extractsHash() {
        val hash = ThumbnailCacheKeys.hashKey("test.jpg")
        assertThat(ThumbnailCacheKeys.hashFromFileName("$hash.webp")).isEqualTo(hash)
    }

    @Test
    fun hashFromFileName_legacyFrameWebp_extractsHash() {
        // 历史遗留 {hash}_frame.webp（v1.16 前详情帧命名），孤儿对账需兼容识别
        val hash = ThumbnailCacheKeys.hashKey("video.mp4")
        assertThat(ThumbnailCacheKeys.hashFromFileName("${hash}_frame.webp")).isEqualTo(hash)
    }

    @Test
    fun hashFromFileName_unknownSuffix_returnsBaseAsIs() {
        // 非 .webp 后缀：不参与对账，原样返回（对账只处理 .webp 缓存文件）
        assertThat(ThumbnailCacheKeys.hashFromFileName("abc.txt")).isEqualTo("abc.txt")
        assertThat(ThumbnailCacheKeys.hashFromFileName("abc_frame.txt")).isEqualTo("abc_frame.txt")
    }

    @Test
    fun hashFromFileName_hashContainingFrameSuffix_notMistaken() {
        // 正常 {hash}.webp 的 hash 若恰好以 "_frame" 结尾也不会被误伤：_frame 是后缀匹配，
        // 仅当文件名以 _frame.webp 结尾才去除——hash 本身是 16 位 hex，不含下划线，恒不触发
        val fake = "0123456789abcdef"
        assertThat(ThumbnailCacheKeys.hashFromFileName("${fake}_frame.webp")).isEqualTo(fake)
        assertThat(ThumbnailCacheKeys.hashFromFileName("$fake.webp")).isEqualTo(fake)
    }
}
