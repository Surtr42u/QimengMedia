package com.qimeng.media.core

object RecordKeyFactory {
    fun fromFileName(fileName: String): String = fileName.trim()

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
