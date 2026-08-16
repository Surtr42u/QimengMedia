package com.qimeng.media

import com.google.common.truth.Truth.assertThat
import com.qimeng.media.data.prefs.AppPrefsManager
import org.junit.Test

/**
 * AppPrefs 导入/磁盘解析值钳制纯函数单元测试
 * 覆盖：列数 0/负/超大钳制、themeMode 白名单、推荐权重 NaN/无穷/负/超界回退
 */
class AppPrefsImportSanitizeTest {

    // ==================== sanitizeGridColumns ====================

    @Test
    fun sanitizeGridColumns_zero_clampsToMin() {
        // 0 会让 GridLayoutManager spanCount 崩溃，须钳回下限
        assertThat(AppPrefsManager.sanitizeGridColumns(0, 1, 2)).isEqualTo(1)
        assertThat(AppPrefsManager.sanitizeGridColumns(0, 2, 5)).isEqualTo(2)
    }

    @Test
    fun sanitizeGridColumns_negative_clampsToMin() {
        // 负数同样钳回下限
        assertThat(AppPrefsManager.sanitizeGridColumns(-3, 1, 2)).isEqualTo(1)
        assertThat(AppPrefsManager.sanitizeGridColumns(-100, 2, 5)).isEqualTo(2)
    }

    @Test
    fun sanitizeGridColumns_oversized_clampsToMax() {
        // 超大列数钳回上限
        assertThat(AppPrefsManager.sanitizeGridColumns(99, 1, 2)).isEqualTo(2)
        assertThat(AppPrefsManager.sanitizeGridColumns(Int.MAX_VALUE, 2, 5)).isEqualTo(5)
    }

    @Test
    fun sanitizeGridColumns_inRange_keptAsIs() {
        // 合法值（含边界）原样保留
        assertThat(AppPrefsManager.sanitizeGridColumns(1, 1, 2)).isEqualTo(1)
        assertThat(AppPrefsManager.sanitizeGridColumns(2, 1, 2)).isEqualTo(2)
        assertThat(AppPrefsManager.sanitizeGridColumns(3, 2, 5)).isEqualTo(3)
        assertThat(AppPrefsManager.sanitizeGridColumns(5, 2, 5)).isEqualTo(5)
    }

    // ==================== sanitizeThemeMode ====================

    @Test
    fun sanitizeThemeMode_whitelistedValues_keptAsIs() {
        // 白名单三值原样通过
        assertThat(AppPrefsManager.sanitizeThemeMode("system", "dark")).isEqualTo("system")
        assertThat(AppPrefsManager.sanitizeThemeMode("light", "dark")).isEqualTo("light")
        assertThat(AppPrefsManager.sanitizeThemeMode("dark", "system")).isEqualTo("dark")
    }

    @Test
    fun sanitizeThemeMode_arbitraryString_fallsBack() {
        // 任意非法串（含大写变体、攻击串）回退 fallback
        assertThat(AppPrefsManager.sanitizeThemeMode("BLACK", "system")).isEqualTo("system")
        assertThat(AppPrefsManager.sanitizeThemeMode("whatever'; --", "light")).isEqualTo("light")
    }

    @Test
    fun sanitizeThemeMode_blank_fallsBack() {
        // 空串/纯空格回退 fallback
        assertThat(AppPrefsManager.sanitizeThemeMode("", "dark")).isEqualTo("dark")
        assertThat(AppPrefsManager.sanitizeThemeMode("   ", "dark")).isEqualTo("dark")
    }

    // ==================== sanitizeWeight ====================

    @Test
    fun sanitizeWeight_nan_fallsBack() {
        // NaN 非有限值，回退默认
        assertThat(AppPrefsManager.sanitizeWeight(Float.NaN, 0.22f)).isEqualTo(0.22f)
    }

    @Test
    fun sanitizeWeight_infinite_fallsBack() {
        // 正/负无穷回退默认
        assertThat(AppPrefsManager.sanitizeWeight(Float.POSITIVE_INFINITY, 0.30f)).isEqualTo(0.30f)
        assertThat(AppPrefsManager.sanitizeWeight(Float.NEGATIVE_INFINITY, 0.05f)).isEqualTo(0.05f)
    }

    @Test
    fun sanitizeWeight_negative_fallsBack() {
        // 负权重无业务语义，回退默认
        assertThat(AppPrefsManager.sanitizeWeight(-0.5f, 0.15f)).isEqualTo(0.15f)
    }

    @Test
    fun sanitizeWeight_aboveOne_fallsBack() {
        // 权重语义为 [0,1] 系数，超过 1 回退默认
        assertThat(AppPrefsManager.sanitizeWeight(1.5f, 0.20f)).isEqualTo(0.20f)
        assertThat(AppPrefsManager.sanitizeWeight(Float.MAX_VALUE, 0.10f)).isEqualTo(0.10f)
    }

    @Test
    fun sanitizeWeight_inRange_keptAsIs() {
        // 合法值（含 0/1 边界）原样保留
        assertThat(AppPrefsManager.sanitizeWeight(0f, 0.22f)).isEqualTo(0f)
        assertThat(AppPrefsManager.sanitizeWeight(1f, 0.22f)).isEqualTo(1f)
        assertThat(AppPrefsManager.sanitizeWeight(0.22f, 0.30f)).isEqualTo(0.22f)
    }
}
