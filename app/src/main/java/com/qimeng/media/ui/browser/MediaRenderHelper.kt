package com.qimeng.media.ui.browser

import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.model.MediaType

/**
 * 媒体渲染共享计算工具类，封装 5 个 Fragment.render 中结构相同的纯计算逻辑。
 * 纯计算型 object，无 Android UI 依赖，不持有 Context/View。
 * 与 MediaGroupHelper（底层分组算法）协作，不替代其职责。
 */
object MediaRenderHelper {

    const val VIEW_MODE_PARTITION = "partition"
    const val VIEW_MODE_SOURCE = "source"
    const val VIEW_MODE_CHARACTER = "character"
    const val VIEW_MODE_TYPE = "type"

    /**
     * 按媒体类型筛选。
     * 替代 5 个 Fragment 中重复的 when(filterType) { IMAGE/VIDEO/ANIMATED_IMAGE/else } 块。
     *
     * @param media 待筛选的媒体列表
     * @param filterType 筛选类型，null 表示全部
     * @return 筛选后的列表
     */
    fun applyTypeFilter(
        media: List<MediaFileEntity>,
        filterType: String?
    ): List<MediaFileEntity> {
        return when (filterType) {
            MediaType.IMAGE -> media.filter { it.mediaType == MediaType.IMAGE }
            MediaType.VIDEO -> media.filter { it.mediaType == MediaType.VIDEO }
            MediaType.ANIMATED_IMAGE -> media.filter { it.mediaType == MediaType.ANIMATED_IMAGE }
            else -> media
        }
    }

    /**
     * 计算最终显示列表。
     * 按 viewMode + 选中状态从分组中过滤，替代 5 个 Fragment 中重复的 displayed 计算块。
     *
     * @param base 基础列表（applyFilter 后的结果）
     * @param sourceGroups 出处分组（无 source 维度时传 emptyMap）
     * @param charGroups 角色分组
     * @param selectedSources 选中的出处集合
     * @param selectedChars 选中的角色集合
     * @return 最终显示列表
     */
    fun computeDisplayed(
        base: List<MediaFileEntity>,
        sourceGroups: Map<String, List<MediaFileEntity>>,
        charGroups: Map<String, List<MediaFileEntity>>,
        selectedSources: Set<String>,
        selectedChars: Set<String>
    ): List<MediaFileEntity> {
        var displayed = base
        if (selectedSources.isNotEmpty()) {
            val sourceMediaSet = sourceGroups.filterKeys { it in selectedSources }.values.flatten().toSet()
            displayed = displayed.filter { it in sourceMediaSet }
        }
        if (selectedChars.isNotEmpty()) {
            val charMediaSet = charGroups.filterKeys { it in selectedChars }.values.flatten().toSet()
            displayed = displayed.filter { it in charMediaSet }
        }
        return displayed
    }

    /**
     * 构建渲染指纹，用于判断是否需要提交 adapter。
     * 替代 AllFiles/Favorite/BrowseHistory/AuthorFiles 四个 Fragment.updateXxxUI 中重复的 buildString 块。
     * AlbumDetailFragment 因字段不同（cos/sel 而非 partitions/src+chr），不使用此方法。
     *
     * @param params 指纹参数
     * @return 指纹字符串
     */
    fun buildFingerprint(params: FingerprintParams): String = buildString {
        append(params.viewMode)
        append("|partitions=")
        append(params.partitions.sorted().joinToString(","))
        append("|")
        append(params.displayed.size)
        append("|")
        append(params.displayed.firstOrNull()?.recordKey.orEmpty())
        append("|src=")
        append(params.selectedSources.sorted().joinToString(","))
        append("|chr=")
        append(params.selectedChars.sorted().joinToString(","))
        append("|ft=")
        append(params.filterType.orEmpty())
        append("|typeExp=")
        append(params.typePillsExpanded)
        if (params.viewMode == VIEW_MODE_SOURCE) {
            append("|")
            params.sourceGroups.entries.sortedBy { it.key }.forEach { (k, v) ->
                append("$k:${v.size},")
            }
        }
        if (params.viewMode == VIEW_MODE_CHARACTER) {
            append("|")
            params.charGroups.entries.sortedBy { it.key }.forEach { (k, v) ->
                append("$k:${v.size},")
            }
        }
    }

    /**
     * buildFingerprint 的参数封装，避免 LongParameterList 违规。
     */
    data class FingerprintParams(
        val viewMode: String,
        val partitions: Set<String>,
        val displayed: List<MediaFileEntity>,
        val selectedSources: Set<String>,
        val selectedChars: Set<String>,
        val filterType: String?,
        val typePillsExpanded: Boolean,
        val sourceGroups: Map<String, List<MediaFileEntity>>,
        val charGroups: Map<String, List<MediaFileEntity>>
    )
}
