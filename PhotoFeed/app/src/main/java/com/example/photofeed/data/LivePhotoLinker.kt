package com.example.photofeed.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileInputStream

/**
 * 把「苹果 Live Photo / vivo 动态照片」的静帧与视频半体配对。
 *
 * 这类素材落到安卓后并不是 JPEG 内嵌 MP4，而是两个独立文件：
 *   · 静帧 JPEG（相册里能看到的那个）
 *   · 短视频 MP4/MOV（带原声，通常被相册隐藏，不单独显示）
 * 两个文件的尾部都写了同一个内容 ID，例如：
 *   vivo{..."com.android.camera.livephoto":"6f6fe497823940a8af0cae0ee73c"...}
 * 苹果原生导出则是在 XMP / QuickTime 元数据里写 ContentIdentifier(UUID)。
 * 文件名往往完全不同（微信导出会各自改名），所以「同名配对」找不到它们，
 * 必须按这个内容 ID 配对。
 *
 * 只读文件头 128KB 与文件尾 32KB，不整文件读入内存；普通照片扫完立刻跳过。
 */
object LivePhotoLinker {

    private const val TAG = "PhotoFeed"
    private const val HEAD_BYTES = 128 * 1024
    private const val TAIL_BYTES = 32 * 1024

    private val VIVO_ID =
        Regex(""""com\.android\.camera\.livephoto"\s*:\s*"([A-Za-z0-9_-]{8,})"""")
    private val APPLE_UUID =
        Regex("""[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}""")
    private val APPLE_MARKERS = listOf(
        "com.apple.quicktime.content.identifier",
        "ContentIdentifier",
        "content.identifier"
    )
    private val VIDEO_EXT = setOf("mp4", "mov", "m4v", "3gp", "mkv")

    data class ScanResult(
        /** 已经配上视频、可以直接播放的 Live Photo。 */
        val paired: List<MediaItemUi>,
        /** 识别出是 Live Photo 静帧、但视频半体不在媒体库（例如被移进隐藏回收站）。 */
        val orphans: List<MediaItemUi>
    )

    /** 完整扫描一次，既给可播放的配对，也给「只有静帧」的条目。 */
    fun scan(
        context: Context,
        images: List<MediaItemUi>,
        videos: List<MediaItemUi>
    ): ScanResult {
        val cr = context.contentResolver
        // 内容 ID 按 mediaKey 缓存，连「扫过但普通照片」的负结果也存下来。
        // mediaKey 含 _id / dateTaken / size，文件被替换后指纹会变，不会误用旧结果。
        // 启动时动图频道已经扫过一遍，设置页再点「重新识别」只解析新增文件。
        val idCache = IdCache(context)
        fun flush() {
            idCache.retain((images + videos).map { it.mediaKey }.toHashSet())
            idCache.save(context)
        }
        val imageById = LinkedHashMap<String, MediaItemUi>()
        images.forEach { img ->
            val id = idCache.get(cr, img) ?: return@forEach
            imageById.putIfAbsent(id, img)
        }
        if (imageById.isEmpty()) {
            Log.d(TAG, "no live-photo still found (images=${images.size})")
            flush()
            return ScanResult(emptyList(), emptyList())
        }

        val videoById = HashMap<String, Uri>()
        videos.forEach { v ->
            val id = idCache.get(cr, v) ?: return@forEach
            if (imageById.containsKey(id)) videoById.putIfAbsent(id, v.uri)
        }

        // 部分 ROM 允许第三方 App 直接读隐藏回收站，按同一内容 ID 补找视频；
        // Android 11+ 作用域存储下这一步通常读不到，此时保留静帧并提示用户恢复。
        val missing = imageById.keys - videoById.keys
        if (missing.isNotEmpty()) {
            hiddenVideoDirs().forEach { dir ->
                val files = try {
                    dir.listFiles { f -> f.isFile && f.extension.lowercase() in VIDEO_EXT }
                } catch (e: Exception) {
                    null
                } ?: return@forEach
                files.forEach { f ->
                    val id = idOfFile(f) ?: return@forEach
                    if (id in missing) videoById.putIfAbsent(id, Uri.fromFile(f))
                }
            }
        }

        val paired = ArrayList<MediaItemUi>()
        val orphans = ArrayList<MediaItemUi>()
        imageById.forEach { (id, img) ->
            val uri = videoById[id]
            if (uri != null) {
                paired.add(
                    img.copy(type = MediaItemUi.MediaType.MOTION, motionVideoUri = uri)
                )
            } else {
                orphans.add(img.copy(type = MediaItemUi.MediaType.MOTION))
            }
        }
        Log.d(
            TAG,
            "live-photo scan: stills=${imageById.size} paired=${paired.size} " +
                "orphans=${orphans.size} videos=${videos.size} " +
                "parsed=${idCache.parsed} cached=${idCache.size - idCache.parsed} " +
                "hiddenDirs=${hiddenVideoDirs().size}"
        )
        flush()
        return ScanResult(paired, orphans)
    }

    /** 内容 ID 的持久化缓存；命中就跳过整个文件的 IO。 */
    private class IdCache(context: Context) {
        private val map = MotionPrefs.linkedIds(context).toMutableMap()
        private var dirty = false

        /** 本次真正打开文件解析的数量，用于验证增量是否生效。 */
        var parsed = 0
            private set
        val size: Int get() = map.size

        fun get(cr: ContentResolver, item: MediaItemUi): String? {
            val hit = map[item.mediaKey]
            if (hit != null) return hit.takeIf { it != MotionPrefs.NO_LINK_ID }
            val id = idOfUri(cr, item.uri, item.size)
            map[item.mediaKey] = id ?: MotionPrefs.NO_LINK_ID
            parsed++
            dirty = true
            return id
        }

        /** 媒体库里已经没有的文件不再占缓存；隐藏回收站条目不入库，不在这里管。 */
        fun retain(liveKeys: Set<String>) {
            if (map.keys.retainAll(liveKeys)) dirty = true
        }

        fun save(context: Context) {
            if (dirty) MotionPrefs.saveLinkedIds(context, map)
        }
    }

    /** 只要可播放的配对（设置页统计用）。 */
    fun link(
        context: Context,
        images: List<MediaItemUi>,
        videos: List<MediaItemUi>
    ): List<MediaItemUi> = scan(context, images, videos).paired

    private fun hiddenVideoDirs(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            File(root, ".vivoFileRecycleBin"),
            File(root, ".vivoRecycleBin"),
            File(root, ".Trash"),
            File(root, ".recycle")
        ).filter { it.isDirectory }
    }

