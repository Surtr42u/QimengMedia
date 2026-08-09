package com.qimeng.media.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.qimeng.media.data.db.dao.AlbumRuleDao
import com.qimeng.media.data.db.dao.AuthorDao
import com.qimeng.media.data.db.dao.CosWorkDao
import com.qimeng.media.data.db.dao.DailyBrowseDao
import com.qimeng.media.data.db.dao.MediaFileDao
import com.qimeng.media.data.db.dao.ScanSourceDao
import com.qimeng.media.data.db.dao.SettingDao
import com.qimeng.media.data.db.dao.TagDao
import com.qimeng.media.data.db.dao.TimelineTagDao
import com.qimeng.media.data.db.dao.ViewHistoryDao
import com.qimeng.media.data.db.dao.ViewStatsDao
import com.qimeng.media.data.db.entity.AlbumRuleEntity
import com.qimeng.media.data.db.entity.AuthorEntity
import com.qimeng.media.data.db.entity.AuthorMediaCrossRef
import com.qimeng.media.data.db.entity.CosWorkEntity
import com.qimeng.media.data.db.entity.DailyBrowseEntity
import com.qimeng.media.data.db.entity.MediaFileEntity
import com.qimeng.media.data.db.entity.MediaTagCrossRef
import com.qimeng.media.data.db.entity.ScanSourceEntity
import com.qimeng.media.data.db.entity.SettingEntity
import com.qimeng.media.data.db.entity.TagEntity
import com.qimeng.media.data.db.entity.TimelineTagEntity
import com.qimeng.media.data.db.entity.ViewHistoryEntity
import com.qimeng.media.data.db.entity.ViewStatsEntity

@Database(
    entities = [
        MediaFileEntity::class,
        ViewStatsEntity::class,
        ViewHistoryEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        TimelineTagEntity::class,
        AlbumRuleEntity::class,
        AuthorEntity::class,
        AuthorMediaCrossRef::class,
        SettingEntity::class,
        ScanSourceEntity::class,
        CosWorkEntity::class,
        DailyBrowseEntity::class
    ],
    version = 6,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun viewStatsDao(): ViewStatsDao
    abstract fun viewHistoryDao(): ViewHistoryDao
    abstract fun dailyBrowseDao(): DailyBrowseDao
    abstract fun tagDao(): TagDao
    abstract fun timelineTagDao(): TimelineTagDao
    abstract fun albumRuleDao(): AlbumRuleDao
    abstract fun authorDao(): AuthorDao
    abstract fun settingDao(): SettingDao
    abstract fun scanSourceDao(): ScanSourceDao
    abstract fun cosWorkDao(): CosWorkDao

    companion object {
        private const val DATABASE_NAME = "qimeng_media.db"

        @Volatile
        private var instance: AppDatabase? = null

        private val MIGRATION_1_2 = androidx.room.migration.Migration(1, 2) {
            it.execSQL("ALTER TABLE media_files ADD COLUMN isCosFile INTEGER NOT NULL DEFAULT 0")
            it.execSQL("ALTER TABLE scan_sources ADD COLUMN isCosDirectory INTEGER NOT NULL DEFAULT 0")
            it.execSQL("CREATE TABLE IF NOT EXISTS cos_works (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, authorName TEXT NOT NULL, workName TEXT NOT NULL, folderUri TEXT NOT NULL, fileCount INTEGER NOT NULL, indexedAtMillis INTEGER NOT NULL)")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_cos_works_authorName ON cos_works(authorName)")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_cos_works_workName ON cos_works(workName)")
            it.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cos_works_authorName_workName ON cos_works(authorName, workName)")
        }

        // v1.7 索引审计：为 media_files.uriString 补索引，加速 LIKE 前缀查询
        // （getRecordKeysByUriPrefix / getCosRecordKeysByUriPrefix / getCosRecordKeysByUriPrefixLight）
        private val MIGRATION_2_3 = androidx.room.migration.Migration(2, 3) {
            it.execSQL("CREATE INDEX IF NOT EXISTS index_media_files_uriString ON media_files(uriString)")
        }

        // v1.12：media_files 增 sourceUri 归属列（扫描源 tree URI），根治旧版 URI 前缀匹配
        // 跨命名空间（content://media / document / tree）恒失败导致的启动误删全库问题。
        // sourceUri 为 null 表示旧版本数据，无法反推归属，清理逻辑对 null 保守保留。
        private val MIGRATION_3_4 = androidx.room.migration.Migration(3, 4) {
            it.execSQL("ALTER TABLE media_files ADD COLUMN sourceUri TEXT")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_media_files_sourceUri ON media_files(sourceUri)")
        }

        // v1.12：cos_works 增 sourceUri 归属列，支持多 COS 源共存。
        // 唯一索引从 (authorName, workName) 重构为 (sourceUri, authorName, workName)：
        // SQLite 需删旧索引再建新索引（同名冲突时无法直接重建为不同定义）。
        private val MIGRATION_4_5 = androidx.room.migration.Migration(4, 5) {
            it.execSQL("ALTER TABLE cos_works ADD COLUMN sourceUri TEXT NOT NULL DEFAULT ''")
            it.execSQL("DROP INDEX IF EXISTS index_cos_works_authorName_workName")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_cos_works_authorName_workName ON cos_works(authorName, workName)")
            it.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cos_works_sourceUri_authorName_workName ON cos_works(sourceUri, authorName, workName)")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_cos_works_folderUri ON cos_works(folderUri)")
        }

        // v1.14：新增按天浏览聚合表 daily_browse（每文件每天一行，无限保留，
        // 统计页趋势/窗口排行/沉浸时长的唯一数据来源）。仅建新表，无数据迁移。
        private val MIGRATION_5_6 = androidx.room.migration.Migration(5, 6) {
            it.execSQL(
                "CREATE TABLE IF NOT EXISTS daily_browse (" +
                    "recordKeyDay TEXT NOT NULL, " +
                    "recordKey TEXT NOT NULL, " +
                    "fileName TEXT NOT NULL, " +
                    "mediaType TEXT NOT NULL, " +
                    "dayStartMillis INTEGER NOT NULL, " +
                    "viewCount INTEGER NOT NULL, " +
                    "playCount INTEGER NOT NULL, " +
                    "totalBrowseSeconds INTEGER NOT NULL, " +
                    "updatedAtMillis INTEGER NOT NULL, " +
                    "PRIMARY KEY(recordKeyDay))"
            )
            it.execSQL("CREATE INDEX IF NOT EXISTS index_daily_browse_dayStartMillis ON daily_browse(dayStartMillis)")
            it.execSQL("CREATE INDEX IF NOT EXISTS index_daily_browse_recordKey ON daily_browse(recordKey)")
        }

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
            .build().also { instance = it }
        }
    }
}
