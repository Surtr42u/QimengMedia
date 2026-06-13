package com.qimeng.media.backup

object BackupFileNames {
    // app数据/ 目录
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

    // 个人偏好/ 目录
    const val PERSONAL_PREFS = "personal_prefs.json"
    const val PERSONAL_PREFS_REPORT = "personal_prefs_report.txt"

    // 格式说明/ 目录 — 示例文件（隐私保护：AI 读取示例文件理解格式，不读取真实导出数据）
    const val FORMAT_SPEC = "FORMAT_SPEC.md"
    const val PERSONAL_PREFS_EXAMPLE = "personal_prefs_example.json"
    const val APP_DATA_EXAMPLES_DIR = "app_data_examples"
    const val SETTINGS_EXAMPLE = "settings_example.json"
    const val AUTHORS_EXAMPLE = "authors_example.json"
    const val TAGS_EXAMPLE = "tags_example.json"
    const val ALBUM_RULES_EXAMPLE = "album_rules_example.json"
    const val MEDIA_STATS_EXAMPLE = "media_stats_example.json"
    const val HISTORY_EXAMPLE = "history_example.json"
    const val SCAN_SOURCES_EXAMPLE = "scan_sources_example.json"
    const val LIKES_EXAMPLE = "likes_example.json"
    const val RECOMMENDATION_PREFS_EXAMPLE = "recommendation_prefs_example.json"
    const val TIMELINE_TAGS_EXAMPLE = "timeline_tags_example.json"

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
