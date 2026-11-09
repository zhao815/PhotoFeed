package com.example.photofeed.ui.feed

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.SeekBar
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.RecyclerView
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.example.photofeed.R
import com.example.photofeed.data.MediaItemUi
import com.example.photofeed.data.MotionDetector
import com.example.photofeed.data.MotionPhotoExtractor
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class FeedAdapter(
    private val items: MutableList<MediaItemUi>,
    private val callbacks: Callbacks
) : RecyclerView.Adapter<FeedAdapter.PageHolder>() {

    interface Callbacks {
        fun onPageShown(position: Int, item: MediaItemUi?)
        fun onFullscreenChanged(enter: Boolean)
    }

    private var currentPosition = RecyclerView.NO_POSITION

    /** 只有当前可见频道的 adapter 才允许播放，避免后台频道偷偷出声。 */
    private var channelActive = false

    fun setActive(active: Boolean) {
        channelActive = active
        if (!active) pauseAll() else notifyActivePage()
    }

    fun setCurrent(position: Int) {
        if (position == currentPosition) return
        currentPosition = position
        notifyActivePage()
    }

    private fun notifyActivePage() {
        val rv = recyclerView ?: return
        for (i in 0 until itemCount) {
            val holder = rv.findViewHolderForAdapterPosition(i) as? PageHolder ?: continue
            val item = items.getOrNull(i) ?: continue
            if (i == currentPosition && channelActive) holder.onShown(item) else holder.onHidden()
        }
    }

    fun pauseAll() {
        val rv = recyclerView ?: return
        for (i in 0 until itemCount) {
            val holder = rv.findViewHolderForAdapterPosition(i) as? PageHolder ?: continue
            holder.onHidden()
        }
    }

    /** 返回键退出横屏全屏。 */
    fun exitFullscreen() {
        val rv = recyclerView ?: return
        for (i in 0 until itemCount) {
            (rv.findViewHolderForAdapterPosition(i) as? PageHolder)?.exitFullscreen()
        }
    }

    fun refreshVisible() {
        // no-op
    }

    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        super.onAttachedToRecyclerView(recyclerView)
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        this.recyclerView = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PageHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_feed_page, parent, false)
        return PageHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: PageHolder, position: Int) {
        holder.bind(items[position], position == currentPosition)
    }

    override fun onViewAttachedToWindow(holder: PageHolder) {
        super.onViewAttachedToWindow(holder)
        val pos = holder.bindingAdapterPosition
        if (pos == RecyclerView.NO_POSITION) return
        val item = items.getOrNull(pos) ?: return
        if (currentPosition == RecyclerView.NO_POSITION) currentPosition = pos
        if (pos == currentPosition && channelActive) {
            holder.onShown(item)
            callbacks.onPageShown(pos, item)
        } else {
            holder.onHidden()
        }
    }

    override fun onViewDetachedFromWindow(holder: PageHolder) {
        super.onViewDetachedFromWindow(holder)
        holder.onHidden()
    }

    override fun onViewRecycled(holder: PageHolder) {
        super.onViewRecycled(holder)
        holder.release()
    }

    inner class PageHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val root: View = itemView.findViewById(R.id.root)
        private val image: PhotoView = itemView.findViewById(R.id.image)
        private val playerView: PlayerView = itemView.findViewById(R.id.player)
        private val pauseIcon: ImageView = itemView.findViewById(R.id.pauseIcon)
        private val errorLabel: TextView = itemView.findViewById(R.id.errorLabel)
        private val btnFullscreen: TextView = itemView.findViewById(R.id.btnFullscreen)
        private val btnBack: ImageButton = itemView.findViewById(R.id.btnBack)
        private val longPhotoHint: TextView = itemView.findViewById(R.id.longPhotoHint)

        private var player: ExoPlayer? = null
        private var boundItem: MediaItemUi? = null
        private var shown = false
        private var isManualLandscape = false
        /** 动图播放器是否已经 setMediaItem + prepare 完毕，只差 play。 */
        private var motionPrepared = false
        /** 静帧封面是否已经揭掉；翻页前会重新盖上。 */
        private var motionCoverHidden = false
        /** 极少数 ROM 不回调 onRenderedFirstFrame 时的兜底，避免一直停在静帧。 */
        private var coverFallback: Runnable? = null
        private var stillTarget: CustomTarget<Bitmap>? = null
        private var hintRunnable: Runnable? = null
        private var motionGeneration = 0

        // 视频控制栏（仅 VIDEO 类型使用）
        private val videoControls: View = itemView.findViewById(R.id.videoControls)
        private val ivPlayPause: ImageButton = itemView.findViewById(R.id.ivPlayPause)
        private val tvCur: TextView = itemView.findViewById(R.id.tvCur)
        private val seekBar: SeekBar = itemView.findViewById(R.id.seekBar)
        private val tvDur: TextView = itemView.findViewById(R.id.tvDur)
        private val tvSpeed: TextView = itemView.findViewById(R.id.tvSpeed)
        private val speedBtn: View = itemView.findViewById(R.id.speedBtn)
        private val speedChips: ViewGroup = itemView.findViewById(R.id.speedChips)

        private var controlsVisible = false
        /** 用户正在拖动进度条，期间不刷新进度，避免抖动。 */
        private var seeking = false
        /** 倍速档位索引，对应 SPEEDS；每个视频独立，bind 时回到 1.0x。 */
        private var speedIndex = 1
        private val SPEEDS = floatArrayOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)

        private val hideControlsRunnable = Runnable { hideControls() }
        private val progressRunnable = object : Runnable {
            override fun run() {
                updateProgress()
                if (controlsVisible) itemView.postDelayed(this, 500L)
            }
        }

        /** 已做旋转修正的视频真实宽高，用于按屏幕比例重排画面。 */
        private var videoW = 0
        private var videoH = 0
        private var lastRootW = 0
        private var lastRootH = 0

        /** 旋转全屏后的恢复播放任务代号，防止 holder 复用后旧任务误触发。 */
        private var resumeGeneration = 0

        init {
            btnFullscreen.setOnClickListener { enterFullscreen() }
            btnBack.setOnClickListener { exitFullscreen() }
            // 放大时给一个明确的状态提示，并告诉用户如何缩回正常大小；
            // 缩回 / 复位后提示自动消失。
            image.onZoomChanged = { zoomed ->
                hintRunnable?.let { image.removeCallbacks(it) }
                hintRunnable = null
                if (zoomed) {
                    longPhotoHint.text = itemView.context.getString(R.string.photo_zoom_hint)
                    longPhotoHint.visibility = View.VISIBLE
                } else {
                    longPhotoHint.visibility = View.GONE
                }
            }
            // 屏幕旋转、不同机型、窗口尺寸变化都重新计算，保证画面与按钮始终居中对齐。
            root.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                val w = r - l
                val h = b - t
                if (w != lastRootW || h != lastRootH) {
                    lastRootW = w
                    lastRootH = h
                    layoutVideo()
                }
            }

            // 控制栏自身消费触摸，不穿透到画面触发暂停；内部按钮各自处理。
            videoControls.setOnClickListener { /* 消费触摸，不暂停 */ }
            ivPlayPause.setOnClickListener {
                val p = player ?: return@setOnClickListener
                if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return@setOnClickListener
                hideSpeedChips()
                togglePlayPause(p)
                if (p.isPlaying) showControls(autoHide = true) else showControls(autoHide = false)
            }
            // 倍速：点一下弹出档位选择，让用户自己挑，而不是点一下加一档
            speedBtn.setOnClickListener {
                if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return@setOnClickListener
                toggleSpeedChips()
            }
            buildSpeedChips()
            seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(sb: SeekBar) {
                    seeking = true
                    videoControls.removeCallbacks(hideControlsRunnable)
                }

                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser && boundItem?.type == MediaItemUi.MediaType.VIDEO) {
                        val dur = player?.duration ?: 0L
                        if (dur > 0) tvCur.text = formatTime(dur * progress / sb.max)
                    }
                }

                override fun onStopTrackingTouch(sb: SeekBar) {
                    if (boundItem?.type == MediaItemUi.MediaType.VIDEO) {
                        val p = player
                        val dur = p?.duration ?: 0L
                        if (p != null && dur > 0) {
                            val target = dur * sb.progress / sb.max
                            p.seekTo(target)
                            Log.d("PhotoFeed", "seek released -> progress=${sb.progress}/${sb.max} targetMs=$target durMs=$dur")
                        }
                    }
                    seeking = false
                    showControls(autoHide = true)
                }
            })
        }

        fun bind(item: MediaItemUi, isCurrent: Boolean) {
            // 复用前若还停留在横屏全屏，先复位方向与顶部栏
            if (isManualLandscape) exitFullscreen()
            boundItem = item
            // 只停掉上一个源、不释放播放器：复用同一个 ExoPlayer，
            // TextureView 的 surface 不重建，翻页时画面不会突然黑一下。
            resetPlayback()
            // 新静帧解码完成前保留上一帧（发生在目标页进入屏幕之前），
            // 这样 bind 到首帧之间不会露出黑屏。
            cancelStill(preserveFrame = true)
            resetPlayerLayout()

            when (item.type) {
                MediaItemUi.MediaType.PHOTO -> {
                    playerView.visibility = View.GONE
                    image.visibility = View.VISIBLE
                    showStill(item)
                }
                MediaItemUi.MediaType.VIDEO -> {
                    image.visibility = View.GONE
                    prepareVideo(item, autoPlay = isCurrent && channelActive)
                }
                MediaItemUi.MediaType.MOTION -> {
                    // 播放器常驻可见，静帧盖在它上面当封面；绑定阶段就预准备视频，
                    // 等第一帧真正渲染出来再揭封面，翻页全程都有画面。
                    playerView.visibility = View.VISIBLE
                    image.visibility = View.VISIBLE
                    showStill(item)
                    prepareMotion(item)
                }
            }
        }

        /**
         * 停掉当前播放源但保留播放器实例与 surface。
         * 旧实现每翻一页都 release() 再 build() 一个 ExoPlayer，
         * surface 反复创建/销毁正是「每刷一张闪一下」的主要来源。
         */
        private fun resetPlayback() {
            motionGeneration++
            resumeGeneration++
            motionPrepared = false
            motionCoverHidden = false
            coverFallback?.let { itemView.removeCallbacks(it) }
            coverFallback = null
            isManualLandscape = false
            videoW = 0
            videoH = 0
            errorLabel.visibility = View.GONE
            pauseIcon.visibility = View.GONE
            btnFullscreen.visibility = View.GONE
            btnBack.visibility = View.GONE
            // 控制栏随播放源一起复位：隐藏、停止计时与进度刷新、倍速回到 1.0x
            controlsVisible = false
            seeking = false
            speedIndex = 1
            videoControls.visibility = View.GONE
            hideSpeedChips()
            itemView.removeCallbacks(progressRunnable)
            videoControls.removeCallbacks(hideControlsRunnable)
            root.setOnClickListener(null)
            image.setOnClickListener(null)
            image.setOnTouchListener(null)
            player?.let { p ->
                p.playWhenReady = false
                p.pause()
                p.volume = 0f
                p.stop()
                p.clearMediaItems()
            }
        }

        /** 取消上一次 Glide 请求、清掉提示；preserveFrame=true 时保留画面避免黑帧。 */
        private fun cancelStill(preserveFrame: Boolean = false) {
            stillTarget?.let {
                try {
                    Glide.with(image).clear(it)
                } catch (_: Exception) {
                }
            }
            stillTarget = null
            hintRunnable?.let { image.removeCallbacks(it) }
            hintRunnable = null
            longPhotoHint.visibility = View.GONE
            if (preserveFrame) image.resetInteraction() else image.clearPhoto()
        }

        private fun showLongHint() {
            hintRunnable?.let { image.removeCallbacks(it) }
            longPhotoHint.visibility = View.VISIBLE
            val r = Runnable { longPhotoHint.visibility = View.GONE }
            hintRunnable = r
            image.postDelayed(r, 2600L)
        }

        /**
         * 静帧统一走完整比例显示：普通照片/动图居中留黑边，长截图按短边铺满并允许拖动。
         * 不再用 centerCrop，任何比例都不会被裁掉。
         */
        private fun showStill(item: MediaItemUi) {
            val isLong = item.isLongImage
            if (isLong) showLongHint()
            val target = object : CustomTarget<Bitmap>() {
                override fun onResourceReady(
                    resource: Bitmap,
                    transition: Transition<in Bitmap>?
                ) {
                    if (boundItem !== item) return
                    image.setImageBitmap(resource)
                    image.onBitmapReady(resource.width, resource.height, isLong)
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    // 旧请求 / 占位图被替换时故意不清画面，否则切换瞬间会闪一帧黑。
                }

                override fun onLoadFailed(errorDrawable: Drawable?) {
                    // 动图保留封面（视频还在下面），普通照片才清掉，避免显示别人的图。
                    if (boundItem === item && item.type == MediaItemUi.MediaType.PHOTO) {
                        image.clearPhoto()
                    }
                }
            }
            stillTarget = target
            val builder = Glide.with(image)
                .asBitmap()
                .load(item.uri)
                .dontAnimate()
                .dontTransform()
            if (isLong) {
                // 长图按短边铺满解码，保证拖动时仍然清晰；RGB_565 控制内存峰值。
                val sw = root.width.coerceAtLeast(720)
                val sh = root.height.coerceAtLeast(720)
                val ow: Int
                val oh: Int
                if (item.height >= item.width) {
                    ow = sw
                    oh = (sw.toLong() * item.height / item.width).toInt().coerceAtMost(14000)
                } else {
                    oh = sh
                    ow = (sh.toLong() * item.width / item.height).toInt().coerceAtMost(14000)
                }
                builder.apply(RequestOptions().format(DecodeFormat.PREFER_RGB_565))
                    .override(ow, oh)
            } else {
                builder.override(
                    root.width.coerceAtLeast(720),
                    root.height.coerceAtLeast(720)
                )
            }
            builder.into(target)
        }

        /** PlayerView 回到整屏 FIT，供照片 / 动图 / 新视频绑定前使用。 */
        private fun resetPlayerLayout() {
            val lp = playerView.layoutParams as FrameLayout.LayoutParams
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.CENTER
            lp.topMargin = 0
            playerView.layoutParams = lp
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        }

        /**
         * 按视频真实宽高比算出画面尺寸，让 PlayerView 本身居中（任意屏幕比例都上下居中），
         * 并把「全屏观看」贴在画面下边缘；只有横版视频才显示该按钮。
         */
        private fun layoutVideo() {
            if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return
            val rw = root.width
            val rh = root.height
            if (rw <= 0 || rh <= 0) return
            val lp = playerView.layoutParams as FrameLayout.LayoutParams

            if (isManualLandscape) {
                // 横屏全屏：MainActivity 已经隐藏系统栏并让 decor 铺满整个物理屏幕，
                // 所以 root 的中心就是屏幕中心。这里把 PlayerView 本身按视频真实比例
                // 算成一个精确矩形并用 gravity=CENTER 摆到 root 正中，画面就以「整个屏幕」
                // 为基准上下左右居中；FIT 再保证内部画面完整显示、不放大裁切，黑边对称。
                lp.gravity = Gravity.CENTER
                lp.topMargin = 0
                if (videoW > 0 && videoH > 0) {
                    val ar = videoW.toFloat() / videoH.toFloat()
                    if (rw.toFloat() / rh.toFloat() > ar) {
                        lp.height = rh
                        lp.width = (rh * ar).roundToInt().coerceAtMost(rw)
                    } else {
                        lp.width = rw
                        lp.height = (rw / ar).roundToInt().coerceAtMost(rh)
                    }
                } else {
                    // 视频尺寸还没回调时先铺满，尺寸一到会再走一次 layoutVideo()
                    lp.width = FrameLayout.LayoutParams.MATCH_PARENT
                    lp.height = FrameLayout.LayoutParams.MATCH_PARENT
                }
                playerView.layoutParams = lp
                playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                btnFullscreen.visibility = View.GONE
                return
            }

            if (videoW <= 0 || videoH <= 0) {
                resetPlayerLayout()
                btnFullscreen.visibility = View.GONE
                return
            }

            val ar = videoW.toFloat() / videoH.toFloat()
            val dispW: Int
            val dispH: Int
            if (rw.toFloat() / rh.toFloat() > ar) {
                dispH = rh
                dispW = (rh * ar).roundToInt().coerceAtMost(rw)
            } else {
                dispW = rw
                dispH = (rw / ar).roundToInt().coerceAtMost(rh)
            }
            lp.width = dispW
            lp.height = dispH
            lp.gravity = Gravity.CENTER
            lp.topMargin = 0
            playerView.layoutParams = lp
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT

            if (ar > 1f) {
                val density = itemView.resources.displayMetrics.density
                val gap = (14 * density).roundToInt()
                val btnH = (38 * density).roundToInt()
                val flp = btnFullscreen.layoutParams as FrameLayout.LayoutParams
                flp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // 画面下边缘 = (rh + dispH) / 2
                flp.topMargin = ((rh + dispH) / 2 + gap)
                    .coerceAtMost(rh - btnH - gap)
                btnFullscreen.layoutParams = flp
                btnFullscreen.visibility = View.VISIBLE
            } else {
                // 竖版视频不需要全屏按钮
                btnFullscreen.visibility = View.GONE
            }
        }

        private fun prepareVideo(item: MediaItemUi, autoPlay: Boolean) {
            val p = player ?: buildPlayer()
            p.repeatMode = Player.REPEAT_MODE_ONE
            // 默认有声，界面上不再提供静音按钮
            p.volume = 1f
            p.playWhenReady = false
            p.setPlaybackSpeed(SPEEDS[speedIndex])
            p.setMediaItem(MediaItem.fromUri(item.uri))
            p.prepare()
            playerView.player = p
            playerView.keepScreenOn = true
            playerView.visibility = View.VISIBLE
            image.visibility = View.GONE
            layoutVideo()

            tvSpeed.text = formatSpeed(SPEEDS[speedIndex])
            refreshSpeedChips()
            tvCur.text = itemView.context.getString(R.string.zero_time)
            seekBar.progress = 0
            root.setOnClickListener {
                if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return@setOnClickListener
                hideSpeedChips()
                togglePlayPause(p)
                if (p.isPlaying) showControls(autoHide = true) else showControls(autoHide = false)
            }
            showControls(autoHide = true)
            if (autoPlay && shown && channelActive) p.playWhenReady = true
        }

        /**
         * 绑定阶段就把动图视频准备好，而不是等翻到这一页才开始解析 + prepare。
         *
         * 闪屏的根因是「静帧先隐藏、视频首帧还没渲染」的那一瞬黑屏。现在：
         *  1. 静帧作为封面一直盖在播放器上面；
         *  2. 播放器复用同一个 ExoPlayer，surface 不重建；
         *  3. 只有 onRenderedFirstFrame（首帧真的画出来了）才揭封面。
         * 因此翻页全过程都看得到画面，不会出现「刷一张闪一下」。
         *
         * 苹果 Live Photo 的 MP4 自带原声，默认有声（用户可用音量键静音）。
         * 内嵌 MP4 的解析 / 抽取放在后台线程，避免滑动时卡顿。
         */
        private fun prepareMotion(item: MediaItemUi) {
            val gen = ++motionGeneration
            val appContext = itemView.context.applicationContext
            MOTION_EXECUTOR.execute {
                val videoUri = resolveMotionVideo(appContext, item)
                itemView.post {
                    if (gen != motionGeneration || boundItem !== item) return@post
                    if (videoUri == null) {
                        showMotionUnavailable()
                        return@post
                    }
                    val p = player ?: buildPlayer()
                    p.repeatMode = Player.REPEAT_MODE_ONE
                    p.volume = 1f
                    p.playWhenReady = false
                    p.setMediaItem(MediaItem.fromUri(videoUri))
                    p.prepare()
                    playerView.player = p
                    resetPlayerLayout()
                    playerView.visibility = View.VISIBLE
                    motionPrepared = true
                    scheduleCoverFallback(item)
                    // 绑定时页面可能已经是当前页了（例如刚进入动图频道），直接开播。
                    if (shown && channelActive) playMotion()
                }
            }
        }

        /** 首帧已经画在 surface 上，这时撤掉封面不会露出任何黑帧。 */
        private fun revealMotionFrame() {
            if (motionCoverHidden || boundItem?.type != MediaItemUi.MediaType.MOTION) return
            motionCoverHidden = true
            coverFallback?.let { itemView.removeCallbacks(it) }
            coverFallback = null
            image.visibility = View.GONE
            Log.i("PhotoFeed", "motion cover revealed after first frame")
        }

        /** 极少数 ROM 不回调首帧时的兜底，避免动图永远停在静帧。 */
        private fun scheduleCoverFallback(item: MediaItemUi) {
            coverFallback?.let { itemView.removeCallbacks(it) }
            val r = Runnable {
                // 只在播放器已就绪时兜底，BUFFERING 阶段宁可继续盖着封面也不露黑。
                if (boundItem === item && !motionCoverHidden &&
                    player?.playbackState == Player.STATE_READY
                ) {
                    revealMotionFrame()
                }
            }
            coverFallback = r
            itemView.postDelayed(r, 900L)
        }

        private fun showMotionUnavailable() {
            motionPrepared = false
            errorLabel.text = itemView.context.getString(R.string.motion_unavailable)
            errorLabel.visibility = View.VISIBLE
            errorLabel.postDelayed({ errorLabel.visibility = View.GONE }, 1800)
        }

        private fun playMotion() {
            if (!motionPrepared) return
            val p = player ?: return
            p.volume = 1f
            p.playWhenReady = true
            if (p.playbackState != Player.STATE_IDLE && !p.isPlaying) p.play()
        }

        /** 配对视频直接用；内嵌视频先抽到私有缓存；位置未知则现场再解析一次。 */
        private fun resolveMotionVideo(context: Context, item: MediaItemUi): Uri? {
            item.motionVideoUri?.let { return it }
            if (item.hasEmbeddedMotionVideo) {
                MotionPhotoExtractor.extract(context, item)?.let { return it }
            }
            val info = MotionDetector.detect(context.contentResolver, item.uri) ?: return null
            val complete = item.copy(
                motionVideoOffset = info.videoOffset,
                motionVideoLength = info.videoLength
            )
            return MotionPhotoExtractor.extract(context, complete)
        }

        private fun togglePlayPause(p: ExoPlayer) {
            if (p.isPlaying) {
                p.pause()
                pauseIcon.visibility = View.VISIBLE
                ivPlayPause.setImageResource(android.R.drawable.ic_media_play)
                ivPlayPause.contentDescription = itemView.context.getString(R.string.play)
            } else {
                p.play()
                pauseIcon.visibility = View.GONE
                ivPlayPause.setImageResource(android.R.drawable.ic_media_pause)
                ivPlayPause.contentDescription = itemView.context.getString(R.string.pause)
            }
        }

        private fun enterFullscreen() {
            val activity = itemView.context as? Activity ?: return
            if (isManualLandscape) return
            isManualLandscape = true
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            btnFullscreen.visibility = View.GONE
            btnBack.visibility = View.VISIBLE
            callbacks.onFullscreenChanged(true)
            layoutVideo()
            keepPlayingAcrossRotation()
            showControls(autoHide = true)
        }

        fun exitFullscreen() {
            if (!isManualLandscape) return
            val activity = itemView.context as? Activity ?: return
            isManualLandscape = false
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            btnBack.visibility = View.GONE
            callbacks.onFullscreenChanged(false)
            layoutVideo()
            keepPlayingAcrossRotation()
            showControls(autoHide = true)
        }

        /**
         * 旋转横屏时 RecyclerView 的 item view 会短暂 detach，onHidden() 会暂停播放器并把音量归零；
         * 但 MainActivity 声明了 configChanges，不会重建、也就不会再走一遍 onShown()，
         * 结果就是「一点全屏，画面停在暂停帧且没声音」。这里在旋转稳定后显式恢复。
         */
        private fun keepPlayingAcrossRotation() {
            val gen = ++resumeGeneration
            itemView.post { resumeIfCurrentVideo(gen) }
            itemView.postDelayed({ resumeIfCurrentVideo(gen) }, 600L)
        }

        private fun resumeIfCurrentVideo(gen: Int) {
            if (gen != resumeGeneration) return
            if (!shown || !channelActive || boundItem?.type != MediaItemUi.MediaType.VIDEO) return
            val p = player ?: return
            if (p.playbackState == Player.STATE_IDLE) return
            p.volume = 1f
            p.playWhenReady = true
            if (!p.isPlaying) p.play()
            pauseIcon.visibility = View.GONE
        }

        private fun buildPlayer(): ExoPlayer {
            // 本地文件读取很快，不需要默认 50 秒的缓冲；4K 素材下大缓冲会直接把堆撑爆。
            // 每个播放器最多缓冲 10 秒，多个预创建页面并存时内存可控。
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(2000, 10000, 500, 1000)
                .build()
            val p = ExoPlayer.Builder(itemView.context)
                .setLoadControl(loadControl)
                .build()
            p.addListener(object : Player.Listener {
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    errorLabel.visibility = View.VISIBLE
                    errorLabel.text = itemView.context.getString(R.string.play_failed)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (boundItem?.type == MediaItemUi.MediaType.VIDEO) {
                        pauseIcon.visibility =
                            if (!isPlaying && p.playbackState == Player.STATE_READY) View.VISIBLE
                            else View.GONE
                        val res = if (isPlaying) android.R.drawable.ic_media_pause
                        else android.R.drawable.ic_media_play
                        ivPlayPause.setImageResource(res)
                        ivPlayPause.contentDescription = itemView.context.getString(
                            if (isPlaying) R.string.pause else R.string.play
                        )
                    }
                }

                override fun onRenderedFirstFrame() {
                    // 视频第一帧真的画到 surface 上了，此刻才撤掉静帧封面。
                    if (boundItem?.type == MediaItemUi.MediaType.MOTION) {
                        revealMotionFrame()
                    }
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return
                    var w = videoSize.width
                    var h = videoSize.height
                    if (videoSize.unappliedRotationDegrees == 90 ||
                        videoSize.unappliedRotationDegrees == 270
                    ) {
                        val tmp = w
                        w = h
                        h = tmp
                    }
                    if (w <= 0 || h <= 0) return
                    videoW = w
                    videoH = h
                    layoutVideo()
                }
            })
            player = p
            return p
        }

        fun onShown(item: MediaItemUi) {
            // 双重保险：非当前频道绝不播放，杜绝后台出声。
            if (!channelActive) {
                onHidden()
                return
            }
            shown = true
            when (item.type) {
                MediaItemUi.MediaType.VIDEO -> {
                    val p = player ?: return
                    p.volume = 1f
                    p.playWhenReady = true
                    pauseIcon.visibility = View.GONE
                    showControls(autoHide = true)
                }
                MediaItemUi.MediaType.MOTION -> playMotion()
                else -> Unit
            }
        }

        fun onHidden() {
            shown = false
            pauseIcon.visibility = View.GONE
            if (isManualLandscape) exitFullscreen()
            player?.let {
                it.playWhenReady = false
                it.pause()
                it.volume = 0f
            }
            hideControls()
            // 不再改动 playerView / image 的可见性：切频道或翻页时
            // 反复显隐播放器会让 surface 重建并露出黑帧。
        }

        fun release() {
            releasePlayer()
            cancelStill()
            hideControls()
        }

        // 视频控制栏显隐与进度刷新（参考抖音 / B 站播放器：进度条 + 时间 + 倍速）
        private fun showControls(autoHide: Boolean) {
            if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return
            controlsVisible = true
            videoControls.visibility = View.VISIBLE
            itemView.removeCallbacks(progressRunnable)
            itemView.post(progressRunnable)
            videoControls.removeCallbacks(hideControlsRunnable)
            if (autoHide) videoControls.postDelayed(hideControlsRunnable, 3000L)
        }

        private fun hideControls() {
            controlsVisible = false
            videoControls.visibility = View.GONE
            itemView.removeCallbacks(progressRunnable)
            videoControls.removeCallbacks(hideControlsRunnable)
            hideSpeedChips()
        }

        private fun updateProgress() {
            val p = player ?: return
            if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return
            val dur = p.duration
            if (dur <= 0) return
            seekBar.max = 1000
            if (!seeking) seekBar.progress = (p.currentPosition * 1000 / dur).toInt()
            tvCur.text = formatTime(p.currentPosition)
            tvDur.text = formatTime(dur)
        }

        private fun formatTime(ms: Long): String {
            if (ms < 0) return "00:00"
            val totalSec = ms / 1000
            val h = totalSec / 3600
            val m = (totalSec % 3600) / 60
            val s = totalSec % 60
            return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
            else String.format(Locale.US, "%02d:%02d", m, s)
        }

        private fun formatSpeed(sp: Float): String = String.format(Locale.US, "%.1fx", sp)

        /** 给五个档位芯片绑定点击：点哪个就切哪个倍速，并高亮当前选中的。 */
        private fun buildSpeedChips() {
            for (i in 0 until speedChips.childCount) {
                val chip = speedChips.getChildAt(i) as? TextView ?: continue
                chip.setOnClickListener {
                    if (boundItem?.type != MediaItemUi.MediaType.VIDEO) return@setOnClickListener
                    val tag = chip.tag?.toString() ?: return@setOnClickListener
                    val sp = tag.toFloatOrNull() ?: return@setOnClickListener
                    val idx = SPEEDS.indexOfFirst { kotlin.math.abs(it - sp) < 0.001f }
                    if (idx < 0) return@setOnClickListener
                    speedIndex = idx
                    player?.setPlaybackSpeed(sp)
                    Log.d("PhotoFeed", "speed selected -> index=$idx speed=${sp}x")
                    refreshSpeedChips()
                    hideSpeedChips()
                    showControls(autoHide = true)
                }
            }
        }

        /** 点速度按钮：没展开就展开档位条，展开了就收起。 */
        private fun toggleSpeedChips() {
            if (speedChips.visibility == View.VISIBLE) {
                hideSpeedChips()
                showControls(autoHide = true)
            } else {
                refreshSpeedChips()
                speedChips.visibility = View.VISIBLE
                // 选档位期间不让控制栏自动隐藏
                videoControls.removeCallbacks(hideControlsRunnable)
                showControls(autoHide = false)
            }
        }

        /** 同步「按钮上的文字」与「五个档位里哪个高亮」。 */
        private fun refreshSpeedChips() {
            val cur = SPEEDS[speedIndex]
            tvSpeed.text = formatSpeed(cur)
            for (i in 0 until speedChips.childCount) {
                val chip = speedChips.getChildAt(i) as? TextView ?: continue
                val tag = chip.tag?.toString()?.toFloatOrNull() ?: continue
                val sel = kotlin.math.abs(tag - cur) < 0.001f
                chip.background = ContextCompat.getDrawable(
                    itemView.context,
                    if (sel) R.drawable.bg_speed_chip_sel else R.drawable.bg_speed_chip
                )
                chip.setTextColor(if (sel) Color.BLACK else Color.WHITE)
            }
        }

        private fun hideSpeedChips() {
            speedChips.visibility = View.GONE
        }

        private fun releasePlayer() {
            motionGeneration++
            resumeGeneration++
            motionPrepared = false
            motionCoverHidden = false
            coverFallback?.let { itemView.removeCallbacks(it) }
            coverFallback = null
            root.setOnClickListener(null)
            image.setOnTouchListener(null)
            pauseIcon.visibility = View.GONE
            if (isManualLandscape) exitFullscreen()
            btnFullscreen.visibility = View.GONE
            btnBack.visibility = View.GONE
            errorLabel.visibility = View.GONE
            resetPlayerLayout()
            playerView.player = null
            player?.release()
            player = null
        }
    }

    companion object {
        // 两条解析线程：当前页与下一页可以同时预准备，翻到时首帧往往已经就绪。
        private val MOTION_EXECUTOR = Executors.newFixedThreadPool(2)
    }
}
