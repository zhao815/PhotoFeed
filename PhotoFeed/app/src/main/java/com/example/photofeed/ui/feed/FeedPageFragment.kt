package com.example.photofeed.ui.feed

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.photofeed.MainActivity
import com.example.photofeed.R
import com.example.photofeed.data.MediaItemUi
import com.example.photofeed.data.MediaRepository
import com.example.photofeed.util.DeleteHelper
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch

class FeedPageFragment : Fragment(R.layout.fragment_feed) {

    private lateinit var recycler: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var loading: View
    private lateinit var repo: MediaRepository
    private var adapter: FeedAdapter? = null
    private var channel: String = MediaRepository.CHANNEL_PHOTO
    private var items: MutableList<MediaItemUi> = mutableListOf()
    private var pendingDelete: Pair<Int, MediaItemUi>? = null
    private var host: MainActivity? = null
    private var channelActive = false
    private var scrollListener: RecyclerView.OnScrollListener? = null

    /** 当前正在看的那张，供顶部删除按钮使用。 */
    private var currentItem: MediaItemUi? = null

    /** 当前这一轮的完整媒体集合，作为下一轮重新洗牌的源（删除时会同步移除）。 */
    private var roundSource: List<MediaItemUi> = emptyList()
    private var appendingRound = false
    private var roundCount = 1

