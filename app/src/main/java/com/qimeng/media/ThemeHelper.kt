package com.qimeng.media

import android.content.Context
import android.content.res.Configuration
import android.util.TypedValue
import androidx.annotation.AttrRes
import androidx.core.content.ContextCompat

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
    val textPrimary: Int,
    val textSecondary: Int,
    val chipBg: Int,
    val divider: Int
)

object ThemeHelper {
    fun resolve(context: Context): ThemeColors = with(context) {
        ThemeColors(
            bg = resolveThemeColor(R.attr.qmColorBg),
            surface = resolveThemeColor(R.attr.qmColorSurface),
            surfaceSoft = resolveThemeColor(R.attr.qmColorSurfaceSoft),
            primary = resolveThemeColor(R.attr.qmColorPrimary),
            primarySoft = resolveThemeColor(R.attr.qmColorPrimarySoft),
            textPrimary = resolveThemeColor(R.attr.qmColorTextPrimary),
            textSecondary = resolveThemeColor(R.attr.qmColorTextSecondary),
            chipBg = resolveThemeColor(R.attr.qmColorChipBg),
            divider = resolveThemeColor(R.attr.qmColorDivider)
        )
    }
}
