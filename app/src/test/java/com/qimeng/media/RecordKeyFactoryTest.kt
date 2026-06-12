package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.core.RecordKeyFactory
import org.junit.Test

/**
 * RecordKeyFactory 纯逻辑单元测试
 * 覆盖：基本文件名生成、重复文件名高级区分、不同扩展名、边界情况
 */
class RecordKeyFactoryTest {

    // ==================== fromFileName ====================

    @Test
    fun fromFileName_normalFileName_returnsTrimmedFileName() {
        // 正常文件名应原样返回（trim 后）
        assertThat(RecordKeyFactory.fromFileName("photo.jpg")).isEqualTo("photo.jpg")
    }

    @Test
    fun fromFileName_fileNameWithSpaces_trimsWhitespace() {
        // 前后空格应被 trim
        assertThat(RecordKeyFactory.fromFileName("  photo.jpg  ")).isEqualTo("photo.jpg")
    }

    @Test
    fun fromFileName_emptyString_returnsEmpty() {
        // 空字符串 trim 后仍为空
        assertThat(RecordKeyFactory.fromFileName("")).isEmpty()
    }

    @Test
    fun fromFileName_onlySpaces_returnsEmpty() {
        // 纯空格 trim 后为空
        assertThat(RecordKeyFactory.fromFileName("   ")).isEmpty()
    }

    @Test
    fun fromFileName_specialCharacters_preserved() {
        // 特殊字符应保留
        assertThat(RecordKeyFactory.fromFileName("photo@2x#1.jpg")).isEqualTo("photo@2x#1.jpg")
    }

    @Test
    fun fromFileName_longFileName_preserved() {
        // 超长文件名应保留
        val longName = "a".repeat(500) + ".jpg"
        assertThat(RecordKeyFactory.fromFileName(longName)).isEqualTo(longName)
    }

    // ==================== fromDuplicateFileName ====================

    @Test
    fun fromDuplicateFileName_withFolderAndHash_returnsCombinedKey() {
        // 同时有 folderName 和 shortPathHash 时，格式为 "fileName @ folderName #hash"
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "Cosplay", "a1b2c3")
        assertThat(result).isEqualTo("photo.jpg @ Cosplay #a1b2c3")
    }

    @Test
    fun fromDuplicateFileName_withFolderOnly_returnsFileNameAtFolder() {
        // 只有 folderName 时，格式为 "fileName @ folderName"
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "Cosplay", null)
        assertThat(result).isEqualTo("photo.jpg @ Cosplay")
    }

    @Test
    fun fromDuplicateFileName_withHashOnly_returnsFileNameHash() {
        // 只有 shortPathHash 时，格式为 "fileName #hash"
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", null, "a1b2c3")
        assertThat(result).isEqualTo("photo.jpg #a1b2c3")
    }

    @Test
    fun fromDuplicateFileName_noFolderNoHash_returnsFileName() {
        // 都没有时，退化为纯文件名
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", null, null)
        assertThat(result).isEqualTo("photo.jpg")
    }

    @Test
    fun fromDuplicateFileName_emptyFolderEmptyHash_returnsFileName() {
        // 空字符串视为无效，退化为纯文件名
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "", "")
        assertThat(result).isEqualTo("photo.jpg")
    }

    @Test
    fun fromDuplicateFileName_blankFolderAndHash_treatedAsEmpty() {
        // 纯空格的 folderName/hash 视为无效
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "   ", "   ")
        assertThat(result).isEqualTo("photo.jpg")
    }

    @Test
    fun fromDuplicateFileName_differentExtensions_differentKeys() {
        // 不同扩展名产生不同的 recordKey
        val jpg = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "Folder", "hash1")
        val png = RecordKeyFactory.fromDuplicateFileName("photo.png", "Folder", "hash1")
        assertThat(jpg).isNotEqualTo(png)
    }

    @Test
    fun fromDuplicateFileName_sameFileDifferentFolders_differentKeys() {
        // 同文件名不同文件夹应产生不同 key
        val key1 = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "FolderA", "hash1")
        val key2 = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "FolderB", "hash2")
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun fromDuplicateFileName_sameFileSameFolderDifferentHash_differentKeys() {
        // 同文件名同文件夹不同路径哈希应产生不同 key
        val key1 = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "Folder", "hash1")
        val key2 = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "Folder", "hash2")
        assertThat(key1).isNotEqualTo(key2)
    }

    @Test
    fun fromDuplicateFileName_specialCharsInFolder_preserved() {
        // 文件夹名含特殊字符应保留
        val result = RecordKeyFactory.fromDuplicateFileName("photo.jpg", "Cos-Play_2024", "abc")
        assertThat(result).isEqualTo("photo.jpg @ Cos-Play_2024 #abc")
    }

    @Test
    fun fromDuplicateFileName_trimApplied() {
        // 所有参数都应被 trim
        val result = RecordKeyFactory.fromDuplicateFileName("  photo.jpg  ", "  Folder  ", "  hash  ")
        assertThat(result).isEqualTo("photo.jpg @ Folder #hash")
    }
}
