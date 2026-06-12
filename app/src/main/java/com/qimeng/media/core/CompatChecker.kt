package com.qimeng.media.core

import android.content.Context
import android.os.Build
import android.os.StatFs

data class CompatResult(
    val items: List<CompatItem>
)

data class CompatItem(
    val name: String,
    val status: CompatStatus,
    val detail: String
)

enum class CompatStatus {
    OK,       // 通过
    WARN,     // 警告（可用但体验受限）
    ERROR     // 错误（不可用）
}

object CompatChecker {

    fun check(context: Context): CompatResult {
        val items = mutableListOf<CompatItem>()

        // 1. Android 版本检查
        val sdk = Build.VERSION.SDK_INT
        items.add(CompatItem(
            name = "Android 版本",
            status = if (sdk >= 31) CompatStatus.OK else CompatStatus.ERROR,
            detail = "API $sdk (${Build.VERSION.RELEASE})，要求 API 31+"
        ))

        // 2. SAF 持久授权检查
        val persistedUris = context.contentResolver.persistedUriPermissions
        items.add(CompatItem(
            name = "SAF 授权",
            status = if (persistedUris.isNotEmpty()) CompatStatus.OK else CompatStatus.WARN,
            detail = if (persistedUris.isNotEmpty()) "已授权 ${persistedUris.size} 个目录" else "尚未授权任何目录"
        ))

        // 3. 存储空间检查
        val stat = StatFs(context.filesDir.path)
        val availableBytes = stat.availableBytes
        val availableMB = availableBytes / (1024 * 1024)
        items.add(CompatItem(
            name = "可用存储",
            status = when {
                availableMB < 50 -> CompatStatus.ERROR
                availableMB < 500 -> CompatStatus.WARN
                else -> CompatStatus.OK
            },
            detail = "${availableMB} MB 可用"
        ))

        // 4. MediaStore 可用性检查
        try {
            val cursor = context.contentResolver.query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(android.provider.MediaStore.Images.Media._ID),
                null, null, null
            )
            cursor?.close()
            items.add(CompatItem(
                name = "MediaStore",
                status = CompatStatus.OK,
                detail = "系统媒体库可正常访问"
            ))
        } catch (e: Exception) {
            items.add(CompatItem(
                name = "MediaStore",
                status = CompatStatus.ERROR,
                detail = "无法访问系统媒体库：${e.message}"
            ))
        }

        // 5. 备份目录检查
        val backupStatus = runCatching {
            val writeUris = persistedUris.filter { it.isWritePermission }
            if (writeUris.isNotEmpty()) CompatStatus.OK else CompatStatus.WARN
        }.getOrDefault(CompatStatus.WARN)
        items.add(CompatItem(
            name = "备份写入",
            status = backupStatus,
            detail = if (backupStatus == CompatStatus.OK) "备份目录可写入" else "未设置备份目录或无写入权限"
        ))

        // 6. 设备信息
        items.add(CompatItem(
            name = "设备信息",
            status = CompatStatus.OK,
            detail = "${Build.MANUFACTURER} ${Build.MODEL} (API $sdk)"
        ))

        return CompatResult(items)
    }
}
