package com.qimeng.media.domain

import com.qimeng.media.core.AppLog
import com.qimeng.media.data.db.dao.MediaFileDao
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.SettingEntity
import com.qimeng.media.data.repository.LocalMediaRepository

/**
 * 作者导入 UseCase：负责从文本导入作者、匹配媒体文件等操作。
 */
class AuthorImportUseCase(
    private val repository: LocalMediaRepository
) {
    /** 从文本导入作者，支持块格式和简单行格式。
     *
     * 块格式（A/B）：先保存当前 TXT 的 blocks 到 settings 表，再调用 [rebuildAssociationsFromBlocks]
     * 从**全部已导入 TXT** 统一重建作者关联。这样跨 TXT 的同名作者关联不会被当前 TXT 的"删旧插新"覆盖，
     * 避免先扫描文件后导入 TXT 时某作者关联被清空为 0 的问题。
     * 简单行格式（C）：只创建作者，不关联文件，不触发重建。 */
    suspend fun importAuthorsFromText(text: String, fileName: String = "", scanUseCase: ScanUseCase) {
        cleanupFileNameAuthors()
        val now = System.currentTimeMillis()
        val blocks = parseAuthorBlocks(text)
        AppLog.d("AuthorImport", "importFromText: fileName=$fileName, blocks=${blocks.size}")
        if (blocks.isNotEmpty()) {
            // 先保存当前 TXT 的 blocks，使重建能读到它
            if (fileName.isNotBlank()) {
                saveTxtBlocks(fileName, blocks)
                addImportedTxtFile(fileName)
            }
            // 从全部已导入 TXT 重建关联（含当前 TXT），跨 TXT 同名作者关联取并集
            rebuildAssociationsFromBlocks(scanUseCase)
        } else {
            val authorEntities = text.lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !looksLikeFileName(it) }
                .map { line ->
                    val authorId = scanUseCase.generateAuthorId(line)
                    AuthorEntity(authorId = authorId, displayName = line, createdAtMillis = now, updatedAtMillis = now)
                }
            repository.upsertAllAuthors(authorEntities)
            if (fileName.isNotBlank()) {
                saveTxtBlocks(fileName, blocks)
                addImportedTxtFile(fileName)
            }
        }
    }

    /** 轻量版匹配：使用 NonCosKeyFileName 替代完整 MediaFileEntity
     *  匹配规则（按优先级）：
     *  1. 精确匹配：去空格+去扩展名后完整一致
     *  2. 基础名+序号括号匹配：TXT 作品名是文件基础名，文件尾部仅有 (N) 序号括号差异
     *     例如 TXT 写 "守望先锋  朱诺 6" 可匹配 "守望先锋  朱诺 6 (1).jpg"
     *  不做前缀匹配：TXT 写 "崩坏 星穹铁道" 不会匹配 "崩坏 星穹铁道 卡芙卡 1.mp4" */
    fun findMatchingMediaLight(workName: String, allMedia: List<MediaFileDao.NonCosKeyFileName>): List<MediaFileDao.NonCosKeyFileName> {
        val normalized = workName.trim()
        if (normalized.isBlank()) return emptyList()
        val collapsed = normalized.replace("\\s+".toRegex(), "")
        val ext = normalized.substringAfterLast('.', "").lowercase()
        val hasExt = ext in setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tiff", "tif")
        val nameNoExt = if (hasExt) normalized.substringBeforeLast('.') else normalized
        val collapsedNoExt = if (hasExt) collapsed.substringBeforeLast('.') else collapsed
        // 序号括号模式：匹配尾部 (N) 或 (NN) 等
        val suffixPattern = Regex("""\(\d+\)$""")
        // 基础名：去掉尾部的序号括号（如 "守望先锋  朱诺 6 (1)" → "守望先锋  朱诺 6"）
        val collapsedBase = collapsedNoExt.replace(suffixPattern, "")

        val result = allMedia.filter { media ->
            val nameNoExtMedia = media.fileName.substringBeforeLast('.')
            val collapsedMedia = nameNoExtMedia.replace("\\s+".toRegex(), "")

            // 规则1：精确匹配（去空格后完整一致）
            val exactMatch = nameNoExtMedia.equals(nameNoExt, ignoreCase = true) ||
                (collapsedNoExt.isNotBlank() && collapsedMedia.equals(collapsedNoExt, ignoreCase = true))
            if (exactMatch) {
                // 扩展名检查
                return@filter if (hasExt) {
                    val mediaExt = media.fileName.substringAfterLast('.', "").lowercase()
                    mediaExt == ext
                } else true
            }

            // 规则2：基础名+序号括号匹配（仅当 TXT 作品名不含扩展名且不含序号括号时）
            // 例如 TXT 写 "守望先锋  朱诺 6" 可匹配 "守望先锋  朱诺 6 (1).jpg"
            if (!hasExt && collapsedBase == collapsedNoExt && collapsedBase.isNotBlank()) {
                // TXT 作品名不含序号括号，检查文件名是否 = 基础名 + (N)
                val mediaBase = collapsedMedia.replace(suffixPattern, "")
                if (mediaBase.equals(collapsedBase, ignoreCase = true) && collapsedMedia != collapsedBase) {
                    // 文件名去序号括号后与 TXT 基础名一致，且文件名确实有序号括号
                    return@filter true
                }
            }

            false
        }
        // 诊断日志
        if (result.isNotEmpty()) {
            AppLog.d("AuthorImport", "findMatch: workName=[$workName] collapsedNoExt=[$collapsedNoExt] collapsedBase=[$collapsedBase] matched=${result.size} files: ${result.take(3).map { it.fileName }}")
        }
        return result
    }

    private fun looksLikeFileName(text: String): Boolean {
        val ext = text.substringAfterLast('.', "").lowercase()
        return ext in setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm",
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "tiff", "tif", "txt", "doc", "pdf")
    }

    private suspend fun cleanupFileNameAuthors() {
        val authors = repository.getAllAuthors()
        val fileNameAuthorIds = authors.filter { looksLikeFileName(it.displayName) }.map { it.authorId }
        if (fileNameAuthorIds.isNotEmpty()) {
            repository.deleteCrossRefsByAuthorIds(fileNameAuthorIds)
            repository.deleteAuthorsByIds(fileNameAuthorIds)
        }
        repository.deleteOrphanAuthors()
    }

    private suspend fun addImportedTxtFile(fileName: String) {
        val current = repository.getSetting(KEY_IMPORTED_TXT_FILES)?.value ?: "[]"
        val arr = try { org.json.JSONArray(current) } catch (_: Exception) { org.json.JSONArray() }
        if ((0 until arr.length()).none { arr.getString(it) == fileName }) {
            arr.put(fileName)
        }
        repository.upsertSetting(
            SettingEntity(key = KEY_IMPORTED_TXT_FILES, value = arr.toString(), updatedAtMillis = System.currentTimeMillis())
        )
    }

    /** 保存TXT导入的结构化数据，用于后续重新匹配 */
    private suspend fun saveTxtBlocks(fileName: String, blocks: List<AuthorBlock>) {
        val key = KEY_IMPORTED_TXT_BLOCKS_PREFIX + fileName
        val jsonArr = org.json.JSONArray()
        for (block in blocks) {
            val obj = org.json.JSONObject().apply {
                put("authorNames", org.json.JSONArray(block.authorNames))
                put("sources", org.json.JSONArray(block.sources))
                put("works", org.json.JSONArray(block.works))
            }
            jsonArr.put(obj)
        }
        repository.upsertSetting(
            SettingEntity(key = key, value = jsonArr.toString(), updatedAtMillis = System.currentTimeMillis())
        )
    }

    /** 重新匹配单个已导入的TXT文件与当前库中的非COS文件
     *  用于刷新时：URI 读取失败时的降级方案，用已保存的 blocks 重新匹配。
     *
     *  实际委托 [rebuildAssociationsFromBlocks] 从全部已导入 TXT 重建关联，
     *  避免单 TXT 删旧插新覆盖跨 TXT 同名作者关联。 */
    suspend fun rematchSingleTxtImport(fileName: String, scanUseCase: ScanUseCase) {
        val blocks = loadTxtBlocks(fileName)
        if (blocks.isEmpty()) return
        AppLog.d("AuthorImport", "rematchSingle: txt=$fileName blocks=${blocks.size}, delegate to rebuild")
        rebuildAssociationsFromBlocks(scanUseCase)
    }

    /** 重新匹配所有已导入的TXT文件与当前库中的非COS文件
     *  在扫描新文件后调用，确保新文件也能被关联到已有TXT导入的作者。
     *  实际委托 [rebuildAssociationsFromBlocks] 执行全量重建。 */
    suspend fun rematchAllTxtImports(scanUseCase: ScanUseCase) {
        val txtFileNames = getImportedTxtFileNames()
        if (txtFileNames.isEmpty()) return
        AppLog.d("AuthorImport", "rematchAllTxtImports: ${txtFileNames.size} txt files")
        // 诊断：打印所有已导入 TXT 的 blocks 内容
        for (fileName in txtFileNames) {
            val blocks = loadTxtBlocks(fileName)
            AppLog.d("AuthorImport", "rematch: txt=$fileName blocks=${blocks.size}")
            for ((idx, block) in blocks.withIndex()) {
                AppLog.d("AuthorImport", "rematch: block[$idx] authors=${block.authorNames} works=${block.works.take(5)}${if (block.works.size > 5) "...+${block.works.size - 5}" else ""}")
            }
        }
        rebuildAssociationsFromBlocks(scanUseCase)
    }

    /** 从全部已导入 TXT 的 blocks 统一重建作者与文件关联。
     *
     *  统一重建入口，被以下场景复用，确保跨 TXT 同名作者的关联取并集、不被单 TXT 删旧插新覆盖：
     *  - TXT 导入完成（[importAuthorsFromText] 块格式分支）
     *  - TXT 刷新降级（[rematchSingleTxtImport]）
     *  - 常规扫描完成（[rematchAllTxtImports] → 本方法）
     *  - 删除 TXT 后清理残留（[com.qimeng.media.ui.library.MediaLibraryViewModel.removeImportedTxtFile]）
     *
     *  逻辑：遍历全部已导入 TXT 的 blocks → 每块生成 AuthorEntity + 用 [findMatchingMediaLight] 匹配当前库非 COS 文件 →
     *  统一 upsertAllAuthors + deleteCrossRefsByAuthorIds(全部相关作者) + upsertAllAuthorMedia(全部 crossRef)。
     *  因为先删全部相关作者旧关联再插全量新关联，跨 TXT 同名作者关联 = 所有 TXT 该作者匹配文件并集，不会丢。 */
    suspend fun rebuildAssociationsFromBlocks(scanUseCase: ScanUseCase) {
        val txtFileNames = getImportedTxtFileNames()
        if (txtFileNames.isEmpty()) return
        val nonCosKeysAndNames = repository.getNonCosKeysAndFileNames()
        if (nonCosKeysAndNames.isEmpty()) return

        AppLog.d("AuthorImport", "rebuild: ${txtFileNames.size} txt files, ${nonCosKeysAndNames.size} media files")

        val now = System.currentTimeMillis()
        val authorEntities = mutableListOf<AuthorEntity>()
        val crossRefs = mutableListOf<AuthorMediaCrossRef>()

        for (fileName in txtFileNames) {
            val blocks = loadTxtBlocks(fileName)
            for (block in blocks) {
                val validNames = block.authorNames.filter { !looksLikeFileName(it) }
                if (validNames.isEmpty()) continue
                val primaryName = validNames.first()
                val displayName = validNames.joinToString(" / ")
                val authorId = scanUseCase.generateAuthorId(primaryName)
                authorEntities.add(AuthorEntity(authorId = authorId, displayName = displayName, createdAtMillis = now, updatedAtMillis = now))
                for (workName in block.works) {
                    val matched = findMatchingMediaLight(workName, nonCosKeysAndNames)
                    for (media in matched) {
                        crossRefs.add(AuthorMediaCrossRef(authorId = authorId, recordKey = media.recordKey, fileName = media.fileName, isMatched = true))
                    }
                }
            }
        }

        if (authorEntities.isNotEmpty()) {
            repository.upsertAllAuthors(authorEntities)
            // 先删除这些作者的旧 crossRef，再插入新的（避免匹配规则变更后旧数据残留）
            repository.deleteCrossRefsByAuthorIds(authorEntities.map { it.authorId })
        }
        if (crossRefs.isNotEmpty()) {
            repository.upsertAllAuthorMedia(crossRefs)
        }

        AppLog.d("AuthorImport", "rebuild: done, authors=${authorEntities.size}, crossRefs=${crossRefs.size}")
    }

    /** 获取所有已导入的TXT文件名列表 */
    private suspend fun getImportedTxtFileNames(): List<String> {
        val current = repository.getSetting(KEY_IMPORTED_TXT_FILES)?.value ?: "[]"
        return try {
            val arr = org.json.JSONArray(current)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            AppLog.d("AuthorImport", "getImportedTxtFileNames failed: ${e.message}")
            emptyList()
        }
    }

    /** 加载已保存的TXT结构化数据 */
    private suspend fun loadTxtBlocks(fileName: String): List<AuthorBlock> {
        val key = KEY_IMPORTED_TXT_BLOCKS_PREFIX + fileName
        val json = repository.getSetting(key)?.value ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val names = (0 until obj.getJSONArray("authorNames").length()).map { obj.getJSONArray("authorNames").getString(it) }
                val sources = (0 until obj.getJSONArray("sources").length()).map { obj.getJSONArray("sources").getString(it) }
                val works = (0 until obj.getJSONArray("works").length()).map { obj.getJSONArray("works").getString(it) }
                AuthorBlock(names, sources, works)
            }
        } catch (e: Exception) {
            AppLog.d("AuthorImport", "loadTxtBlocks failed for $fileName: ${e.message}")
            emptyList()
        }
    }

    /** 保存TXT文件的URI，用于刷新时重新读取 */
    suspend fun saveTxtUri(fileName: String, uriString: String) {
        val key = KEY_IMPORTED_TXT_URI_PREFIX + fileName
        repository.upsertSetting(
            SettingEntity(key = key, value = uriString, updatedAtMillis = System.currentTimeMillis())
        )
    }

    /** 加载已保存的TXT文件URI */
    suspend fun loadTxtUri(fileName: String): String? {
        val key = KEY_IMPORTED_TXT_URI_PREFIX + fileName
        return repository.getSetting(key)?.value
    }

    /** 删除TXT文件时清理对应的URI数据 */
    suspend fun removeImportedTxtUri(fileName: String) {
        val key = KEY_IMPORTED_TXT_URI_PREFIX + fileName
        repository.deleteSetting(key)
    }

    /** 删除TXT文件时清理对应的blocks数据 */
    suspend fun removeImportedTxtBlocks(fileName: String) {
        val key = KEY_IMPORTED_TXT_BLOCKS_PREFIX + fileName
        repository.deleteSetting(key)
    }

    private data class AuthorBlock(val authorNames: List<String>, val sources: List<String>, val works: List<String>)

    private fun parseAuthorBlocks(text: String): List<AuthorBlock> {
        val blocks = mutableListOf<AuthorBlock>()
        val lines = text.lines().map { it.trim() }
        var currentAuthors = listOf<String>()
        var currentSources = mutableListOf<String>()
        var currentWorks = mutableListOf<String>()
        var inSources = false
        var inWorks = false

        for (line in lines) {
            if (line.isBlank()) continue
            val authorLineMatch = Regex("^\\d+\\s+(.+)$").matchEntire(line)
            if (authorLineMatch != null) {
                if (currentAuthors.isNotEmpty()) {
                    blocks.add(AuthorBlock(currentAuthors, currentSources.toList(), currentWorks.toList()))
                }
                currentAuthors = authorLineMatch.groupValues[1].split(Regex("\\s{2,}")).map { it.trim() }.map { it.replace(Regex("\\s*[(\\[（【].+$"), "") }.filter { it.isNotBlank() }
                currentSources = mutableListOf()
                currentWorks = mutableListOf()
                inSources = false
                inWorks = false
                continue
            }
            if (line.startsWith("出处") || line.startsWith("来源")) {
                inSources = true; inWorks = false
                val rest = line.removePrefix("出处").removePrefix("来源").trim()
                if (rest.isNotBlank()) currentSources.add(rest)
                continue
            }
            if (line == "作品") { inSources = false; inWorks = true; continue }
            if (inSources) {
                val urlMatch = Regex("`([^`]+)`").find(line)
                if (urlMatch != null) currentSources.add(urlMatch.groupValues[1])
                val platformNames = line.replace(Regex("`[^`]+`"), "").trim()
                if (platformNames.isNotBlank()) currentSources.add(platformNames)
                continue
            }
            if (inWorks) {
                currentWorks.add(line)
                continue
            }
        }
        if (currentAuthors.isNotEmpty()) {
            blocks.add(AuthorBlock(currentAuthors, currentSources.toList(), currentWorks.toList()))
        }
        return blocks
    }

    companion object {
        const val KEY_IMPORTED_TXT_FILES = "imported_txt_files"
        const val KEY_IMPORTED_TXT_BLOCKS_PREFIX = "imported_txt_blocks_"
        const val KEY_IMPORTED_TXT_URI_PREFIX = "imported_txt_uri_"
    }
}
