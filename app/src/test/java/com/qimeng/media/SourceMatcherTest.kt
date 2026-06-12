package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.ui.album.SourceMatcher
import org.junit.Test

/**
 * SourceMatcher.match() 纯逻辑单元测试
 * 覆盖：精确匹配、变体匹配、最长优先、未匹配返回 null、版本号合并
 */
class SourceMatcherTest {

    // ==================== 精确匹配已知出处 ====================

    @Test
    fun match_exactCanonicalName_returnsCanonical() {
        // 精确匹配 canonical 名称
        assertThat(SourceMatcher.match("守望先锋")).isEqualTo("守望先锋")
    }

    @Test
    fun match_exactCanonicalWithExtension_returnsCanonical() {
        // 带扩展名的文件名也能匹配
        assertThat(SourceMatcher.match("守望先锋.jpg")).isEqualTo("守望先锋")
    }

    @Test
    fun match_exactCanonicalWithSuffix_returnsCanonical() {
        // 文件名以出处名开头，后面有附加内容
        assertThat(SourceMatcher.match("守望先锋_天使.jpg")).isEqualTo("守望先锋")
    }

    // ==================== 变体匹配 ====================

    @Test
    fun match_variantLOL_matchesHeroLeague() {
        // "LOL" 应匹配到"英雄联盟"
        assertThat(SourceMatcher.match("LOL")).isEqualTo("英雄联盟")
    }

    @Test
    fun match_variantOW_matchesOverwatch() {
        // "OW" 应匹配到"守望先锋"
        assertThat(SourceMatcher.match("OW_天使.jpg")).isEqualTo("守望先锋")
    }

    @Test
    fun match_variantEnglishName_matchesChineseCanonical() {
        // 英文名变体应匹配到中文 canonical
        assertThat(SourceMatcher.match("Overwatch")).isEqualTo("守望先锋")
    }

    @Test
    fun match_variantWithExtension_matchesCorrectly() {
        // 带扩展名的变体名应匹配
        assertThat(SourceMatcher.match("Overwatch.jpg")).isEqualTo("守望先锋")
    }

    @Test
    fun match_variantCyberpunk2077_matchesCorrectGroup() {
        // "2077" 是赛博朋克的变体
        assertThat(SourceMatcher.match("2077")).isEqualTo("赛博朋克2077")
    }

    // ==================== 最长优先匹配 ====================

    @Test
    fun match_longerVariantPreferredOverShorter() {
        // "尼尔机械纪元" 应匹配到"尼尔 机械纪元"而非"尼尔 人工生命"
        // 因为 BUILTIN_VARIANTS 按长度降序排列，"尼尔机械纪元"比"尼尔"长
        assertThat(SourceMatcher.match("尼尔机械纪元")).isEqualTo("尼尔 机械纪元")
    }

    @Test
    fun match_nierAutomata_matchesNierAutomata() {
        // 英文变体 NieR Automata 应匹配到"尼尔 机械纪元"
        assertThat(SourceMatcher.match("NieR Automata")).isEqualTo("尼尔 机械纪元")
    }

    @Test
    fun match_residentEvil4_matchesBiohazard() {
        // "生化危机4" 应匹配到"生化危机"组
        assertThat(SourceMatcher.match("生化危机4")).isEqualTo("生化危机")
    }

    // ==================== 未匹配返回 null ====================

    @Test
    fun match_unknownSource_returnsNull() {
        // 不认识的出处应返回 null
        assertThat(SourceMatcher.match("随便一个名字.jpg")).isNull()
    }

    @Test
    fun match_emptyString_returnsNull() {
        // 空字符串应返回 null
        assertThat(SourceMatcher.match("")).isNull()
    }

    @Test
    fun match_randomEnglish_returnsNull() {
        // 不相关的英文名应返回 null
        assertThat(SourceMatcher.match("RandomTitle.jpg")).isNull()
    }

    // ==================== 版本号合并 ====================

    @Test
    fun match_tekken8_matchesTekken() {
        // "铁拳8" 是铁拳的变体，应匹配到"铁拳"
        assertThat(SourceMatcher.match("铁拳8")).isEqualTo("铁拳")
    }

    @Test
    fun match_tekken7_matchesTekken() {
        // "铁拳7" 同理
        assertThat(SourceMatcher.match("铁拳7")).isEqualTo("铁拳")
    }

    @Test
    fun match_englishTekken_matchesTekken() {
        // "Tekken 8" 也应匹配到"铁拳"
        assertThat(SourceMatcher.match("Tekken 8")).isEqualTo("铁拳")
    }

    @Test
    fun match_finalFantasy7_matchesFinalFantasy() {
        // "最终幻想7" 是最终幻想的变体
        assertThat(SourceMatcher.match("最终幻想7")).isEqualTo("最终幻想")
    }

    @Test
    fun match_ff7_matchesFinalFantasy() {
        // "FF7" 是最终幻想的变体
        assertThat(SourceMatcher.match("FF7")).isEqualTo("最终幻想")
    }

    // ==================== 大小写与空格容差 ====================

    @Test
    fun match_caseInsensitive_matchesCorrectly() {
        // 匹配应忽略大小写（collapsed 阶段已 lowercase）
        assertThat(SourceMatcher.match("overwatch")).isEqualTo("守望先锋")
    }

    @Test
    fun match_spaceTolerant_matchesCorrectly() {
        // 匹配应忽略空格差异（collapsed 阶段已去除空格）
        assertThat(SourceMatcher.match("守望先锋 归来")).isEqualTo("守望先锋")
    }

    // ==================== 自定义出处 ====================

    @Test
    fun match_customSource_matchesAfterUpdate() {
        // 更新自定义出处后应能匹配
        SourceMatcher.updateCustomSources(setOf("我的自定义游戏"))
        assertThat(SourceMatcher.match("我的自定义游戏")).isEqualTo("我的自定义游戏")
        // 清理
        SourceMatcher.updateCustomSources(emptySet())
    }

    @Test
    fun match_customSourceCleared_noLongerMatches() {
        // 清除自定义出处后不应再匹配
        SourceMatcher.updateCustomSources(setOf("临时游戏"))
        SourceMatcher.updateCustomSources(emptySet())
        assertThat(SourceMatcher.match("临时游戏")).isNull()
    }

    // ==================== txt 作品 ====================

    @Test
    fun match_txtWork_matchesAfterUpdate() {
        // 更新 txt 作品后应能匹配
        SourceMatcher.updateTxtWorks(setOf("某部小说"))
        assertThat(SourceMatcher.match("某部小说")).isEqualTo("某部小说")
        // 清理
        SourceMatcher.updateTxtWorks(emptySet())
    }
}
