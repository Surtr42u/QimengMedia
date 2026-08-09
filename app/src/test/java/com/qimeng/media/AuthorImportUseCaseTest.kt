package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.domain.AuthorImportUseCase
import org.junit.Test

/**
 * AuthorImportUseCase.parseAuthorBlocks 纯逻辑单元测试
 * 覆盖：编号行/出处/作品区解析、括号别名去除、多别名合并、数字开头文件名误判编号行的修复
 */
class AuthorImportUseCaseTest {

    // ==================== 基础块解析 ====================

    @Test
    fun parseAuthorBlocks_formatB2_singleBlockParsed() {
        // 格式 B2：编号 + 单作者 + 出处 + 作品
        val text = """
            1  that_maskey
            出处  kemono
            作品
            尼尔 机械纪元  2B 1 (1).png
            守望先锋  天使 16 (1).png
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].authorNames).containsExactly("that_maskey")
        assertThat(blocks[0].works).containsExactly(
            "尼尔 机械纪元  2B 1 (1).png",
            "守望先锋  天使 16 (1).png"
        ).inOrder()
    }

    @Test
    fun parseAuthorBlocks_formatB_multiBlockParsed() {
        // 格式 B：同一 TXT 多作者块，用编号行分隔
        val text = """
            1  bamhor  bamh3d
            来源
            `https://example.site`
            作品
            英雄联盟  阿狸 1 (1).mp4

            2  Nagoonimation
            作品
            守望先锋 朱诺 1 (1).mp4
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(2)
        assertThat(blocks[0].authorNames).containsExactly("bamhor", "bamh3d").inOrder()
        assertThat(blocks[1].authorNames).containsExactly("Nagoonimation")
    }

    @Test
    fun parseAuthorBlocks_parenthesizedAlias_stripsSuffix() {
        // 括号备注自动去除：`紙飛行機(小红车资源出处)` → `紙飛行機`
        val text = """
            1  kamihikoki_mmd  紙飛行機(小红车资源出处)
            作品
            a.mp4
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].authorNames).containsExactly("kamihikoki_mmd", "紙飛行機").inOrder()
    }

    // ==================== 数字开头文件名误判编号行（v1.12 修复） ====================

    @Test
    fun parseAuthorBlocks_digitLeadingFileNameInWorks_keptAsWork() {
        // 作品名以数字开头（`2077  帕南.png`）不应被误判为新编号行，应归入当前块作品列表
        val text = """
            6  Takerskiy
            出处  kemono
            作品
            2077  帕南.png
            守望先锋  雾子 7 (1).jpg
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].authorNames).containsExactly("Takerskiy")
        assertThat(blocks[0].works).containsExactly(
            "2077  帕南.png",
            "守望先锋  雾子 7 (1).jpg"
        ).inOrder()
    }

    @Test
    fun parseAuthorBlocks_digitLeadingFileNameOutsideWorks_ignored() {
        // 数字开头文件名出现在块外（无归属作者时），应忽略而非创建伪作者块
        val text = """
            2077  朱迪+帕南.png

            1  Keu3D
            出处  kemono
            作品
            最终幻想  蒂法+爱丽丝 1 (1).png
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].authorNames).containsExactly("Keu3D")
        assertThat(blocks[0].works).containsExactly("最终幻想  蒂法+爱丽丝 1 (1).png")
    }

    @Test
    fun parseAuthorBlocks_multipleDigitLeadingWorks_allKept() {
        // 同一块内多个数字开头文件名（`2077 帕南.png`/`2077 帕南 2.jpg`）全部归入作品
        val text = """
            28  Night Cry
            作品
            生化危机  格蕾丝 2.jpg
            2077  帕南 2.jpg
            守望先锋  雾子 1.jpg
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].works).containsExactly(
            "生化危机  格蕾丝 2.jpg",
            "2077  帕南 2.jpg",
            "守望先锋  雾子 1.jpg"
        ).inOrder()
    }

    // ==================== 边界情况 ====================

    @Test
    fun parseAuthorBlocks_plainAuthorList_returnsEmpty() {
        // 格式 C（纯作者名列表）不是块格式，parseAuthorBlocks 应返回空，由调用方走简单行分支
        val text = """
            kamihikoki_mmd
            紙飛行機
            bamhor
        """.trimIndent()

        assertThat(AuthorImportUseCase.parseAuthorBlocks(text)).isEmpty()
    }

    @Test
    fun parseAuthorBlocks_emptyText_returnsEmpty() {
        assertThat(AuthorImportUseCase.parseAuthorBlocks("")).isEmpty()
        assertThat(AuthorImportUseCase.parseAuthorBlocks("   \n  \n ")).isEmpty()
    }

    @Test
    fun parseAuthorBlocks_numberOnlyLine_notTreatedAsHeader() {
        // 纯数字行不满足 `数字 内容` 编号行格式，按格式规范"编号行可不写作者名"仅作分隔符处理
        // 当前实现：纯数字行被忽略（不创建块、不误加作品），下一编号行正常开启新块
        val text = """
            1  aaa
            作品
            a.png

            16

            2  bbb
            作品
            b.png
        """.trimIndent()

        val blocks = AuthorImportUseCase.parseAuthorBlocks(text)
        assertThat(blocks).hasSize(2)
        assertThat(blocks[0].authorNames).containsExactly("aaa")
        assertThat(blocks[1].authorNames).containsExactly("bbb")
    }
}