    private fun idOfUri(cr: ContentResolver, uri: Uri, size: Long): String? {
        val afd = try {
            cr.openAssetFileDescriptor(uri, "r")
        } catch (e: Exception) {
            null
        } ?: return null
        return try {
            val total = if (afd.length > 0) afd.length else size
            if (total <= 0) return null
            val headLen = minOf(HEAD_BYTES.toLong(), total).toInt()
            val tailLen = minOf(TAIL_BYTES.toLong(), total - headLen).toInt()
            val head = ByteArray(headLen)
            val tail = ByteArray(tailLen)
            val fis = FileInputStream(afd.fileDescriptor)
            try {
                fis.channel.position(afd.startOffset)
                readFully(fis, head)
                if (tailLen > 0) {
                    fis.channel.position(afd.startOffset + total - tailLen)
                    readFully(fis, tail)
                }
            } finally {
                fis.close()
            }
            extractId(head, tail)
        } catch (e: Exception) {
            null
        } finally {
            try {
                afd.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun idOfFile(file: File): String? {
        val total = file.length()
        if (total < 64) return null
        return try {
            val headLen = minOf(HEAD_BYTES.toLong(), total).toInt()
            val tailLen = minOf(TAIL_BYTES.toLong(), total - headLen).toInt()
            val head = ByteArray(headLen)
            val tail = ByteArray(tailLen)
            FileInputStream(file).use { fis ->
                readFully(fis, head)
                if (tailLen > 0) {
                    fis.channel.position(total - tailLen)
                    readFully(fis, tail)
                }
            }
            extractId(head, tail)
        } catch (e: Exception) {
            null
        }
    }

    private fun readFully(fis: FileInputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = fis.read(buf, off, buf.size - off)
            if (n <= 0) break
            off += n
        }
    }

    /** vivo 的 32 位十六进制 ID 优先；其次找苹果的 ContentIdentifier(UUID)。 */
    private fun extractId(head: ByteArray, tail: ByteArray): String? {
        val tailText = String(tail, Charsets.ISO_8859_1)
        VIVO_ID.find(tailText)?.let { return it.groupValues[1].lowercase() }
        val headText = String(head, Charsets.ISO_8859_1)
        VIVO_ID.find(headText)?.let { return it.groupValues[1].lowercase() }
        appleId(headText)?.let { return it }
        return appleId(tailText)
    }

    private fun appleId(text: String): String? {
        APPLE_MARKERS.forEach { marker ->
            var from = 0
            while (from < text.length) {
                val i = text.indexOf(marker, from, ignoreCase = true)
                if (i < 0) break
                val window = text.substring(i, minOf(text.length, i + 260))
                APPLE_UUID.find(window)?.let { return it.value.uppercase() }
                from = i + marker.length
            }
        }
        return null
    }
}
