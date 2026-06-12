package com.qimeng.media.backup

interface BackupRepository {
    suspend fun exportAll(exportedAtMillis: Long): List<String>
    suspend fun importAll(): List<String>
}
