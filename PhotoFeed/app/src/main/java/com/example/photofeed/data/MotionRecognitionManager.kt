package com.example.photofeed.data

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * 「重新识别」的进程级后台任务。
 *
 * 旧实现挂在 SettingsActivity 的 lifecycleScope 上：点完识别一按返回，
 * Activity 销毁、协程被取消，识别半途而废，用户只能干等 in 设置页。
 * 现在任务跑在进程作用域里：退出设置页、切到视频频道继续刷都不受影响，
 * 结果直接写进 SharedPreferences，完成后通过 [lastResult] 通知动图频道刷新。
 *
 * 识别本身仍是增量的：按 mediaKey 跳过已扫过的文件，负结果也缓存，
 * 所以后台跑也不会跟前台播放抢 IO / CPU。
 */
object MotionRecognitionManager {

    data class Result(
        /** 每次完成递增，供 UI 判断「这条结果我处理过没有」。 */
        val version: Long,
        val total: Int,
        val scannedNew: Int,
        val skipped: Int,
        val elapsedMs: Long
    )

    /** true = 正在后台识别；设置页按钮跟着它变灰。 */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    /** 一次性事件（不重放）：只用来在设置页仍在前台时弹结果提示。 */
    private val _finished = MutableSharedFlow<Result>(extraBufferCapacity = 1)
    val finished: SharedFlow<Result> = _finished

    /** 最近一次完成的结果（可重放）：MainActivity 在 onResume 也能补上刷新。 */
    private val _lastResult = MutableStateFlow<Result?>(null)
    val lastResult: StateFlow<Result?> = _lastResult

    private val version = AtomicLong(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** 已在跑就忽略重复点击；返回是否真的启动了新任务。 */
    @Synchronized
    fun start(context: Context): Boolean {
        if (job?.isActive == true) return false
        val app = context.applicationContext
        _running.value = true
        job = scope.launch {
            val result = try {
                recognize(app)
            } catch (e: Exception) {
                Log.w(TAG, "background recognize failed", e)
                Result(version.incrementAndGet(), 0, 0, 0, 0L)
            } finally {
                _running.value = false
            }
            Log.i(
                TAG,
                "background recognize done: total=${result.total} " +
                    "new=${result.scannedNew} skipped=${result.skipped} " +
                    "in ${result.elapsedMs}ms"
            )
            _lastResult.value = result
            _finished.tryEmit(result)
        }
        return true
    }

    private fun recognize(app: Context): Result {
        val started = System.currentTimeMillis()
        val cr = app.contentResolver
        val photos = MediaQueryHelper.queryAllImages(cr)
        val videos = MediaQueryHelper.queryVideos(cr)

        val known = MotionPrefs.scannedKeys(app).toMutableSet()
        val detected = MotionPrefs.detectedInfo(app).toMutableMap()
        var scannedNew = 0
        photos.forEach { img ->
            if (known.contains(img.mediaKey)) return@forEach
            scannedNew++
            known.add(img.mediaKey)
            val id = img.mediaKey.substringBefore(':').toLongOrNull()
            val info = MotionDetector.detect(cr, img.uri)
            if (info != null && id != null) {
                detected[id] = info
            } else if (id != null) {
                // 内容被换过（指纹变了）且不再是动态照片，清掉旧命中
                detected.remove(id)
            }
        }

        // 缓存只保留媒体库里还存在的条目，避免删照片后记录无限增长。
        val liveKeys = photos.map { it.mediaKey }.toHashSet()
        val liveIds = liveKeys
            .mapNotNull { it.substringBefore(':').toLongOrNull() }
            .toHashSet()
        val cleanedDetected: Map<Long, MotionDetector.MotionInfo?> =
            detected.filterKeys { it in liveIds }
        val cleanedScanned: Set<String> = known.intersect(liveKeys)
        MotionPrefs.saveDetected(app, cleanedDetected)
        MotionPrefs.saveScanned(app, cleanedScanned)

        val pairs = MediaRepository(app).findLivePhotoPairs(photos, videos)
        // 苹果 Live Photo / vivo 动态照片按内容 ID 配对，不需要用户预先扫描。
        val linked = LivePhotoLinker.link(app, photos, videos)
        val keys = HashSet<String>()
        photos.forEach { img ->
            val id = img.mediaKey.substringBefore(':').toLongOrNull()
            if (id != null && cleanedDetected.containsKey(id)) keys.add(img.mediaKey)
        }
        pairs.forEach { keys.add(it.mediaKey) }
        linked.forEach { keys.add(it.mediaKey) }

        return Result(
            version = version.incrementAndGet(),
            total = keys.size,
            scannedNew = scannedNew,
            skipped = photos.size - scannedNew,
            elapsedMs = System.currentTimeMillis() - started
        )
    }

    private const val TAG = "PhotoFeed"
}
