package com.qimeng.media.data.model

import java.util.Locale

object MediaType {
    const val IMAGE = "image"
    const val VIDEO = "video"
    const val ANIMATED_IMAGE = "animated_image"

    /** 静态图片扩展名（扫描索引口径） */
    val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")

    /** 动图扩展名（GIF 在 MediaStore.Images 中查询但识别为 ANIMATED_IMAGE） */
    val ANIMATED_IMAGE_EXTENSIONS = setOf("gif")

    /** 视频扩展名（mkv/avi/flv/webm 与 mp4/mov 同为 VIDEO） */
    val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "flv", "webm")

    /** PNG 扩展名单列：与 IMAGE_EXTENSIONS 其余静态图解码路径不同（详情页走 SpngDecoder） */
    val PNG_EXTENSIONS = setOf("png")

    /** 根据扩展名判断媒体类型（扩展名→媒体类型映射真相源），未知扩展名返回 null */
    fun mediaTypeForExtension(extension: String): String? {
        val ext = extension.lowercase(Locale.ROOT)
        return when {
            ext in IMAGE_EXTENSIONS -> IMAGE
            ext in ANIMATED_IMAGE_EXTENSIONS -> ANIMATED_IMAGE
            ext in VIDEO_EXTENSIONS -> VIDEO
            else -> null
        }
    }

    /** 从文件名提取小写扩展名（无扩展名返回空串），供散落扩展名判断统一复用 */
    fun extensionOf(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
}
