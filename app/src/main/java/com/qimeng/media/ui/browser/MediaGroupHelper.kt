package com.qimeng.media.ui.browser

import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.ui.album.SourceMatcher

/**
 * 媒体分组计算工具类，封装出处/角色/COS 分组的共享逻辑。
 * AllFilesFragment、FavoriteFragment、BrowseHistoryFragment、AuthorFilesFragment、AlbumDetailFragment 共用。
 */
object MediaGroupHelper {

    /** 按出处分组（非 COS） */
    fun groupBySource(media: List<MediaFileEntity>): Map<String, List<MediaFileEntity>> {
        val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
        for (m in media) {
            val source = SourceMatcher.match(m.fileName) ?: "其他"
            groups.getOrPut(source) { mutableListOf() }.add(m)
        }
        return groups
    }

    /** 按角色分组（非 COS） */
    fun groupByCharacter(files: List<MediaFileEntity>): Map<String, List<MediaFileEntity>> {
        val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
        for (file in files) {
            val charName = extractCharacterName(file)
            groups.getOrPut(charName) { mutableListOf() }.add(file)
        }
        return groups
    }

    /** 提取角色名 */
    fun extractCharacterName(file: MediaFileEntity): String {
        val (source, characters) = SourceMatcher.matchAll(file.fileName)
        if (source == null || characters.isEmpty()) return "其他"
        return characters.sorted().joinToString("+")
    }

    /**
     * COS 作者索引：recordKey → 作者显示名，O(1) 查找。
     *
     * 替代旧实现里"对每个 COS 文件线性扫描整个 authorMedia 列表"的 O(文件数×关联数) 嵌套查找。
     * 全库 COS 文件数千级、authorMedia 关联同量级时，旧实现单次分组即数千万次比较并在主线程执行，
     * 导致点击 COS 作者/相册进列表时明显卡顿。本索引构建一次 O(关联数+作者数)，循环内查找 O(1)。
     *
     * 取首条匹配语义与旧 [findCosAuthorForMedia] 的 `authorMedia.find { ... }` 完全等价：
     * 同一 recordKey 仅在首次遇到时写入，避免一个文件关联多个 cos_ 作者时的行为差异。
     * 仅收录 `cos_` 前缀作者，忽略常规作者，与旧实现一致。
     */
    class CosAuthorIndex private constructor(
        private val recordKeyToAuthorName: Map<String, String>
    ) {
        /** 返回该文件所属 COS 作者显示名；无关联或关联的是常规作者时返回"其他" */
        fun authorNameOf(recordKey: String): String = recordKeyToAuthorName[recordKey] ?: "其他"

        companion object {
            /** 从 authorMedia 关联表 + 作者表构建索引，O(关联数 + 作者数) */
            fun build(
                authorMedia: List<AuthorMediaCrossRef>,
                authors: List<AuthorEntity>
            ): CosAuthorIndex {
                if (authorMedia.isEmpty() || authors.isEmpty()) return CosAuthorIndex(emptyMap())
                val authorIdToName = authors.associate { it.authorId to it.displayName }
                val map = HashMap<String, String>(authorMedia.size)
                for (ref in authorMedia) {
                    // 合并过滤条件为单个守卫，避免循环内多个 continue（detekt LoopWithTooManyJumpStatements）：
                    // 仅收录 cos_ 前缀作者、未收录过的 recordKey、且作者仍在 authors 表中
                    val name = authorIdToName[ref.authorId]
                    if (ref.authorId.startsWith("cos_") && ref.recordKey !in map && name != null) {
                        map[ref.recordKey] = name // 取首条匹配，等价于旧 find 语义
                    }
                }
                return CosAuthorIndex(map)
            }
        }
    }

    /**
     * COS 作品索引：作者名 → 该作者的作品名列表，O(1) 取作品集 + O(作品数) 查找。
     *
     * 替代旧 [findCosCharacterForMedia] 里每次调用都 `cosWorks.filter { it.authorName == authorName }`
     * 的 O(全库作品数) 线性扫描。构建一次 O(作品数)，查找时只遍历该作者的作品（通常个位数）。
     */
    class CosWorkIndex private constructor(
        private val authorToWorks: Map<String, List<String>>
    ) {
        /** 返回该作者的全部作品名列表；无作品时返回空列表 */
        fun worksOf(authorName: String): List<String> = authorToWorks[authorName] ?: emptyList()

        companion object {
            /** 从 cosWorks 表构建索引，O(作品数) */
            fun build(cosWorks: List<CosWorkEntity>): CosWorkIndex {
                if (cosWorks.isEmpty()) return CosWorkIndex(emptyMap())
                val map = HashMap<String, MutableList<String>>()
                for (w in cosWorks) {
                    map.getOrPut(w.authorName) { mutableListOf() }.add(w.workName)
                }
                return CosWorkIndex(map.mapValues { it.value.toList() })
            }
        }
    }

