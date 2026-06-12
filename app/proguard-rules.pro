# ==============================
# 绮梦影库 ProGuard 规则
# ==============================

# 保留行号信息用于调试崩溃日志
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ==============================
# Kotlin
# ==============================
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# ==============================
# Kotlin Coroutines
# ==============================
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}

# ==============================
# Room
# ==============================
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keepclassmembers class * extends androidx.room.Dao {
    <methods>;
}
-dontwarn androidx.room.paging.**

# ==============================
# Coil
# ==============================
-dontwarn coil.**
# Coil 核心组件（仅保留必要的，避免全量 keep）
-keep class coil3.ImageLoader { *; }
-keep class coil3.request.ImageRequest { *; }
-keep class coil3.request.CachePolicy { *; }
-keep class coil3.request.CachePolicy$Companion { *; }
-keep class coil3.size.Size { *; }
-keep class coil3.size.Scale { *; }
-keepclassmembers class coil3.ComponentRegistry {
    public <methods>;
}

# ==============================
# Media3 / ExoPlayer
# ==============================
-dontwarn com.google.android.exoplayer2.**
-dontwarn androidx.media3.**
# Media3 核心播放器组件（仅保留必要的，避免全量 keep）
-keep class androidx.media3.exoplayer.ExoPlayer { *; }
-keep class androidx.media3.exoplayer.SeekParameters { *; }
-keep class androidx.media3.common.AudioAttributes { *; }
-keep class androidx.media3.common.C { *; }
-keep class androidx.media3.common.PlaybackException { *; }
-keep class androidx.media3.common.util.UnstableApi { *; }
-keep class androidx.media3.ui.PlayerView { *; }
-keepclassmembers class androidx.media3.exoplayer.** {
    public <methods>;
    public <fields>;
}
-keepclassmembers class androidx.media3.common.** {
    public <methods>;
}

# ==============================
# AndroidX / ViewBinding
# ==============================
-keepclassmembers class * extends androidx.fragment.app.Fragment {
    public <methods>;
}
-keep class * implements androidx.viewbinding.ViewBinding {
    public static *** bind(android.view.View);
    public static *** inflate(android.view.LayoutInflater);
}

# ==============================
# 项目特定规则
# ==============================
# 保留实体类（Room 需要）
-keep class com.qimeng.media.data.db.entity.** { *; }
# 保留数据模型（序列化需要）
-keep class com.qimeng.media.data.model.** { *; }
# 保留 SourceMatcher 匹配逻辑
-keep class com.qimeng.media.ui.album.SourceMatcher { *; }
