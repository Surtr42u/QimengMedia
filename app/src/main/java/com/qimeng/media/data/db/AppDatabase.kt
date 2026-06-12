package com.qimeng.media.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.qimeng.media.data.db.dao.AlbumRuleDao
import com.qimeng.media.data.db.dao.AuthorDao
import com.qimeng.media.data.db.dao.CosWorkDao
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
        CosWorkEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun mediaFileDao(): MediaFileDao
    abstract fun viewStatsDao(): ViewStatsDao
    abstract fun viewHistoryDao(): ViewHistoryDao
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

        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
            .addMigrations(MIGRATION_1_2)
            .build().also { instance = it }
        }
    }
}
