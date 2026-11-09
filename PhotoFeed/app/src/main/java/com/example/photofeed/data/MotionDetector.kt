package com.example.photofeed.data

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import java.io.InputStream

/**
 * 识别「动态照片」：安卓 Motion Photo（JPEG 内嵌 MP4）、各厂商变体，
 * 以及苹果 Live Photo 以 JPEG + 内嵌视频形式落到安卓上的情况。
 *
 * 低内存实现：XMP 标记一定在文件头部，先只读 512KB 判断是不是动态照片；
 * 普通照片立刻返回，不再把整个文件读完、也不构造数 MB 的字符串。
 * 确认是动态照片后，再以固定小缓冲流式扫描 MP4 的 ftyp box 定位内嵌视频。
 * 不依赖 ROM 的 special_format 列（部分 ROM，例如这台 vivo Android 11，根本没有该列）。
 */
object MotionDetector {
    private const val TAG = "MotionDetector"
    private const val HEAD_SCAN_BYTES = 512 * 1024
    private const val MAX_SCAN_BYTES = 64L * 1024 * 1024
    private const val CHUNK = 64 * 1024

    data class MotionInfo(val videoOffset: Long, val videoLength: Long)

    private val MARKERS = listOf(
        "MotionPhoto",
        "MicroVideo",
        "MotionPhoto_Data",           // 三星
        "Item:Semantic=\"MotionPhoto\"",
        "Item:Semantic=\"Video\"",
        "vivo_motion",
        "XiaomiMotion",
        "oppo_motion",
        "Huawei:MotionPhoto",
        "LivePhoto",
        "apple.live_photo",
        "ContentIdentifier"           // iPhone Live Photo 的配对标识
    )

    private val LENGTH_PATTERNS = listOf(
        Regex("""MicroVideoOffset[^0-9]{0,12}(\d+)"""),
        Regex("""Item:Length[^0-9]{0,12}(\d+)"""),
        Regex("""MotionPhotoVideoLength[^0-9]{0,12}(\d+)""")
    )

    /** 返回内嵌视频的位置；不是动态照片返回 null。 */
    fun detect(cr: ContentResolver, uri: Uri): MotionInfo? {
        val input: InputStream = try {
            cr.openInputStream(uri) ?: return null
        } catch (e: Exception) {
            Log.w(TAG, "open failed: ${e.message}")
            return null
        }
        try {
            // 阶段 1：只读文件头。绝大多数普通照片在这一步就返回，内存峰值 < 1MB。
            val head = ByteArray(HEAD_SCAN_BYTES)
            var headLen = 0
            while (headLen < HEAD_SCAN_BYTES) {
                val r = input.read(head, headLen, HEAD_SCAN_BYTES - headLen)
                if (r <= 0) break
                headLen += r
            }
            val xmp = String(head, 0, headLen, Charsets.ISO_8859_1)
            if (!MARKERS.any { xmp.contains(it, ignoreCase = true) }) return null

            val declaredLength = LENGTH_PATTERNS.firstNotNullOfOrNull { r ->
                r.find(xmp)?.groupValues?.get(1)?.toLongOrNull()
            }

            // 阶段 2：先在头部找 ftyp；找不到再带 7 字节重叠窗口往后流式扫描。
            var ftypStart: Long? = indexOfFtypBox(head.copyOf(headLen))
                .takeIf { it >= 0 }?.toLong()
            var total = headLen.toLong()
            if (headLen < HEAD_SCAN_BYTES) {
                // 文件已在阶段 1 读完，无需再扫
                return buildResult(ftypStart, declaredLength, total)
            }
            val buf = ByteArray(CHUNK)
            var carry = head.copyOfRange(headLen - 7, headLen)
            var carryBase = total - carry.size
            while (total < MAX_SCAN_BYTES) {
                val read = input.read(buf)
                if (read <= 0) break
                total += read
                if (ftypStart != null) continue   // 已定位，只需读完求长度
                val window = ByteArray(carry.size + read)
                System.arraycopy(carry, 0, window, 0, carry.size)
                System.arraycopy(buf, 0, window, carry.size, read)
                val idx = indexOfFtypBox(window)
                if (idx >= 0) {
                    ftypStart = carryBase + idx
                } else {
                    val keep = minOf(7, window.size)
                    carry = window.copyOfRange(window.size - keep, window.size)
                    carryBase = total - carry.size
                }
            }
            return buildResult(ftypStart, declaredLength, total)
        } catch (e: Exception) {
            Log.w(TAG, "detect failed: ${e.message}")
            return null
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildResult(ftypStart: Long?, declaredLength: Long?, total: Long): MotionInfo? {
        val offset = ftypStart ?: declaredLength?.let { total - it } ?: return null
        val length = total - offset
        if (offset <= 0 || length < 1024) return null
        return MotionInfo(offset, length)
    }

    /** 找到 "ftyp" 后回退 4 字节，即 MP4 box 的 size 字段起点。 */
    private fun indexOfFtypBox(w: ByteArray): Int {
        var i = 4
        while (i + 7 < w.size) {
            if (w[i] == 'f'.code.toByte() && w[i + 1] == 't'.code.toByte() &&
                w[i + 2] == 'y'.code.toByte() && w[i + 3] == 'p'.code.toByte()
            ) {
                return i - 4
            }
            i++
        }
        return -1
    }
}
