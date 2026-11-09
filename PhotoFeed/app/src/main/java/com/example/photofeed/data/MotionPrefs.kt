package com.example.photofeed.data

import android.content.Context

/**
 * 保存手动识别出的动态照片（mediaKey 中的 _ID -> 内嵌视频位置）以及
 * 最近浏览窗口，避免为了这两项新增 Room 表与迁移。
 */
object MotionPrefs {
    private const val PREFS = "photofeed_motion"
    private const val KEY_IDS = "motion_ids"
    private const val KEY_RECENT_PREFIX = "recent_"

    /** 已经扫过文件头的媒体指纹（含「普通照片」的负结果），增量识别靠它跳过。 */
    private const val KEY_SCANNED = "motion_scanned"

    /** mediaKey -> Live Photo 内容 ID；值为 NO_LINK_ID 表示扫过但没有内容 ID。 */
    private const val KEY_LINKED_IDS = "motion_linked_ids"

    /** 缓存里表示「扫过、不是 Live Photo」的占位值。 */
    const val NO_LINK_ID = "-"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun saveDetected(context: Context, info: Map<Long, MotionDetector.MotionInfo?>) {
        val encoded = info.entries.map { (id, m) ->
            if (m == null) "$id" else "$id:${m.videoOffset}:${m.videoLength}"
        }.toSet()
        prefs(context).edit().putStringSet(KEY_IDS, encoded).apply()
    }

    /** 旧格式（只存 id）也会被兼容：value 为 null，播放时再现场解析一次。 */
    fun detectedInfo(context: Context): Map<Long, MotionDetector.MotionInfo?> {
        val raw = prefs(context).getStringSet(KEY_IDS, emptySet()) ?: return emptyMap()
        return raw.mapNotNull { entry ->
            val parts = entry.split(":")
            val id = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            val info = if (parts.size >= 3) {
                val off = parts[1].toLongOrNull()
                val len = parts[2].toLongOrNull()
                if (off != null && len != null && len > 0) MotionDetector.MotionInfo(off, len)
                else null
            } else null
            id to info
        }.toMap()
    }

    fun detectedIds(context: Context): Set<Long> = detectedInfo(context).keys

    // ---- 增量识别缓存 ----------------------------------------------------

    fun scannedKeys(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SCANNED, emptySet())?.toHashSet() ?: emptySet()

    fun saveScanned(context: Context, keys: Set<String>) {
        prefs(context).edit().putStringSet(KEY_SCANNED, keys).apply()
    }

    fun linkedIds(context: Context): Map<String, String> {
        val raw = prefs(context).getStringSet(KEY_LINKED_IDS, emptySet()) ?: return emptyMap()
        return raw.mapNotNull { entry ->
            val i = entry.indexOf('\t')
            if (i <= 0) null else entry.substring(0, i) to entry.substring(i + 1)
        }.toMap()
    }

    fun saveLinkedIds(context: Context, ids: Map<String, String>) {
        val encoded = ids.entries.map { "${it.key}\t${it.value}" }.toSet()
        prefs(context).edit().putStringSet(KEY_LINKED_IDS, encoded).apply()
    }

    fun addRecent(context: Context, channel: String, mediaKey: String) {
        val p = prefs(context)
        val key = KEY_RECENT_PREFIX + channel
        val current = p.getString(key, "")?.split("\n")?.filter { it.isNotBlank() }
            ?.toMutableList() ?: mutableListOf()
        current.remove(mediaKey)
        current.add(mediaKey)
        while (current.size > 300) current.removeAt(0)
        p.edit().putString(key, current.joinToString("\n")).apply()
    }

    fun recentKeys(context: Context, channel: String): List<String> {
        val raw = prefs(context).getString(KEY_RECENT_PREFIX + channel, "") ?: ""
        return raw.split("\n").filter { it.isNotBlank() }
    }
}
