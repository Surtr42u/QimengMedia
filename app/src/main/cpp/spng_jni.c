/**
 * libspng JNI 桥接
 *
 * 功能：通过文件描述符解码 PNG 图片，返回 RGBA 像素数据。
 * 仅用于详情页大图 PNG 解码加速，不影响其他图片加载流程。
 *
 * 优化：单次 fd 打开，通过 lseek 回到文件头实现两阶段解码
 * （先获取信息，再解码像素），避免两次打开 ContentProvider。
 *
 * 安全设计：
 * - 所有 spng 资源在函数退出前必定释放（goto cleanup 模式）
 * - 输入参数校验（fd >= 0）
 * - 内存分配失败检查
 * - 解码失败时返回 NULL/FALSE，Kotlin 端回退到 BitmapFactory
 */

#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include "spng.h"

#define LOG_TAG "SpngJni"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/**
 * spng 读取回调：从文件描述符读取 PNG 数据。
 * 签名：int spng_rw_fn(spng_ctx *ctx, void *user, void *dst_src, size_t length)
 */
static int read_fn(spng_ctx *ctx, void *user, void *dst, size_t length) {
    (void)ctx;
    int fd = *(int *)user;
    ssize_t bytes_read = read(fd, dst, length);
    if (bytes_read < 0) return SPNG_IO_ERROR;
    if ((size_t)bytes_read < length) return SPNG_EOF;
    return 0;
}

/**
 * 获取 PNG 图片信息（宽、高、每行字节数），不解码像素。
 * 返回 int[3] { width, height, stride }，失败返回 NULL。
 *
 * 解码后通过 lseek 将 fd 回到文件头，供后续 nativeDecodePngToBuffer 使用，
 * 避免在 Kotlin 端重新打开 ContentProvider。
 */
JNIEXPORT jintArray JNICALL
Java_com_qimeng_media_core_SpngDecoder_nativeGetPngInfo(JNIEnv *env, jobject thiz, jint fd) {
    if (fd < 0) {
        LOGE("nativeGetPngInfo: invalid fd=%d", fd);
        return NULL;
    }

    spng_ctx *ctx = NULL;
    jintArray result = NULL;
    int user_fd = fd;
    off_t start_pos = lseek(fd, 0, SEEK_CUR); /* 记录当前偏移 */

    /* 创建 spng 上下文 */
    ctx = spng_ctx_new(0);
    if (!ctx) {
        LOGE("spng_ctx_new failed");
        goto cleanup;
    }

    /* 设置读取回调 */
    spng_set_png_stream(ctx, read_fn, &user_fd);

    /* 解析 PNG 头部，获取图片尺寸 */
    struct spng_ihdr ihdr;
    if (spng_get_ihdr(ctx, &ihdr) != 0) {
        LOGE("spng_get_ihdr failed, not a valid PNG");
        goto cleanup;
    }

    uint32_t stride = ihdr.width * 4; /* RGBA8 每像素 4 字节 */

    result = (*env)->NewIntArray(env, 3);
    if (!result) goto cleanup;

    jint info[3] = { (jint)ihdr.width, (jint)ihdr.height, (jint)stride };
    (*env)->SetIntArrayRegion(env, result, 0, 3, info);

cleanup:
    if (ctx) spng_ctx_free(ctx);
    /* 将 fd 回到文件头，供后续 nativeDecodePngToBuffer 使用 */
    if (start_pos >= 0) lseek(fd, start_pos, SEEK_SET);
    return result;
}

/**
 * 解码 PNG 像素数据到预分配的 DirectByteBuffer。
 * buffer 大小必须 >= width * height * 4 (RGBA8)。
 * 成功返回 JNI_TRUE，失败返回 JNI_FALSE。
 */
JNIEXPORT jboolean JNICALL
Java_com_qimeng_media_core_SpngDecoder_nativeDecodePngToBuffer(JNIEnv *env, jobject thiz,
                                                                 jint fd, jobject buffer) {
    if (fd < 0 || !buffer) {
        LOGE("nativeDecodePngToBuffer: invalid params fd=%d buffer=%p", fd, buffer);
        return JNI_FALSE;
    }

    spng_ctx *ctx = NULL;
    jboolean success = JNI_FALSE;
    int user_fd = fd;

    void *pixels = (*env)->GetDirectBufferAddress(env, buffer);
    if (!pixels) {
        LOGE("GetDirectBufferAddress failed, not a DirectByteBuffer");
        return JNI_FALSE;
    }

    jlong capacity = (*env)->GetDirectBufferCapacity(env, buffer);

    /* 创建 spng 上下文 */
    ctx = spng_ctx_new(0);
    if (!ctx) {
        LOGE("spng_ctx_new failed");
        goto cleanup;
    }

    /* 设置读取回调 */
    spng_set_png_stream(ctx, read_fn, &user_fd);

    /* 获取解码后图片大小，校验 buffer 容量 */
    size_t image_size = 0;
    if (spng_decoded_image_size(ctx, SPNG_FMT_RGBA8, &image_size) != 0) {
        LOGE("spng_decoded_image_size failed");
        goto cleanup;
    }

    if ((jlong)image_size > capacity) {
        LOGE("buffer too small: need %zu, have %lld", image_size, (long long)capacity);
        goto cleanup;
    }

    /* 解码为 RGBA8 格式，直接写入 buffer */
    int ret = spng_decode_image(ctx, pixels, image_size, SPNG_FMT_RGBA8, SPNG_DECODE_TRNS);
    if (ret != 0) {
        LOGE("spng_decode_image failed: %s (%d)", spng_strerror(ret), ret);
        goto cleanup;
    }

    success = JNI_TRUE;
    LOGD("spng decode success, size=%zu", image_size);

cleanup:
    if (ctx) spng_ctx_free(ctx);
    return success;
}
