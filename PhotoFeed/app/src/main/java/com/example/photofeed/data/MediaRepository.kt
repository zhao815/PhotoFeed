package com.example.photofeed.data

import android.content.ContentResolver
import android.content.Context
import com.example.photofeed.db.AppDatabase
import com.example.photofeed.db.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

class MediaRepository(private val context: Context) {

    private val sessionDao = AppDatabase.get(context).sessionDao()
    private val shuffleEngine = ShuffleEngine()

    suspend fun loadChannel(channel: String): List<MediaItemUi> = withContext(Dispatchers.IO) {
        val cr = context.contentResolver
        val items = when (channel) {
            CHANNEL_PHOTO -> MediaQueryHelper.queryPhotos(cr)
                .filter { it.type != MediaItemUi.MediaType.MOTION }
            CHANNEL_VIDEO -> MediaQueryHelper.queryVideos(cr)
            CHANNEL_MOTION -> loadMotionChannel(cr)
            else -> emptyList()
        }
        applyShuffle(channel, items)
    }

    /**
     * 动图频道的四个来源：
     * 1) ROM 的 special_format 列直接标出来的动态照片；
     * 2) 按文件内容 ID 配对的苹果 Live Photo / vivo 动态照片（文件名不必相同，
     *    视频半体即使在隐藏回收站也能找回，且视频自带原声）；
     * 3) 设置页手动扫描 XMP 识别出来、带内嵌视频位置的动态照片；
     * 4) 图片 + 同名短视频的兜底配对。
     */
    private fun loadMotionChannel(cr: ContentResolver): List<MediaItemUi> {
        val direct = MediaQueryHelper.queryMotion(cr)
            .map { it.copy(type = MediaItemUi.MediaType.MOTION) }

        val allImages = MediaQueryHelper.queryAllImages(cr)
        val allVideos = MediaQueryHelper.queryVideos(cr)
        val linked = LivePhotoLinker.scan(context, allImages, allVideos)

        val detected = MotionPrefs.detectedInfo(context)
        val embedded = allImages.mapNotNull { img ->
            val id = img.mediaKey.substringBefore(':').toLongOrNull() ?: return@mapNotNull null
            if (!detected.containsKey(id)) return@mapNotNull null
            val info = detected[id]
            img.copy(
                type = MediaItemUi.MediaType.MOTION,
                motionVideoOffset = info?.videoOffset,
                motionVideoLength = info?.videoLength
            )
        }

        val paired = findLivePhotoPairs(allImages, allVideos)
        // 同一张图可能同时命中多个来源，优先保留「已经拿到可播放视频」的那份，
        // 否则会出现动图频道里明明有条目却播不动。
        return (direct + linked.paired + linked.orphans + embedded + paired)
            .groupBy { it.mediaKey }
            .map { (_, group) ->
                group.maxByOrNull { item ->
                    when {
                        item.motionVideoUri != null -> 3
                        item.hasEmbeddedMotionVideo -> 2
                        else -> 1
                    }
                }!!
            }
            .sortedByDescending { it.dateTaken }
    }

    /** 图片与同名短视频（<=8s）配对，视为 Live Photo。 */
    fun findLivePhotoPairs(
        images: List<MediaItemUi>,
        videos: List<MediaItemUi>
    ): List<MediaItemUi> {
        val videoByStem = HashMap<String, MediaItemUi>()
        videos.forEach { v ->
            val stem = stem(v.fileName) ?: return@forEach
            val duration = v.durationMs ?: 0L
            if (duration == 0L || duration <= 8000L) videoByStem[stem] = v
        }
        if (videoByStem.isEmpty()) return emptyList()
        return images.mapNotNull { img ->
            val stem = stem(img.fileName) ?: return@mapNotNull null
            val v = videoByStem[stem] ?: return@mapNotNull null
            img.copy(type = MediaItemUi.MediaType.MOTION, motionVideoUri = v.uri)
        }
    }

    private fun stem(name: String?): String? =
        name?.substringBeforeLast('.')?.lowercase()?.takeIf { it.length >= 4 }

    private suspend fun applyShuffle(
        channel: String,
        items: List<MediaItemUi>
    ): List<MediaItemUi> {
        if (items.isEmpty()) return emptyList()
        val existing = sessionDao.getSession(channel)
        val seed = existing?.shuffleSeed ?: Random.nextLong()
        val recent = MotionPrefs.recentKeys(context, channel)
        val shuffled = shuffleEngine.shuffle(items, seed, recent)
        val resumeKey = existing?.lastMediaKey
        val startIndex = resumeKey
            ?.let { key -> shuffled.indexOfFirst { it.mediaKey == key } }
            ?.takeIf { it > 0 } ?: 0
        val reordered = if (startIndex > 0) {
            shuffled.subList(startIndex, shuffled.size) + shuffled.subList(0, startIndex)
        } else {
            shuffled
        }
        sessionDao.upsert(
            SessionEntity(
                channel = channel,
                shuffleSeed = seed,
                lastIndex = 0,
                lastMediaKey = reordered.firstOrNull()?.mediaKey
            )
        )
        return reordered
    }

    suspend fun saveProgress(channel: String, index: Int, mediaKey: String?) =
        withContext(Dispatchers.IO) {
            if (mediaKey != null) MotionPrefs.addRecent(context, channel, mediaKey)
            val existing = sessionDao.getSession(channel) ?: return@withContext
            sessionDao.upsert(
                existing.copy(lastIndex = index, lastMediaKey = mediaKey)
            )
        }

    /**
     * 无限刷：一轮走完后，用**新种子**把同一批媒体重新洗牌生成下一轮。
     * · 一轮内部天然不重复（洗牌是对整个集合的重排）；
     * · 不套 recent 窗口，否则刷过 300 张之后每轮都会把最近看过的排到队尾，
     *   变成「前 200 张反复出现」，随机感会越来越差；
     * · avoidKey 是上一轮最后一张，若新轮第一张刚好相同就与第 2 张交换，
     *   避免轮次交界处连着看到同一张。
     */
    suspend fun nextRound(
        source: List<MediaItemUi>,
        avoidKey: String?
    ): List<MediaItemUi> = withContext(Dispatchers.Default) {
        if (source.isEmpty()) return@withContext emptyList()
        val next = shuffleEngine.shuffle(source, Random.nextLong(), emptyList()).toMutableList()
        if (avoidKey != null && next.size > 1 && next.first().mediaKey == avoidKey) {
            val tmp = next[0]
            next[0] = next[1]
            next[1] = tmp
        }
        next
    }

    suspend fun resetShuffle(channel: String) = withContext(Dispatchers.IO) {
        sessionDao.upsert(
            SessionEntity(
                channel = channel,
                shuffleSeed = Random.nextLong(),
                lastIndex = 0,
                lastMediaKey = null
            )
        )
    }

    companion object {
        const val CHANNEL_PHOTO = "photo"
        const val CHANNEL_VIDEO = "video"
        const val CHANNEL_MOTION = "motion"
    }
}
