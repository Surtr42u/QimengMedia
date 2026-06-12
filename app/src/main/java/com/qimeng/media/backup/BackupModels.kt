package com.qimeng.media.backup

const val CURRENT_BACKUP_SCHEMA_VERSION = 1

data class BackupEnvelope<T>(
    val schemaVersion: Int = CURRENT_BACKUP_SCHEMA_VERSION,
    val exportedAtMillis: Long,
    val data: T
)

data class SettingsBackup(
    val items: List<SettingBackupItem>
)

data class SettingBackupItem(
    val key: String,
    val value: String,
    val updatedAtMillis: Long
)

data class RecommendationPrefsBackup(
    val tagRelevance: Float,
    val tagCollection: Float,
    val engagement: Float,
    val recency: Float,
    val likeScore: Float,
    val discovery: Float,
    val freshness: Float,
    val browseDepth: Float,
    val maxRandom: Float
)

data class MediaStatsBackup(
    val items: List<MediaStatsBackupItem>
)

data class MediaStatsBackupItem(
    val recordKey: String,
    val fileName: String,
    val viewCount: Int,
    val playCount: Int,
    val totalBrowseSeconds: Long,
    val lastOpenedAtMillis: Long?,
    val updatedAtMillis: Long
)

data class HistoryBackup(
    val items: List<HistoryBackupItem>
)

data class HistoryBackupItem(
    val recordKey: String,
    val fileName: String,
    val mediaType: String,
    val openedAtMillis: Long
)

data class TagsBackup(
    val tags: List<TagBackupItem>,
    val mediaTags: List<MediaTagBackupItem>
)

data class TagBackupItem(
    val tagId: Long,
    val name: String,
    val createdAtMillis: Long
)

data class MediaTagBackupItem(
    val recordKey: String,
    val fileName: String,
    val tagName: String
)

data class TimelineTagsBackup(
    val items: List<TimelineTagBackupItem>
)

data class TimelineTagBackupItem(
    val recordKey: String,
    val fileName: String,
    val timeMillis: Long,
    val name: String,
    val createdAtMillis: Long
)

data class AlbumRulesBackup(
    val rules: List<AlbumRuleBackupItem>
)

data class AlbumRuleBackupItem(
    val sourceName: String,
    val enabled: Boolean,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class AuthorsBackup(
    val authors: List<AuthorBackupItem>
)

data class AuthorBackupItem(
    val authorId: String,
    val displayName: String,
    val files: List<AuthorFileBackupItem>,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

data class AuthorFileBackupItem(
    val recordKey: String,
    val fileName: String,
    val isMatched: Boolean
)

data class ScanSourcesBackup(
    val sources: List<ScanSourceBackupItem>
)

data class ScanSourceBackupItem(
    val uriString: String,
    val displayName: String,
    val isBackupDirectory: Boolean,
    val addedAtMillis: Long,
    val lastScannedAtMillis: Long?
)