    private val deleteLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pending = pendingDelete
        pendingDelete = null
        if (result.resultCode == android.app.Activity.RESULT_OK && pending != null) {
            onConfirmedDeleted(pending.first, pending.second)
        } else if (result.resultCode != android.app.Activity.RESULT_CANCELED) {
            view?.let {
                Snackbar.make(it, R.string.delete_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val ARG_CHANNEL = "channel"
        /** 距队尾还剩几张时就把下一轮接上，用户完全感觉不到「刷完了」。 */
        private const val PRELOAD_THRESHOLD = 4

        fun newInstance(channel: String): FeedPageFragment {
            val f = FeedPageFragment()
            f.arguments = Bundle().apply { putString(ARG_CHANNEL, channel) }
            return f
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        channel = requireArguments().getString(ARG_CHANNEL, MediaRepository.CHANNEL_PHOTO)
        repo = MediaRepository(requireContext())
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        host = activity as? MainActivity
        // ViewPager2 会预创建相邻频道：这里一律先 inactive，
        // 只有 MainActivity 明确激活当前频道后才允许播放。
        channelActive = false
        recycler = view.findViewById(R.id.feedRecycler)
        emptyText = view.findViewById(R.id.emptyText)
        loading = view.findViewById(R.id.loading)

        recycler.layoutManager = LinearLayoutManager(requireContext(), RecyclerView.VERTICAL, false)
        PagerSnapHelper().attachToRecyclerView(recycler)
        recycler.setHasFixedSize(true)
        recycler.itemAnimator = null
        // 只保留当前页与相邻一页，滑走的 holder 尽快回收，避免多个 ExoPlayer 同时占内存。
        recycler.setItemViewCacheSize(1)

        load()
    }

    override fun onPause() {
        super.onPause()
        adapter?.pauseAll()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) adapter?.pauseAll()
    }

    fun channel(): String = channel

    /** 由 MainActivity 统一控制：只有当前频道允许播放。 */
    fun setChannelActive(active: Boolean) {
        channelActive = active
        adapter?.setActive(active)
    }

    fun reload() {
        if (!isAdded || !this::recycler.isInitialized) return
        load()
    }

    fun pausePlayback() {
        adapter?.pauseAll()
    }

    fun resumePlayback() {
        if (!channelActive || !isAdded || !this::recycler.isInitialized) return
        val pos = (recycler.layoutManager as? LinearLayoutManager)
            ?.findFirstCompletelyVisibleItemPosition() ?: 0
        if (pos != RecyclerView.NO_POSITION) adapter?.setCurrent(pos)
    }

    /** 顶部右上角的删除按钮：删除当前正在看的那一项。 */
    fun deleteCurrentItem() {
        requestDelete(currentItem ?: return)
    }

    /** 横屏全屏时按返回键退出，而不是退出 App。 */
    fun exitFullscreen() {
        adapter?.exitFullscreen()
    }

    private fun load() {
        loading.visibility = View.VISIBLE
        viewLifecycleOwner.lifecycleScope.launch {
            val loaded = repo.loadChannel(channel)
            if (!isAdded) return@launch
            items = loaded.toMutableList()
            roundSource = items.toList()
            roundCount = 1
            appendingRound = false
            loading.visibility = View.GONE
            if (items.isEmpty()) {
                currentItem = null
                emptyText.visibility = View.VISIBLE
                recycler.visibility = View.GONE
                return@launch
            }
            currentItem = items.firstOrNull()
            emptyText.visibility = View.GONE
            recycler.visibility = View.VISIBLE
            val a = FeedAdapter(items, callbacks)
            adapter = a
            // 异步加载完成时再向宿主确认一次，避免预创建频道抢跑播放。
            channelActive = host?.isChannelActive(channel) ?: false
            a.setCurrent(0)
            a.setActive(channelActive)
            scrollListener?.let { recycler.removeOnScrollListener(it) }
            recycler.adapter = a
            val listener = object : RecyclerView.OnScrollListener() {
                override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                    // 一旦本次手势被判定为竖向翻页，就禁止 ViewPager2 中途抢走事件
                    // 切到别的频道；这是「手稍微一斜就滑到视频」的第二道保险。
                    when (newState) {
                        RecyclerView.SCROLL_STATE_DRAGGING ->
                            rv.requestDisallowInterceptTouchEvent(true)
                        RecyclerView.SCROLL_STATE_IDLE ->
                            rv.requestDisallowInterceptTouchEvent(false)
                    }
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        val pos = (rv.layoutManager as LinearLayoutManager)
                            .findFirstCompletelyVisibleItemPosition()
                        if (pos != RecyclerView.NO_POSITION) {
                            a.setCurrent(pos)
                            items.getOrNull(pos)?.let { item ->
                                currentItem = item
                                viewLifecycleOwner.lifecycleScope.launch {
                                    repo.saveProgress(channel, pos, item.mediaKey)
                                    host?.onMediaShown(channel, item)
                                }
                            }
                        }
                    }
                }

                override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                    maybeAppendNextRound()
                }
            }
            scrollListener = listener
            recycler.addOnScrollListener(listener)
        }
    }

    /**
     * 无限刷：快到队尾时，用新种子重新洗牌生成下一轮并接到列表后面。
     * 一轮内部不重复、轮与轮之间重新随机排布，用户一直刷不到底，
     * 而且追加发生在看到队尾之前，衔接处没有任何「刷完了」的停顿。
     */
    private fun maybeAppendNextRound() {
        if (appendingRound || roundSource.isEmpty() || !isAdded) return
        val lm = recycler.layoutManager as? LinearLayoutManager ?: return
        val last = lm.findLastVisibleItemPosition()
        if (last == RecyclerView.NO_POSITION) return
        if (last < items.size - PRELOAD_THRESHOLD) return
        appendingRound = true
        viewLifecycleOwner.lifecycleScope.launch {
            val next = repo.nextRound(roundSource, items.lastOrNull()?.mediaKey)
            // 等 RecyclerView 布局算完再改数据，避开 computingLayout 期间的 notify。
            recycler.post {
                if (!isAdded || next.isEmpty()) {
                    appendingRound = false
                    return@post
                }
                val from = items.size
                items.addAll(next)
                adapter?.notifyItemRangeInserted(from, next.size)
                roundCount++
                Log.d(
                    "PhotoFeed",
                    "channel=$channel round=$roundCount appended=${next.size} total=${items.size}"
                )
                appendingRound = false
            }
        }
    }

    fun scrollToTop() {
        recycler.scrollToPosition(0)
    }

    private val callbacks = object : FeedAdapter.Callbacks {
        override fun onPageShown(position: Int, item: MediaItemUi?) {
            if (item != null) currentItem = item
            if (item != null && isAdded) {
                viewLifecycleOwner.lifecycleScope.launch {
                    repo.saveProgress(channel, position, item.mediaKey)
                }
                host?.onMediaShown(channel, item)
            }
        }

        override fun onFullscreenChanged(enter: Boolean) {
            host?.setFullscreenMode(enter)
        }
    }

    /**
     * 点右上角删除立即生效：介质只是被移入系统/相册回收站，随时可恢复，
     * 所以不再保留 5 秒「撤销」窗口，点击后直接唤起系统删除确认。
     */
    private fun requestDelete(item: MediaItemUi) {
        val pos = items.indexOf(item)
        if (pos < 0) return
        submitDelete(pos, item)
    }

    private fun submitDelete(position: Int, item: MediaItemUi) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try {
                val pendingIntent =
                    DeleteHelper.buildDeleteRequest(requireContext(), listOf(item.uri))
                pendingDelete = position to item
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pendingIntent).build()
                )
                return
            } catch (e: Exception) {
                android.util.Log.w("PhotoFeed", "createDeleteRequest failed", e)
            }
        }

        when (val result = DeleteHelper.deleteWithRecovery(requireContext(), item.uri)) {
            is DeleteHelper.Result.Deleted -> onConfirmedDeleted(position, item)
            is DeleteHelper.Result.NeedsConfirmation -> {
                pendingDelete = position to item
                deleteLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(result.intentSender).build()
                )
            }
            else -> view?.let {
                Snackbar.make(it, R.string.delete_failed, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    private fun onConfirmedDeleted(position: Int, item: MediaItemUi) {
        adapter?.let { a ->
            val idx = items.indexOf(item)
            if (idx >= 0) {
                items.removeAt(idx)
                // 下一轮的洗牌源也要同步删掉，否则被删的照片会在第二遍里「复活」。
                roundSource = roundSource.filterNot { it.mediaKey == item.mediaKey }
                a.notifyItemRemoved(idx)
                // 删除后原本的下一项会补到当前位置，立刻把它设为「当前正在看的」，
                // 否则删除按钮会指向已删对象，连点删除会没有任何反应。
                val nextPos = idx.coerceAtMost(items.size - 1)
                val next = items.getOrNull(nextPos)
                currentItem = next
                if (next != null) {
                    a.setCurrent(nextPos)
                    recycler.post { recycler.scrollToPosition(nextPos) }
                    viewLifecycleOwner.lifecycleScope.launch {
                        repo.saveProgress(channel, nextPos, next.mediaKey)
                    }
                }
            }
            if (items.isEmpty()) {
                currentItem = null
                emptyText.visibility = View.VISIBLE
                recycler.visibility = View.GONE
            }
        }
        view?.let {
            Snackbar.make(it, R.string.deleted, Snackbar.LENGTH_SHORT).show()
        }
    }
}
