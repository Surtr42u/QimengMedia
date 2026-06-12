package com.qimeng.media.backup

object BackupFileNames {
    const val SETTINGS = "settings.json"
    const val RECOMMENDATION_PREFS = "recommendation_prefs.json"
    const val MEDIA_STATS = "media_stats.json"
    const val HISTORY = "history.json"
    const val TAGS = "tags.json"
    const val TIMELINE_TAGS = "timeline_tags.json"
    const val ALBUM_RULES = "album_rules.json"
    const val AUTHORS = "authors.json"
    const val SCAN_SOURCES = "scan_sources.json"
    const val LIKES = "likes.json"
    const val PERSONAL_PREFS = "personal_prefs_export.json"
    const val PERSONAL_PREFS_REPORT = "personal_prefs_report.txt"
    const val COS_PREFS = "cos_prefs_export.json"
    const val COS_PREFS_REPORT = "cos_prefs_report.txt"

    val all: List<String> = listOf(
        SETTINGS,
        RECOMMENDATION_PREFS,
        MEDIA_STATS,
        HISTORY,
        TAGS,
        TIMELINE_TAGS,
        ALBUM_RULES,
        AUTHORS,
        SCAN_SOURCES,
        LIKES
    )
}
