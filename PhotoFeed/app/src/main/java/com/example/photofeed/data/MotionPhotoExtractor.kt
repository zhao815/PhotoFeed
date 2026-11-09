package com.example.photofeed.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * 把动态照片里内嵌的 MP4 抽到应用私有缓存，交给播放器播放。
 * 直接把 JPEG 的 content Uri 丢给 ExoPlayer 是播不出动态片段的。
 */
object MotionPhotoExtractor {

    fun extract(context: Context, item: MediaItemUi): Uri? {
        item.motionVideoUri?.let { return it }
        val offset = item.motionVideoOffset ?: return null
        val length = item.motionVideoLength ?: return null
        if (offset <= 0 || length <= 0) return null

        val dir = File(context.cacheDir, "motion").apply { mkdirs() }
        val name = Integer.toHexString(item.mediaKey.hashCode())
        val target = File(dir, "$name.mp4")
        if (target.exists() && target.length() >= length) return Uri.fromFile(target)

        val tmp = File(dir, "$name.tmp")
        val input: InputStream = try {
            context.contentResolver.openInputStream(item.uri) ?: return null
        } catch (e: Exception) {
            return null
        }
        return try {
            var skipped = 0L
            while (skipped < offset) {
                val n = input.skip(offset - skipped)
                if (n <= 0) break
                skipped += n
            }
            if (skipped < offset) return null

            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(64 * 1024)
                var remaining = length
                while (remaining > 0) {
                    val read = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (read <= 0) break
                    out.write(buf, 0, read)
                    remaining -= read
                }
            }
            if (tmp.length() < 1024) {
                tmp.delete()
                return null
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) return null
            Uri.fromFile(target)
        } catch (e: Exception) {
            tmp.delete()
            null
        } finally {
            try {
                input.close()
            } catch (_: Exception) {
            }
        }
    }
}
