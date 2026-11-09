package com.example.photofeed

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.photofeed.data.MediaItemUi
import com.example.photofeed.data.MediaRepository
import com.example.photofeed.data.MotionRecognitionManager
import com.example.photofeed.data.NoteStore
import com.example.photofeed.ui.feed.FeedPageFragment
import com.example.photofeed.ui.guide.GuideActivity
import com.example.photofeed.ui.settings.SettingsActivity
import com.example.photofeed.util.PermissionHelper
import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var tabs: TabLayout
    private lateinit var partialBanner: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDelete: ImageButton
    private lateinit var topBar: View
    private var pageAdapter: FeedPagerAdapter? = null
    private var fullscreenMode = false

    /** 已经消费过的后台识别结果版本，避免重复刷新动图频道。 */
    private var handledRecognizeVersion = 0L

    // 首次落点由 companion 的 initialChannelApplied 统一控制，见 showContent / onResume。

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        if (PermissionHelper.hasAnyAccess(this)) {
            showContent()
        } else {
            showGuide()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        NoteStore.init(applicationContext)

        // 后台识别完成时刷新动图频道；用户此刻多半正在视频频道刷，
        // 刷新在后台进行、不打断当前浏览。onResume 里还会补一次，
        // 覆盖「结果在 MainActivity 处于后台时到达」的情况。
        lifecycleScope.launch {
            MotionRecognitionManager.lastResult.collect { result ->
                if (result != null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    consumeRecognitionResult(result.version)
                }
            }
        }

        viewPager = findViewById(R.id.feedPager)
        tabs = findViewById(R.id.tabs)
        partialBanner = findViewById(R.id.partialAccessBanner)
        btnSettings = findViewById(R.id.btnSettings)
        btnDelete = findViewById(R.id.btnDelete)
        topBar = findViewById(R.id.topBar)

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnDelete.setOnClickListener {
            activeFragment()?.deleteCurrentItem()
        }
        partialBanner.setOnClickListener { requestPermissionsNow() }

        if (!PermissionHelper.hasEverAsked(this) || !PermissionHelper.hasAnyAccess(this)) {
            showGuide()
            return
        }
        showContent()
    }

    private fun showGuide() {
        startActivity(Intent(this, GuideActivity::class.java))
        finish()
    }

    fun requestPermissionsNow() {
        permissionLauncher.launch(PermissionHelper.requestedPermissions())
    }

    private fun showContent() {
        if (pageAdapter != null) {
            refreshBanner()
            return
        }
        pageAdapter = FeedPagerAdapter(this)
        viewPager.adapter = pageAdapter
        viewPager.offscreenPageLimit = 1
        hardenChannelSwipes()

        // 必须在 Fragment 被预创建之前定好位置，否则视频频道会短暂被当成当前频道而开播。
        if (!initialChannelApplied) {
            viewPager.setCurrentItem(DEFAULT_CHANNEL_INDEX, false)
        }

        TabLayoutMediator(tabs, viewPager) { tab, position ->
            tab.text = when (position) {
                0 -> getString(R.string.tab_video)
                1 -> getString(R.string.tab_photo)
                else -> getString(R.string.tab_motion)
            }
        }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                refreshBanner()
                viewPager.post { updateChannelActivation() }
            }
        })
        viewPager.post { updateChannelActivation() }
        refreshBanner()
    }

    /**
     * 左右切频道太容易误触：ViewPager2 内部就是一个横向 RecyclerView，
     * 默认 touchSlop 只有 8dp、fling 门槛也极低，上下刷照片时手稍微一斜、
     * 或者一次很短的横甩，就会连跳两个频道。
     *
     * 这里只抬高它自己那层 RecyclerView 的拖动阈值与最小甩动速度：
     * 轻微 / 斜向滑动不再进入拖拽，只有明确的长横滑或快甩才切页；
     * 顶部 Tab 点击切换完全不受影响。内层竖向 RecyclerView 的翻页阈值不变，
     * Fragment 那边还会在竖向拖动期间额外锁住父级拦截（双保险）。
     *
     * ViewPager2 没有公开的灵敏度 API，mRecyclerView / mTouchSlop /
     * mMinFlingVelocity 都是 AndroidX（非 framework hidden API）字段，
     * 反射失败时静默跳过，功能退化成系统默认但不会崩。
     */
    private fun hardenChannelSwipes() {
        val rv = try {
            val f = ViewPager2::class.java.getDeclaredField("mRecyclerView")
            f.isAccessible = true
            f.get(viewPager) as? RecyclerView
        } catch (e: Exception) {
            Log.w("PhotoFeed", "harden swipe: pager rv unavailable: ${e.message}")
            null
        } ?: return
        val vc = ViewConfiguration.get(this)
        try {
            val f = RecyclerView::class.java.getDeclaredField("mTouchSlop")
            f.isAccessible = true
            f.setInt(rv, vc.scaledTouchSlop * CHANNEL_SLOP_FACTOR)
        } catch (e: Exception) {
            Log.w("PhotoFeed", "harden swipe: touchSlop: ${e.message}")
        }
        try {
            val f = RecyclerView::class.java.getDeclaredField("mMinFlingVelocity")
            f.isAccessible = true
            f.setInt(rv, vc.scaledMinimumFlingVelocity * CHANNEL_FLING_FACTOR)
        } catch (e: Exception) {
            Log.w("PhotoFeed", "harden swipe: fling: ${e.message}")
        }
    }

    private fun channelPosition(channel: String): Int = when (channel) {
        MediaRepository.CHANNEL_VIDEO -> 0
        MediaRepository.CHANNEL_PHOTO -> 1
        else -> 2
    }

    /** 供 Fragment 在异步加载完成时判断自己是不是当前可见频道。 */
    fun isChannelActive(channel: String): Boolean =
        ::viewPager.isInitialized &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
            viewPager.currentItem == channelPosition(channel)

    private fun activeFragment(): FeedPageFragment? =
        supportFragmentManager.fragments.filterIsInstance<FeedPageFragment>()
            .firstOrNull { isChannelActive(it.channel()) }

    private fun consumeRecognitionResult(version: Long) {
        if (version == handledRecognizeVersion) return
        handledRecognizeVersion = version
        refreshMotionChannel()
    }

    /**
     * 后台识别的结果要能让动图频道用上，但不能把用户正在看的位置顶掉：
     * 只有「动图频道不是当前频道」时才重新加载它。
     */
    private fun refreshMotionChannel() {
        if (!::viewPager.isInitialized) return
        if (viewPager.currentItem == channelPosition(MediaRepository.CHANNEL_MOTION)) return
        supportFragmentManager.fragments.filterIsInstance<FeedPageFragment>()
            .firstOrNull { it.channel() == MediaRepository.CHANNEL_MOTION }
            ?.reload()
    }

    /** 只有前台且当前频道 active，其余频道一律暂停并静音。 */
    private fun updateChannelActivation() {
        val foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val pos = viewPager.currentItem
        supportFragmentManager.fragments.filterIsInstance<FeedPageFragment>().forEach { f ->
            f.setChannelActive(foreground && channelPosition(f.channel()) == pos)
        }
    }

    /** 横屏全屏：隐藏整条顶部栏（Tab / 设置 / 删除），只留视频左上角的返回。 */
    fun setFullscreenMode(enter: Boolean) {
        fullscreenMode = enter
        topBar.visibility = if (enter) View.GONE else View.VISIBLE
        if (enter) applyImmersive() else restoreSystemBars()
    }

    /**
     * 真正的沉浸式全屏（抖音 / YouTube / ExoPlayer 官方 demo 的横屏做法）：
     * 1. decor 不再为状态栏、导航栏让位，内容铺满整个物理屏幕 —— 这一步决定
     *    画面中心是「整个屏幕中心」，而不是被状态栏挤下去的「应用内容区中心」；
     * 2. WindowInsetsController 隐藏 systemBars（状态栏 + 导航栏），只有从屏幕
     *    边缘上滑才临时显示，且临时显示时是浮在内容上、不会重新挤压布局；
     * 3. 允许内容延伸到刘海 / 挖孔区域，避免系统再留一条安全区黑边；
     * 4. 系统栏设为透明，杜绝残留色条。
     * 国产 ROM 对 InsetsController 支持可能不完整，额外用 FLAG_FULLSCREEN 兜底。
     */
    private fun applyImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = attrs
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 透明导航栏时系统默认强制加一层对比度衬底，去掉才不会残留一条底色。
            window.isNavigationBarContrastEnforced = false
        }
        // 国产 ROM 的手势提示条往往只认这套 legacy immersive 标记，
        // 作为兜底再压一次，让横屏全屏连底部手势条一起隐藏。
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    /** 退出横屏：把状态栏、导航栏、刘海策略和窗口避让全部还原成竖屏原样。 */
    private fun restoreSystemBars() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = true
        }
        WindowCompat.setDecorFitsSystemWindows(window, true)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.show(WindowInsetsCompat.Type.systemBars())
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val attrs = window.attributes
            attrs.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
            window.attributes = attrs
        }
    }

    /** 旋转、弹窗、切后台再回来都可能让系统重设 UI flags，重新压回全屏。 */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && fullscreenMode) applyImmersive()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Manifest 声明了 configChanges，旋转不重建 Activity，需要主动重新进入沉浸式。
        if (fullscreenMode) window.decorView.post { applyImmersive() }
    }

    override fun onBackPressed() {
        if (fullscreenMode) {
            activeFragment()?.exitFullscreen()
            return
        }
        super.onBackPressed()
    }

    private fun refreshBanner() {
        partialBanner.visibility =
            if (PermissionHelper.hasPartialAccess(this)) View.VISIBLE else View.GONE
    }

    fun onMediaShown(channel: String, item: MediaItemUi) {
    }

    override fun onResume() {
        super.onResume()
        if (pageAdapter == null && PermissionHelper.hasAnyAccess(this)) {
            showContent()
        } else {
            refreshBanner()
        }
        // 结果可能在 MainActivity 处于后台时就到了，collect 那次被前台条件挡掉，
        // 这里回到前台时补一次；识别还在跑也没关系，完成时 collect 会再触发。
        MotionRecognitionManager.lastResult.value?.let { consumeRecognitionResult(it.version) }
        viewPager.post {
            // 系统在 onRestoreInstanceState 之后可能把 ViewPager 恢复到上次频道，
            // 这里再强制一次，确保每次进程冷启动都落在中间的「照片」。
            if (!initialChannelApplied) {
                viewPager.setCurrentItem(DEFAULT_CHANNEL_INDEX, false)
                initialChannelApplied = true
            }
            updateChannelActivation()
        }
        if (fullscreenMode) window.decorView.post { applyImmersive() }
    }

    override fun onPause() {
        super.onPause()
        // 退到后台立即把所有频道的播放关掉，避免后台继续出声。
        supportFragmentManager.fragments.filterIsInstance<FeedPageFragment>()
            .forEach { it.setChannelActive(false) }
    }

    private class FeedPagerAdapter(activity: AppCompatActivity) : FragmentStateAdapter(activity) {
        override fun getItemCount(): Int = 3
        override fun createFragment(position: Int): Fragment = FeedPageFragment.newInstance(
            when (position) {
                0 -> MediaRepository.CHANNEL_VIDEO
                1 -> MediaRepository.CHANNEL_PHOTO
                else -> MediaRepository.CHANNEL_MOTION
            }
        )
    }

    companion object {
        /** 顶部顺序为 视频 / 照片 / 动图，首次启动落在中间的「照片」。 */
        const val DEFAULT_CHANNEL_INDEX = 1

        /** 进程内是否已把首次落点定为照片；进程被杀重启后重置，冷启动仍进照片。 */
        private var initialChannelApplied = false

        /** 频道横滑门槛：默认 8dp 太灵敏，放大到 3 倍才允许进入横向拖拽。 */
        private const val CHANNEL_SLOP_FACTOR = 3

        /** 轻甩就切页也是误触来源，同样放大 3 倍。 */
        private const val CHANNEL_FLING_FACTOR = 3
    }
}
