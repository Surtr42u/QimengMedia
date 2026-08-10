package com.qimeng.media.core

object RecordKeyFactory {
    fun fromFileName(fileName: String): String = fileName.trim()

    /**
     * 重名文件高级区分 key。
     *
     * 已知限制（v1.x 注明，不修复）：文件名本身若包含分隔符「 @ 」或「#」，
     * 会与上述拼接结构产生歧义（无法从 key 无损还原文件名/文件夹名），属已知限制。
     * 因既有 recordKey 已在用户设备上持久化（关联统计/历史/标签/收藏/点赞），
     * 改动分隔符会破坏既有 key 的稳定性，故保持现状。
     */
    fun fromDuplicateFileName(
        fileName: String,
        folderName: String?,
        shortPathHash: String?
    ): String {
        val cleanFileName = fileName.trim()
        val cleanFolderName = folderName?.trim().orEmpty()
        val cleanHash = shortPathHash?.trim().orEmpty()

        return when {
            cleanFolderName.isNotEmpty() && cleanHash.isNotEmpty() ->
                "$cleanFileName @ $cleanFolderName #$cleanHash"
            cleanFolderName.isNotEmpty() ->
                "$cleanFileName @ $cleanFolderName"
            cleanHash.isNotEmpty() ->
                "$cleanFileName #$cleanHash"
            else -> cleanFileName
        }
    }
}
