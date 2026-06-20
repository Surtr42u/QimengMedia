package com.qimeng.media

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat

/**
 * 主题色解析工具。
 *
 * v1.10 起：取消主题定制系统（ThemeManager/ThemePreset），仅保留跟随手机系统的明暗模式。
 * 所有颜色由 values/values-night 静态决定，运行时直接解析 ?attr。
 */
fun Context.resolveThemeColor(@AttrRes attr: Int): Int {
    val tv = TypedValue()
    theme.resolveAttribute(attr, tv, true)
    return ContextCompat.getColor(this, tv.resourceId)
}

fun Context.isNightMode(): Boolean =
    (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

data class ThemeColors(
    val bg: Int,
    val surface: Int,
    val surfaceSoft: Int,
    val primary: Int,
    val primarySoft: Int,
    val accent: Int,
    val accentSoft: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val chipBg: Int,
    val divider: Int
)

object ThemeHelper {
    /** 批量解析 11 个主题色，直接从 ?attr 读取（跟随系统明暗模式） */
    fun resolve(context: Context): ThemeColors = ThemeColors(
        bg = context.resolveThemeColor(R.attr.qmColorBg),
        surface = context.resolveThemeColor(R.attr.qmColorSurface),
        surfaceSoft = context.resolveThemeColor(R.attr.qmColorSurfaceSoft),
        primary = context.resolveThemeColor(R.attr.qmColorPrimary),
        primarySoft = context.resolveThemeColor(R.attr.qmColorPrimarySoft),
        accent = context.resolveThemeColor(R.attr.qmColorAccent),
        accentSoft = context.resolveThemeColor(R.attr.qmColorAccentSoft),
        textPrimary = context.resolveThemeColor(R.attr.qmColorTextPrimary),
        textSecondary = context.resolveThemeColor(R.attr.qmColorTextSecondary),
        chipBg = context.resolveThemeColor(R.attr.qmColorChipBg),
        divider = context.resolveThemeColor(R.attr.qmColorDivider)
    )
}