    /**
     * 按COS作者分组。
     * 内部先构建 [CosAuthorIndex] 再遍历，复杂度 O(关联数 + 作者数 + 文件数)，
     * 替代旧实现的 O(文件数 × 关联数) 嵌套线性扫描。
     */
    fun groupByCosAuthor(
        media: List<MediaFileEntity>,
        authorMedia: List<AuthorMediaCrossRef>,
        authors: List<AuthorEntity>
    ): Map<String, List<MediaFileEntity>> {
        val index = CosAuthorIndex.build(authorMedia, authors)
        val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
        for (m in media) {
            val authorName = index.authorNameOf(m.recordKey)
            groups.getOrPut(authorName) { mutableListOf() }.add(m)
        }
        return groups
    }

    /**
     * 按COS作品分组。
     * 内部先构建 [CosAuthorIndex] + [CosWorkIndex] 再遍历，复杂度 O(关联数 + 作者数 + 作品数 + 文件数 × 单作者作品数)，
     * 替代旧实现的 O(文件数 × 关联数 + 文件数 × 作品数) 嵌套线性扫描。
     */
    fun groupByCosWork(
        media: List<MediaFileEntity>,
        authorMedia: List<AuthorMediaCrossRef>,
        authors: List<AuthorEntity>,
        cosWorks: List<CosWorkEntity>
    ): Map<String, List<MediaFileEntity>> {
        val authorIndex = CosAuthorIndex.build(authorMedia, authors)
        val workIndex = CosWorkIndex.build(cosWorks)
        val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
        for (m in media) {
            val charName = findCosCharacterForMedia(m, authorIndex, workIndex)
            groups.getOrPut(charName) { mutableListOf() }.add(m)
        }
        return groups
    }

    /**
     * 查找COS作者名（索引版，O(1)）。
     * 适用于循环/批量过滤场景：调用方先 [CosAuthorIndex.build] 构建一次，循环内调用本方法。
     */
    fun findCosAuthorForMedia(
        media: MediaFileEntity,
        index: CosAuthorIndex
    ): String = index.authorNameOf(media.recordKey)

    /**
     * 查找COS作者名（旧签名，O(关联数)）。
     * 保留用于单次查询与现有测试；批量/循环场景请用索引版避免 O(N×M) 嵌套扫描。
     */
    fun findCosAuthorForMedia(
        media: MediaFileEntity,
        authorMedia: List<AuthorMediaCrossRef>,
        authors: List<AuthorEntity>
    ): String {
        val crossRef = authorMedia.find { it.recordKey == media.recordKey && it.authorId.startsWith("cos_") }
        if (crossRef != null) {
            val author = authors.find { it.authorId == crossRef.authorId }
            if (author != null) return author.displayName
        }
        return "其他"
    }

    /**
     * 查找COS作品名（角色分组用，索引版）。
     * 适用于循环/批量过滤场景：调用方先构建 [CosAuthorIndex] + [CosWorkIndex]，循环内调用本方法。
     */
    fun findCosCharacterForMedia(
        media: MediaFileEntity,
        authorIndex: CosAuthorIndex,
        workIndex: CosWorkIndex
    ): String {
        val authorName = authorIndex.authorNameOf(media.recordKey)
        if (authorName == "其他") return "其他"
        val works = workIndex.worksOf(authorName)
        val matched = works.find { it == media.folderName }
        // workName == authorName 时归入"其他"（结构1：作者文件夹下直接含文件，作品名=作者名）
        if (matched != null && matched != authorName) return matched
        return "其他"
    }

    /**
     * 查找COS作品名（旧签名，O(关联数 + 作品数)）。
     * 保留用于单次查询与现有测试；批量/循环场景请用索引版。
     */
    fun findCosCharacterForMedia(
        media: MediaFileEntity,
        authorMedia: List<AuthorMediaCrossRef>,
        authors: List<AuthorEntity>,
        cosWorks: List<CosWorkEntity>
    ): String {
        val authorName = findCosAuthorForMedia(media, authorMedia, authors)
        if (authorName == "其他") return "其他"
        val authorWorks = cosWorks.filter { it.authorName == authorName }
        val matchedWork = authorWorks.find { it.workName == media.folderName }
        if (matchedWork != null && matchedWork.workName != matchedWork.authorName) {
            return matchedWork.workName
        }
        return "其他"
    }
}
