package com.example.photofeed.data

import android.net.Uri

data class MediaItemUi(
    val mediaKey: String,
    val uri: Uri,
    val type: MediaType,
    val dateTaken: Long,
    val size: Long,
    val durationMs: Long?,
    val fileName: String? = null,
    /** 原始图片/视频像素尺寸（来自 MediaStore，用于长截图与画幅判断）。 */
    val width: Int = 0,
    val height: Int = 0,
    /** 动态照片：内嵌视频在文件中的起始偏移（相对文件头）。 */
    val motionVideoOffset: Long? = null,
    /** 动态照片：内嵌视频的字节数。 */
    val motionVideoLength: Long? = null,
    /** 配对型 Live Photo（图片 + 独立 MOV/MP4）的视频 Uri。 */
    val motionVideoUri: Uri? = null
) {
    enum class MediaType { PHOTO, VIDEO, MOTION }

    val hasEmbeddedMotionVideo: Boolean
        get() = motionVideoOffset != null && motionVideoLength != null && motionVideoLength > 0

    val hasMotionVideo: Boolean
        get() = hasEmbeddedMotionVideo || motionVideoUri != null

    /** 超长截图 / 超宽全景：短边铺满屏幕、长边靠拖动查看，绝不裁掉内容。 */
    val isLongImage: Boolean
        get() {
            if (width <= 0 || height <= 0) return false
            val longSide = maxOf(width, height).toFloat()
            val shortSide = minOf(width, height).toFloat()
            return longSide / shortSide >= 2.4f
        }
}
