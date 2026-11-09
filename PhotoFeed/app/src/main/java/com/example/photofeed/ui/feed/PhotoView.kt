package com.example.photofeed.ui.feed

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 相册里的图片视图：
 * · 普通照片 / 动图静帧：按原始比例完整显示，居中留黑边，绝不裁切；
 * · 双指捏合放大，缩回去会自动吸附回「正常大小」（原始 FIT / 长图 FILL），
 *   放大后可单指拖动查看，双击也能一键复位；
 * · 超长截图 / 超宽全景：短边铺满屏幕、长边可拖动查看，双击在
 *   「铺满拖动」与「整张适应屏幕」之间切换；
 * · 未放大的普通照片完全不接管手势，上下翻页与左右切频道照常；
 *   放大态 / 长图拖动态才暂时禁止父级拦截，避免放大查看时误翻页。
 */
class PhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    private val drawMatrix = Matrix()
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val gestureDetector: GestureDetector
    private val scaleDetector: ScaleGestureDetector

    private var drawableW = 0
    private var drawableH = 0
    private var longMode = false
    private var fitWhole = false

    /** 基准比例：普通图是 FIT，长图默认是 FILL；用户缩放只是在它之上叠加倍数。 */
    private var baseScale = 1f
    private var userScale = 1f
    private var scale = 1f
    private var transX = 0f
    private var transY = 0f

    private var startX = 0f
    private var startY = 0f
    private var startTransX = 0f
    private var startTransY = 0f
    private var dragging = false
    private var pinching = false

    /** 是否已经放大；放大态禁止父级拦截手势。 */
    private val zoomed: Boolean get() = userScale > 1.02f

    /** 缩放状态变化回调，供页面显示「已放大 / 如何复位」提示。 */
    var onZoomChanged: ((Boolean) -> Unit)? = null

    private var lastNotifiedZoom = false

    private fun notifyZoom() {
        val z = zoomed
        if (z == lastNotifiedZoom) return
        lastNotifiedZoom = z
        onZoomChanged?.invoke(z)
    }

    init {
        scaleType = ScaleType.MATRIX
        gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (pinching) return false
                if (zoomed) {
                    // 放大状态下双击：直接回到原始大小
                    resetMatrix()
                    Log.i(TAG, "double tap -> reset to normal size")
                    notifyZoom()
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (longMode) {
                    // 长图：切换「短边铺满拖动」/「整张适应屏幕」
                    fitWhole = !fitWhole
                    resetMatrix()
                    notifyZoom()
                    return true
                }
                // 普通照片：以点击点为中心放大，再双击复位
                Log.i(TAG, "double tap -> zoom ${DOUBLE_TAP_SCALE}x")
                applyUserScale(DOUBLE_TAP_SCALE, e.x, e.y)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
        })
        scaleDetector = ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pinching = true
                    Log.i(TAG, "pinch begin: two fingers down")
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (drawableW <= 0 || baseScale <= 0f) return true
                    val wanted = (userScale * detector.scaleFactor)
                        .coerceIn(MIN_USER_SCALE, MAX_USER_SCALE)
                    // 以双指中心为不动点缩放
                    applyUserScale(wanted, detector.focusX, detector.focusY)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    pinching = false
                    Log.i(TAG, "pinch end: userScale=$userScale")
                    if (userScale <= SNAP_BACK) {
                        // 捏到接近 1 倍就吸附回正常大小，不留下半吊子的偏移
                        resetMatrix()
                        notifyZoom()
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
        )
    }

    /** Glide 解码完成后调用，传入实际 bitmap 的像素尺寸。 */
    fun onBitmapReady(w: Int, h: Int, long: Boolean) {
        drawableW = w
        drawableH = h
        longMode = long
        fitWhole = false
        resetMatrix()
    }

    /**
     * 换绑下一张前的清理：保留当前画面（新 bitmap 解码完成前不黑屏），
     * 但把缩放 / 拖动 / 双击状态全部复位，避免把上一张的放大倍率带过来。
     * RecyclerView 会在目标页进入屏幕之前调用，所以保留的旧帧不会被看到。
     */
    fun resetInteraction() {
        userScale = 1f
        longMode = false
        fitWhole = false
        pinching = false
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        notifyZoom()
        if (drawableW > 0) resetMatrix()
    }

    fun clearPhoto() {
        drawableW = 0
        drawableH = 0
        longMode = false
        fitWhole = false
        baseScale = 1f
        userScale = 1f
        scale = 1f
        pinching = false
        dragging = false
        parent?.requestDisallowInterceptTouchEvent(false)
        notifyZoom()
        setImageDrawable(null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    private fun resetMatrix() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0f || vh <= 0f || drawableW <= 0 || drawableH <= 0) return
        val dw = drawableW.toFloat()
        val dh = drawableH.toFloat()
        val fitScale = min(vw / dw, vh / dh)
        val fillScale = max(vw / dw, vh / dh)
        baseScale = if (longMode && !fitWhole) fillScale else fitScale
        userScale = 1f
        scale = baseScale
        val contentW = dw * scale
        val contentH = dh * scale
        transX = (vw - contentW) / 2f
        transY = (vh - contentH) / 2f
        // 长图从开头看起：竖长图贴顶、横长图贴左，而不是停在不确定的中段。
        if (longMode && !fitWhole) {
            if (contentH > vh) transY = 0f
            if (contentW > vw) transX = 0f
        }
        clampAndApply()
    }

    /** 以 (fx, fy) 为不动点把用户倍率改成 newUser。 */
    private fun applyUserScale(newUser: Float, fx: Float, fy: Float) {
        val oldScale = scale
        val newScale = baseScale * newUser
        if (oldScale <= 0f || newScale <= 0f) return
        val ratio = newScale / oldScale
        transX = fx - (fx - transX) * ratio
        transY = fy - (fy - transY) * ratio
        userScale = newUser
        scale = newScale
        clampAndApply()
        notifyZoom()
    }

    private fun clampAndApply() {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val cw = drawableW * scale
        val ch = drawableH * scale
        transX = if (cw <= vw) (vw - cw) / 2f else transX.coerceIn(vw - cw, 0f)
        transY = if (ch <= vh) (vh - ch) / 2f else transY.coerceIn(vh - ch, 0f)
        drawMatrix.setScale(scale, scale)
        drawMatrix.postTranslate(transX, transY)
        imageMatrix = drawMatrix
        invalidate()
    }

    /** 当前基准状态下是否有内容超出屏幕（长图才需要内部拖动）。 */
    private fun hasOverflow(): Boolean =
        drawableW * baseScale > width + 1f || drawableH * baseScale > height + 1f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (drawableW <= 0 || width <= 0) return false
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                startTransX = transX
                startTransY = transY
                dragging = false
                pinching = false
                // 未放大的普通照片不接管，父级可以正常翻页 / 切频道。
                val takeOver = zoomed || (longMode && !fitWhole && hasOverflow())
                parent?.requestDisallowInterceptTouchEvent(takeOver)
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    pinching = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                if (pinching) return true
                val dx = event.x - startX
                val dy = event.y - startY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true

                val canPan = zoomed || (longMode && !fitWhole && hasOverflow())
                if (!canPan) {
                    // 普通照片原始大小：不拦截，手势交还 RecyclerView / ViewPager2。
                    return true
                }
                transX = startTransX + dx
                transY = startTransY + dy
                clampAndApply()

                val vh = height.toFloat()
                val ch = drawableH * scale
                val verticalOverflow = ch > vh + 1f
                val atTop = transY >= -0.5f
                val atBottom = transY <= (vh - ch) + 0.5f
                // 内容到头还继续往同方向拖，说明用户想翻到上一张/下一张。
                // 放大态一律不释放，避免放大查看时误翻页或误切频道。
                val release = when {
                    !verticalOverflow -> abs(dy) > touchSlop && abs(dy) > abs(dx)
                    dy > touchSlop && atTop -> true
                    dy < -touchSlop && atBottom -> true
                    else -> false
                }
                if (dragging && release && !zoomed) {
                    parent?.requestDisallowInterceptTouchEvent(false)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // 两指抬到只剩一指，用剩下那根重新做拖动起点，避免画面跳变。
                if (event.pointerCount <= 2) {
                    val remain = if (event.actionIndex == 0) 1 else 0
                    if (remain < event.pointerCount) {
                        startX = event.getX(remain)
                        startY = event.getY(remain)
                        startTransX = transX
                        startTransY = transY
                    }
                    pinching = false
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinching = false
                dragging = false
                if (!zoomed) parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG = "PhotoFeed"
        private const val MAX_USER_SCALE = 4f
        private const val MIN_USER_SCALE = 0.85f
        /** 缩到这个倍率以下就吸附回 1 倍原始大小。 */
        private const val SNAP_BACK = 1.03f
        private const val DOUBLE_TAP_SCALE = 2.5f
    }
}
