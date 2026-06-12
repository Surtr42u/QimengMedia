package com.qimeng.media.ui.browser

import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.ui.album.SourceMatcher

/**
 * 媒体分组计算工具类，封装出处/角色/COS 分组的共享逻辑。
 * AllFilesFragment、FavoriteFragment、BrowseHistoryFragment 共用。
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

    /** 按COS作者分组 */
    fun groupByCosAuthor(
        media: List<MediaFileEntity>,
        authorMedia: List<AuthorMediaCrossRef>,
        authors: List<AuthorEntity>
    ): Map<String, List<MediaFileEntity>> {
        val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
        for (m in media) {
            val authorName = findCosAuthorForMedia(m, authorMedia, authors)
            groups.getOrPut(authorName) { mutableListOf() }.add(m)
        }
        return groups
    }

    /** 按COS作品分组 */
    fun groupByCosWork(
        media: List<MediaFileEntity>,
        authorMedia: List<AuthorMediaCrossRef>,
        authors: List<AuthorEntity>,
        cosWorks: List<CosWorkEntity>
    ): Map<String, List<MediaFileEntity>> {
        val groups = mutableMapOf<String, MutableList<MediaFileEntity>>()
        for (m in media) {
            val charName = findCosCharacterForMedia(m, authorMedia, authors, cosWorks)
            groups.getOrPut(charName) { mutableListOf() }.add(m)
        }
        return groups
    }

    /** 查找COS作者名 */
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

    /** 查找COS作品名（角色分组用） */
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
